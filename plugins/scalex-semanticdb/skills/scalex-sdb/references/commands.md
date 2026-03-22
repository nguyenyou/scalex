# scalex-sdb Command Reference

## All Commands

| Command | Arguments | Description |
|---|---|---|
| `lookup` | `<symbol>` | Find symbol by FQN or display name |
| `refs` | `<symbol>` | Compiler-precise references |
| `supertypes` | `<symbol>` | Parent type chain |
| `subtypes` | `<symbol>` | Who extends this |
| `members` | `<symbol>` | Declarations/members of a type |
| `type` | `<symbol>` | Resolved type signature |
| `callers` | `<symbol>` | Reverse call graph |
| `callees` | `<symbol>` | Forward call graph |
| `flow` | `<method>` | Recursive downstream call tree |
| `related` | `<symbol>` | Co-occurring symbols by frequency |
| `diagnostics` | `[file]` | Compiler warnings/errors |
| `symbols` | `[file]` | List symbols (all or per-file) |
| `occurrences` | `<file>` | All occurrences in a file |
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

SemanticDB fully-qualified names use `/` for packages, `#` for types, `.` for terms:
- `scala/collection/List#` — type (class/trait)
- `scala/Predef.println(+1).` — term (method/val), `+1` disambiguates overloads
- `example/Main.` — term (object)
- `example/Main.main().` — method

## Kind Values

Use with `--kind` flag: `class`, `trait`, `object`, `method`, `field`, `type`, `package`, `packageobj`, `constructor`, `parameter`, `typeparam`, `macro`, `interface`, `local`.

## JSON Output

All commands support `--json`. Output is a single JSON object per invocation. Key fields vary by command:

- `lookup`/`members`/`symbols`: `{"header", "total", "symbols": [...]}`
- `refs`/`occurrences`: `{"header", "total", "occurrences": [...]}`
- `diagnostics`: `{"header", "total", "diagnostics": [...]}`
- `flow`: `{"header", "lines": [...]}`
- `related`: `{"header", "total", "related": [...]}`
- `stats`: `{"files", "symbols", "occurrences", "diagnostics", "buildTimeMs", "cached"}`
- Errors: `{"error": "not_found"|"usage", "message": "..."}`
