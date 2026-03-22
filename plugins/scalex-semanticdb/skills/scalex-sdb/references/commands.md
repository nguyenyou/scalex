# scalex-sdb Command Reference

## All Commands

**Call graph (compiler-only):**

| Command | Arguments | Description |
|---|---|---|
| `flow` | `<method>` | Downstream call tree with `--depth N` |
| `callers` | `<symbol>` | Reverse call graph — who calls this |
| `callees` | `<symbol>` | Forward call graph — what does this call |

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
| `--kind` | — | all | Filter by symbol kind |
| `--role` | — | all | Filter occurrences by role (def/ref) |
| `--depth` | — | 3 | Max recursion depth for `flow` and `subtypes` |
| `--timings` | — | off | Print timing breakdown to stderr |
| `--version` | — | — | Print version |

## Symbol Resolution

When you pass a symbol name, scalex-sdb resolves it in this order:

1. **Exact FQN** — `com/example/MyService#` matches directly
2. **Suffix match** — `MyService#` matches `com/example/MyService#`
3. **Display name** — `MyService` matches by case-insensitive display name
4. **Partial name** — `Service` matches any symbol containing "Service"

Results are ranked: classes/traits first, then methods/fields, locals last.

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
- `flow`: `{"header", "lines": [...]}`
- `related`: `{"header", "total", "related": [...]}`
- `stats`: `{"files", "symbols", "occurrences", "buildTimeMs", "cached"}`
- `batch`: `{"batch": [{"command": "...", "result": {...}}, ...]}`
- Errors: `{"error": "not_found"|"usage", "message": "..."}`
