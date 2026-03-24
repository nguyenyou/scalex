# Daemon Reference

## Socket mode (recommended for coding agents)

Socket mode lets any process connect to a running daemon, send a query, and read the response — no persistent shell needed.

```bash
# Start daemon in socket mode (backgrounding is fine)
bash "/path/to/sdbx-cli" daemon --socket -w /project &

# Wait for ready signal on stdout
# Non-daemon commands auto-detect the socket and forward queries transparently:
sdbx callers handleRequest -w /project  # <10ms via socket, falls back to local index if no daemon
```

The socket is created at a short path under `/tmp/` (hashed from workspace path) to respect the macOS 104-byte limit on Unix domain socket paths. Requires Java 16+.

## Stdin mode

The default mode reads JSON-lines from stdin and writes responses to stdout. Useful with `coproc` or heredoc when all queries happen in a single shell:

```bash
# coproc (zsh)
coproc bash "/path/to/sdbx-cli" daemon -w /project 2>/dev/null
read -t 30 ready <&p          # wait for ready signal
print -p '{"command":"callers","args":["handleRequest"]}'
read -t 10 resp <&p
print -p '{"command":"shutdown"}'
```

```bash
# coproc (bash)
coproc SDBX { bash "/path/to/sdbx-cli" daemon -w /project 2>/dev/null; }
read -t 30 ready <&${SDBX[0]}
echo '{"command":"callers","args":["handleRequest"]}' >&${SDBX[1]}
read -t 10 resp <&${SDBX[0]}
```

```bash
# heredoc — all queries known upfront
bash "/path/to/sdbx-cli" daemon -w /project <<'QUERIES'
{"command":"callers","args":["handleRequest"]}
{"command":"subtypes","args":["Repository"]}
QUERIES
```

## Request/response protocol (JSON-lines)

Send one JSON object per line. The daemon responds with one JSON line per request.

**Request format:**
```json
{"command":"callers","args":["handleRequest"],"flags":{"--kind":"method","--depth":"3"}}
```

- `command` (required): any command name (`callers`, `refs`, `lookup`, `stats`, `heartbeat`, `shutdown`, etc.)
- `args` (optional): list of positional arguments
- `flags` (optional): map of flags to values. Boolean flags use `"true"` as value. Keys can include `--` prefix or not.

**Response format:**
```json
{"ok":true,"result":{...}}
{"ok":false,"error":"parse_error","message":"missing 'command' field"}
{"ok":false,"error":"unknown_command","message":"Unknown command: foo"}
{"ok":false,"error":"timeout","message":"Query timed out after 30s"}
{"ok":false,"error":"internal","message":"..."}
```

**Special daemon commands:**
- `{"command":"heartbeat"}` — returns `{"ok":true}`, resets idle timer
- `{"command":"shutdown"}` — returns `{"ok":true}`, then exits cleanly
- `{"command":"rebuild"}` — force-rebuilds the index, returns stats with `"event":"rebuilt"`
- `{"command":"stats"}` — returns index statistics

**Examples:**
```json
{"command":"callers","args":["processPayment"],"flags":{"--kind":"method","--exclude":"test"}}
{"command":"refs","args":["Config"]}
{"command":"callees","args":["createOrder"],"flags":{"--smart":"true","--kind":"method"}}
{"command":"explain","args":["processPayment"],"flags":{"--kind":"method"}}
{"command":"batch","args":["callers handleRequest","subtypes Repository","members Config"]}
{"command":"stats"}
{"command":"heartbeat"}
```

## Auto-rebuild on staleness

The daemon checks if `.semanticdb` files have changed before each query (~7ms check). If files are newer than the last build, it automatically rebuilds — no need to send `rebuild` manually after recompilation.

## Safety guarantees

The daemon is designed to self-terminate aggressively — it will never become a zombie process. Eight independent termination layers ensure this:

1. **Stdin EOF** (stdin mode only) — if you close stdin (or your process dies), the daemon exits immediately
3. **Idle timeout** — exits after 5 minutes of no requests (configurable, first positional arg)
4. **Max lifetime** — exits after 30 minutes regardless of activity (configurable, second positional arg)
5. **Per-query timeout** — any query taking >30s returns a timeout error instead of hanging
6. **Heap pressure** — exits if JVM memory usage exceeds 85% after GC
7. **Startup timeout** — exits if index building takes >120s
8. **Shutdown hook** — SIGTERM/SIGINT triggers clean exit (socket file cleaned up automatically)

You do not need to worry about cleanup — the daemon handles it. But you can send `{"command":"shutdown"}` for explicit clean shutdown.
