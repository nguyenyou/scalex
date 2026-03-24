# sdbex Command Reference

## All Commands

**Call graph (compiler-only):**

| Command | Arguments | Description |
|---|---|---|
| `callers` | `<symbol>` | Reverse call graph — who calls this. Trait-aware by default. `--depth N` for transitive, `--group-by-file` to group by file |
| `callees` | `<symbol>` | Forward call graph — what does this call |
| `flow` | `<method>` | Downstream call tree with `--depth N`. Use `--smart` on large codebases |
| `path` | `<source> <target>` | Shortest call path between two symbols (BFS) |

**Composite:**

| Command | Arguments | Description |
|---|---|---|
| `explain` | `<symbol>` | One-shot summary: type, callers, callees, members, subtypes |

**Compiler-precise queries:**

| Command | Arguments | Description |
|---|---|---|
| `refs` | `<symbol>` | Zero-false-positive references. `--role def\|ref` to filter |
| `type` | `<symbol>` | Resolved type signature |
| `related` | `<symbol>` | Co-occurring symbols by frequency |
| `occurrences` | `<file>` | All occurrences in a file with roles |

**Navigation:**

| Command | Arguments | Description |
|---|---|---|
| `lookup` | `<symbol>` | Find symbol by FQN or name. `--source-only`/`--smart` excludes generated code |
| `supertypes` | `<symbol>` | Resolved parent type chain |
| `subtypes` | `<symbol>` | Exhaustive subtype tree. `--depth N` for depth |
| `members` | `<symbol>` | Declarations with resolved types. Hides case class synthetics by default |
| `symbols` | `[file]` | List symbols (all or per-file) |

**Batch and daemon:**

| Command | Arguments | Description |
|---|---|---|
| `batch` | `"cmd1" "cmd2" ...` | Run multiple queries in one invocation (~1.5s amortized) |
| `daemon` | `[idle] [max]` | Socket daemon — keeps index hot in memory (<10ms/query). Self-terminates. |
| `index` | — | Force rebuild index |
| `stats` | — | Index statistics |

## Global Options

| Flag | Short | Default | Description |
|---|---|---|---|
| `--workspace` | `-w` | cwd | Set workspace root (must be Mill project root) |
| `--limit` | — | 50 | Max results (0=unlimited) |
| `--kind` | — | all | Filter by symbol kind AND narrow resolution |
| `--in` | — | — | Scope resolution by owner class, file, or package |
| `--depth` | — | varies | Max recursion depth (callers: 1, flow/subtypes: 3, path: 5) |
| `--smart` | — | off | Auto-filter noise: accessors, generated code, protobuf, combinators. In flow: same-module only |
| `--exclude` | — | — | Exclude symbols matching FQN or file path (comma-separated) |
| `--exclude-test` | — | off | Exclude test source directories |
| `--exclude-pkg` | — | — | Exclude by package prefix (dots auto-converted to /) |
| `--group-by-file` | — | off | Group callers output by source file |
| `--role` | — | all | Filter occurrences by role (def/ref) |
| `--verbose` | `-v` | off | Full signatures, properties, overrides |
| `--source-only` | — | off | Exclude generated sources from lookup |
| `--no-accessors` | — | off | Exclude val/var accessors from flow/callees |
| `--timings` | — | off | Print timing breakdown to stderr |
| `--version` | — | — | Print version |

## Symbol Resolution

sdbex resolves symbol names in this order:

1. **Exact FQN** — `com/example/MyService#` matches directly
2. **FQN separator swap** — tries `#` ↔ `.` swap with a hint
3. **Suffix match** — `MyService#` matches `com/example/MyService#`
4. **Display name** — case-insensitive match
5. **Partial name** — substring match

Results ranked: source before generated, classes/traits first, locals last.

**FQN syntax**: `/` for packages, `#` for types, `.` for terms:
- `scala/collection/List#` — type
- `example/Main.main().` — method on object
- `example/Main#handle().` — method on class

## Kind Values

Use with `--kind`: `class`, `trait`, `object`, `method`, `field`, `type`, `package`, `constructor`, `parameter`, `typeparam`, `interface`, `local`.
