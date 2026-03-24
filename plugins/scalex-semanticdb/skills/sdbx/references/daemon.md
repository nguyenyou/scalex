# Daemon Reference

## Usage

The daemon listens on a Unix domain socket. Any process can connect, send a query, and read the response — no persistent shell needed.

```bash
# Start daemon (backgrounding is fine)
bash "/path/to/sdbx-cli" daemon -w /project &

# Non-daemon commands auto-detect the daemon and forward queries transparently:
sdbx callers handleRequest -w /project  # <10ms via socket, falls back to local index if no daemon
```

The socket is created at a short path under `/tmp/` (hashed from workspace path) to respect the macOS 104-byte limit on Unix domain socket paths. Requires Java 16+.

## Wire protocol

The daemon uses a text-based wire protocol over the Unix domain socket. Non-daemon CLI commands auto-detect the daemon and forward queries transparently — output is identical whether the daemon is running or not.

**Response format** (over socket):
- Success: `SDBX_OK\n<text output>` — human-readable text, same as CLI
- Error: `SDBX_ERR\n<error message>` — CLI prints to stderr, exits with code 1

**Request format** (internal, sent by CLI):
```json
{"command":"callers","args":["handleRequest"],"flags":{"kind":"method","depth":"3"}}
```

- `command` (required): any command name
- `args` (optional): list of positional arguments
- `flags` (optional): map of flags to values. Boolean flags use `"true"` as value.

**Daemon-specific commands:** `heartbeat` (resets idle timer), `shutdown` (exits cleanly), `rebuild` (force-rebuilds index), `stats` (index statistics).

## Auto-rebuild on staleness

The daemon checks if `.semanticdb` files have changed before each query (~7ms check). If files are newer than the last build, it automatically rebuilds — no need to send `rebuild` manually after recompilation.

## Safety guarantees

The daemon is designed to self-terminate aggressively — it will never become a zombie process. Seven independent termination layers ensure this:

1. **Idle timeout** — exits after 5 minutes of no requests (configurable, first positional arg)
2. **Max lifetime** — exits after 30 minutes regardless of activity (configurable, second positional arg)
3. **Shutdown command** — explicit shutdown exits after sending response
4. **Per-query timeout** — any query taking >30s returns a timeout error instead of hanging
5. **Heap pressure** — exits if JVM memory usage exceeds 85% after GC
6. **Startup timeout** — exits if index building takes >120s
7. **Shutdown hook** — SIGTERM/SIGINT triggers clean exit (socket file cleaned up automatically)

You do not need to worry about cleanup — the daemon handles it.
