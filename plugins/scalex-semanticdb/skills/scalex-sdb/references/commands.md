# scalex-sdb Command Reference

## All Commands

**Call graph (compiler-only):**

| Command | Arguments | Description |
|---|---|---|
| `flow` | `<method>` | Downstream call tree with `--depth N` |
| `callers` | `<symbol>` | Reverse call graph — who calls this (`--depth N` for transitive) |
| `callees` | `<symbol>` | Forward call graph — what does this call |
| `path` | `<source> <target>` | Shortest call path between two symbols (BFS) |

**Composite:**

| Command | Arguments | Description |
|---|---|---|
| `explain` | `<symbol>` | One-shot summary: type, callers, callees, members, subtypes |

**Compiler-precise queries:**

| Command | Arguments | Description |
|---|---|---|
| `refs` | `<symbol>` | Zero-false-positive references |
| `type` | `<symbol>` | Resolved type signature |
| `related` | `<symbol>` | Co-occurring symbols by frequency |
| `occurrences` | `<file>` | All occurrences in a file with roles |

**Navigation (with resolved types):**

| Command | Arguments | Description |
|---|---|---|
| `lookup` | `<symbol>` | Find symbol by FQN or display name. `--source-only`/`--smart` excludes generated code |
| `supertypes` | `<symbol>` | Resolved parent type chain |
| `subtypes` | `<symbol>` | Who extends this |
| `members` | `<symbol>` | Declarations with resolved types. Hides case class synthetics by default (`--verbose` to show) |
| `symbols` | `[file]` | List symbols (all or per-file) |

**Batch:**

| Command | Arguments | Description |
|---|---|---|
| `batch` | `"cmd1" "cmd2" ...` | Run multiple queries in one invocation |

**Daemon (for coding agents):**

| Command | Arguments | Description |
|---|---|---|
| `daemon` | `[idle] [max]` | Stdin/stdout JSON-lines server (keeps index hot, <10ms/query) |

Daemon-only options: `--parent-pid PID` (monitor parent process, auto-exit on parent death).
Positional args: idle timeout seconds (default: 300), max lifetime seconds (default: 1800).

**Index:**

| Command | Arguments | Description |
|---|---|---|
| `index` | — | Force rebuild index |
| `stats` | — | Index statistics |

## Global Options

| Flag | Short | Default | Description |
|---|---|---|---|
| `--workspace` | `-w` | cwd | Set workspace root |
| `--limit` | — | 50 | Max results (0=unlimited) |
| `--json` | — | off | JSON output for all commands |
| `--verbose` | `-v` | off | Show full signatures, properties, overrides |
| `--kind` | — | all | Filter by symbol kind AND narrow resolution in flow/callees/callers |
| `--role` | — | all | Filter occurrences by role (def/ref) |
| `--depth` | — | varies | Max recursion depth (callers: 1, flow/subtypes: 3, path: 5) |
| `--no-accessors` | — | off | Exclude val/var accessors from flow/callees |
| `--smart` | — | off | Auto-filter noise: accessors, generated code, protobuf, combinators, case class synthetics. In members: hides synthetics + accessors. In lookup: excludes generated sources. In flow: same-module only. |
| `--source-only` | — | off | Exclude generated/compiled sources from lookup results |
| `--exclude` | — | — | Exclude symbols matching FQN or file path (comma-separated) |
| `--exclude-test` | — | off | Exclude symbols from test source directories |
| `--exclude-pkg` | — | — | Exclude symbols by package prefix (comma-separated, dots auto-converted to /) |
| `--in` | — | — | Scope symbol resolution by owner class, file, or package |
| `--timings` | — | off | Print timing breakdown to stderr |
| `--version` | — | — | Print version |

## Symbol Resolution

When you pass a symbol name, scalex-sdb resolves it in this order:

1. **Exact FQN** — `com/example/MyService#` matches directly
2. **FQN separator swap** — if exact FQN fails, tries `#` ↔ `.` swap (class member ↔ object member) with a hint
3. **Suffix match** — `MyService#` matches `com/example/MyService#`
4. **Display name** — `MyService` matches by case-insensitive display name
5. **Partial name** — `Service` matches any symbol containing "Service"

Results are ranked: non-local before local, source before generated (protobuf/codegen), classes/traits first, then methods/fields, locals last.

SemanticDB fully-qualified names use `/` for packages, `#` for types, `.` for terms:
- `scala/collection/List#` — type (class/trait)
- `scala/Predef.println(+1).` — term (method/val), `+1` disambiguates overloads
- `example/Main.` — term (object)
- `example/Main.main().` — method

## Kind Values

Use with `--kind` flag: `class`, `trait`, `object`, `method`, `field`, `type`, `package`, `packageobj`, `constructor`, `parameter`, `typeparam`, `macro`, `interface`, `local`.

## JSON Output

All commands support `--json`. Output is a single JSON object per invocation:

- `lookup`/`members`/`symbols`: `{"header", "total", "symbols": [...]}`
- `refs`/`occurrences`: `{"header", "total", "occurrences": [...]}`
- `flow`/`path`: `{"header", "lines": [...]}`
- `explain`: `{"symbol", "file", "line", "callers", "totalCallers", "callees", "totalCallees", "parents", "members", "totalMembers", "subtypes", "totalSubtypes"}`
- `related`: `{"header", "total", "related": [...]}`
- `stats`: `{"files", "symbols", "occurrences", "buildTimeMs", "cached", "parsedCount", "skippedCount"}`
- `batch`: `{"batch": [{"command": "...", "result": {...}}, ...]}`
- Errors: `{"error": "not_found"|"usage", "message": "..."}`

## Daemon Protocol

The daemon communicates via JSON-lines on stdin/stdout. It supports all regular commands plus daemon-specific ones.

### Startup

On launch, the daemon builds the index and emits a ready signal:
```json
{"ok":true,"event":"ready","files":142,"symbols":3580,"occurrences":28400,"buildTimeMs":1823}
```

### Request format

One JSON object per line on stdin:
```json
{"command":"callers","args":["handleRequest"],"flags":{"--kind":"method","--depth":"3"}}
```

- `command` (required): command name
- `args` (optional): positional arguments as string array
- `flags` (optional): flag-value pairs. Boolean flags use `"true"`. Keys may include `--` prefix.

### Response format

One JSON object per line on stdout:
```json
{"ok":true,"result":{...}}
{"ok":false,"error":"parse_error|unknown_command|timeout|internal","message":"..."}
```

### Daemon-specific commands

| Command | Response | Effect |
|---|---|---|
| `heartbeat` | `{"ok":true}` | Resets idle timer, no-op otherwise |
| `shutdown` | `{"ok":true}` | Daemon exits after response |
| `rebuild` | `{"ok":true,"event":"rebuilt","files":N,...}` | Force-rebuilds index |
| `stats` | `{"ok":true,"result":{"files":N,...}}` | Returns index statistics |

### Auto-rebuild

Before each query, the daemon checks if `.semanticdb` directories have been modified (mtime check, ~7ms). If stale, it automatically rebuilds before dispatching the query.

### Safety guarantees

Eight termination layers ensure the daemon never becomes a zombie:

1. **Stdin EOF** — parent dies → pipe closes → daemon exits immediately
2. **Parent PID exit** — `--parent-pid PID` → `ProcessHandle.onExit()` → daemon exits when parent dies
3. **Idle timeout** — no request for N seconds → exit (default: 300s, configurable)
4. **Max lifetime** — hard cap regardless of activity (default: 1800s, configurable)
5. **Per-query timeout** — query >30s → returns `{"ok":false,"error":"timeout",...}`, daemon stays alive
6. **Heap pressure** — used heap >85% after GC → exit
7. **Startup timeout** — index build >120s → exit with code 1
8. **Shutdown hook** — SIGTERM/SIGINT → clean exit
