# Daemon Reference

## Non-interactive shells (backgrounding with &)

Backgrounding the daemon with `&` closes stdin, triggering immediate exit (Layer 1: stdin EOF). Two workarounds:

**Option A: `--fifo` flag (recommended)** — reads from a named pipe instead of stdin:

```bash
mkfifo .scalex/daemon.fifo .scalex/daemon.out
bash "/path/to/sdbx-cli" daemon --fifo .scalex/daemon.fifo --parent-pid $$ -w /project \
  > .scalex/daemon.out &

# Open persistent file descriptors (avoids re-reading from byte 0)
exec 3>.scalex/daemon.fifo 4<.scalex/daemon.out

# Wait for ready signal
read -t 30 ready <&4

# Send a query and read the response
echo '{"command":"callers","args":["handleRequest"]}' >&3
read -t 10 resp <&4

# Clean up
echo '{"command":"shutdown"}' >&3
exec 3>&- 4<&-
rm -f .scalex/daemon.fifo .scalex/daemon.out
```

For simpler plumbing, prefer `coproc` (Option B below) — it keeps bidirectional pipes alive natively without FIFOs.

**Option B: `coproc`** — keeps bidirectional pipes alive without a FIFO:

```zsh
# zsh
coproc bash "/path/to/sdbx-cli" daemon --parent-pid $$ -w /project 2>/dev/null
read -t 30 ready <&p          # wait for ready signal
print -p '{"command":"callers","args":["handleRequest"]}'
read -t 10 resp <&p
print -p '{"command":"shutdown"}'
```

```bash
# bash
coproc SDBX { bash "/path/to/sdbx-cli" daemon --parent-pid $$ -w /project 2>/dev/null; }
read -t 30 ready <&${SDBX[0]}
echo '{"command":"callers","args":["handleRequest"]}' >&${SDBX[1]}
read -t 10 resp <&${SDBX[0]}
```

## Request/response protocol (JSON-lines on stdin/stdout)

Send one JSON object per line to stdin. The daemon responds with one JSON line on stdout per request.

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

1. **Stdin/FIFO EOF** — if you close stdin or the FIFO (or your process dies), the daemon exits immediately
2. **Parent PID monitoring** — pass `--parent-pid <PID>` and the daemon exits when that process dies (works even if stdin stays open)
3. **Idle timeout** — exits after 5 minutes of no requests (configurable, first positional arg)
4. **Max lifetime** — exits after 30 minutes regardless of activity (configurable, second positional arg)
5. **Per-query timeout** — any query taking >30s returns a timeout error instead of hanging
6. **Heap pressure** — exits if JVM memory usage exceeds 85% after GC
7. **Startup timeout** — exits if index building takes >120s
8. **Shutdown hook** — SIGTERM/SIGINT triggers clean exit

You do not need to worry about cleanup — the daemon handles it. But you can send `{"command":"shutdown"}` for explicit clean shutdown.
