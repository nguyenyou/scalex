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
| `explain` | `<symbol>` | One-shot summary: type, callers, callees, members |

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
| `lookup` | `<symbol>` | Find symbol by FQN or display name |
| `supertypes` | `<symbol>` | Resolved parent type chain |
| `subtypes` | `<symbol>` | Who extends this |
| `members` | `<symbol>` | Declarations with resolved types |
| `symbols` | `[file]` | List symbols (all or per-file) |

**Batch:**

| Command | Arguments | Description |
|---|---|---|
| `batch` | `"cmd1" "cmd2" ...` | Run multiple queries in one invocation |

**Index:**

| Command | Arguments | Description |
|---|---|---|
| `index` | — | Force rebuild index |
| `stats` | — | Index statistics |

## Global Options

| Flag | Short | Default | Description |
|---|---|---|---|
| `--workspace` | `-w` | cwd | Set workspace root |
| `--semanticdb-path` | — | auto | Explicit path to `.semanticdb` files |
| `--limit` | — | 50 | Max results (0=unlimited) |
| `--json` | — | off | JSON output for all commands |
| `--verbose` | `-v` | off | Show full signatures, properties, overrides |
| `--kind` | — | all | Filter by symbol kind AND narrow resolution in flow/callees/callers |
| `--role` | — | all | Filter occurrences by role (def/ref) |
| `--depth` | — | varies | Max recursion depth (callers: 1, flow/subtypes: 3, path: 5) |
| `--no-accessors` | — | off | Exclude val/var accessors from flow/callees |
| `--smart` | — | off | Auto-filter infrastructure noise (accessors, generated, protobuf, monadic combinators) |
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
- `explain`: `{"symbol", "file", "line", "callers", "totalCallers", "callees", "totalCallees", "parents", "members", "totalMembers"}`
- `related`: `{"header", "total", "related": [...]}`
- `stats`: `{"files", "symbols", "occurrences", "buildTimeMs", "cached"}`
- `batch`: `{"batch": [{"command": "...", "result": {...}}, ...]}`
- Errors: `{"error": "not_found"|"usage", "message": "..."}`
