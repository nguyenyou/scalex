---
name: sdbex
description: "Compiler-precise Scala code intelligence from SemanticDB data. Call graph analysis (callers, callees, flow), precise references with zero false positives, resolved type signatures, related symbol discovery. Requires compiled .semanticdb files (Scala 2/3). Triggers: \"who calls X\", \"what does X call\", \"trace the call flow from X\", \"what calls this method\", \"precise references of X\", \"what type does X return\", \"show callers of X\", \"call graph of X\", \"what symbols are related to X\", \"what methods does this class have with types\", \"trace execution flow\", \"trace the service layers\", \"how does A reach B\", \"find call path from X to Y\", \"explain this method\", or when you need compiler-precise (not text-based) code intelligence. Use this over scalex when you need call graphs, type information, or zero-false-positive references. Use scalex for zero-setup exploration; use sdbex when the project has been compiled with SemanticDB enabled. Daemon mode: <10ms queries."
---

You have access to `sdbex`, a compiler-precise Scala code intelligence CLI. It reads `.semanticdb` files produced by the Scala compiler and provides capabilities that source-text tools cannot: reverse call graphs, zero-false-positive references, resolved types, and exhaustive subtype trees.

## Before your first query

Generate SemanticDB data for the whole codebase (Mill only):

```bash
./mill __.semanticDbData
```

Re-run after code changes to keep results fresh. Without this, sdbex has nothing to read and every query returns empty. The `__` wildcard covers all modules — partial coverage silently returns incomplete results.

## Setup

A bootstrap script at `scripts/sdbex-cli` (next to this SKILL.md) handles downloading, caching, and auto-upgrading. Requires Java 11+.

**Always use the absolute path to `sdbex-cli`** — do NOT store it in a shell variable (agent shells are non-persistent):

```bash
bash "/absolute/path/to/skills/sdbex/scripts/sdbex-cli" callers handleRequest -w /project
```

Replace `/absolute/path/to/skills/sdbex` with the actual path to this skill's directory.

## Commands

### Call graph (the primary reason to use sdbex)

#### `callers <symbol>` — who calls this method?

The killer feature — no scalex equivalent. Returns the exact enclosing method of each call site. Trait-aware by default: also finds callers through trait/abstract method indirection.

```bash
sdbex callers handleRequest -w /project
sdbex callers handleRequest --kind method --exclude "test,integ" -w /project
sdbex callers handleRequest --group-by-file -w /project

# Transitive — trace back through service layers:
sdbex callers add --depth 3 -w /project
```

#### `callees <symbol>` — what does this method call?

Flat list of symbols referenced in a method's body. Use `--smart` on large codebases to filter accessors, generated code, and functional plumbing.

```bash
sdbex callees createOrder --kind method --smart -w /project
```

#### `flow <method>` — recursive call tree

Like `callees` but recursive. **Always use `--smart` on large codebases** — without it, depth 3 can produce thousands of lines of noise.

```bash
sdbex flow createOrder --kind method --depth 3 --smart -w /project
```

#### `path <source> <target>` — call path between two symbols

BFS on the call graph. Default max depth 5.

```bash
sdbex path OrderServer.createOrder EventStoreOperations.add -w /project
```

#### `explain <symbol>` — one-shot summary

Combines type signature, callers, callees, members, and subtypes in a single output.

```bash
sdbex explain createOrder --kind method -w /project
```

### Compiler-precise queries

#### `refs <symbol>` — zero-false-positive references

Every occurrence is compiler-resolved — distinguishes overloads, resolves across renames.

```bash
sdbex refs PaymentService -w /project
sdbex refs processPayment --role ref -w /project
```

#### `subtypes <symbol>` — exhaustive subtype tree

Compiler-verified — catches every implementation including those in unexpected packages.

```bash
sdbex subtypes Repository --depth 2 -w /project
```

#### `type <symbol>` — resolved type signature

Shows inferred types not written in source.

```bash
sdbex type configLayer -w /project
```

#### `related <symbol>` — co-occurring symbols

Discovers symbols that frequently appear alongside the target, ranked by frequency.

```bash
sdbex related UserService -w /project
```

### Navigation

| Command | Description |
|---|---|
| `lookup <symbol>` | Find symbol by FQN or name. `--source-only`/`--smart` excludes generated code |
| `supertypes <symbol>` | Resolved parent type chain |
| `members <symbol>` | Declarations with resolved types. `--smart` hides synthetics + accessors |
| `symbols [file]` | Symbols in a file |
| `occurrences <file>` | All symbol occurrences with roles |

### Batch and daemon

**Batch** — amortizes ~1.5s index load across multiple queries:

```bash
sdbex batch "callers handleRequest" "callees processPayment --smart" -w /project
```

**Daemon** — keeps index hot in memory for <10ms queries. Start once per session:

```bash
bash "/path/to/sdbex-cli" daemon -w /project &
```

All subsequent CLI commands auto-detect the daemon and forward queries transparently. Falls back to local index if daemon isn't running. The daemon self-terminates after 5 min idle (or 30 min max). No cleanup needed.

| Scenario | Use |
|---|---|
| 1-2 queries | CLI directly |
| 3-5 related queries | `batch` |
| Many queries across a session | `daemon` |

## Options

| Flag | Description |
|---|---|
| `-w, --workspace PATH` | Set workspace (default: cwd) |
| `--limit N` | Max results (default: 50, 0=unlimited) |
| `--kind K` | Filter by kind AND narrow symbol resolution (class, trait, object, method, field, type) |
| `--in <scope>` | Scope resolution by owner class, file, or package. E.g. `--in OrderService` |
| `--depth N` | Max recursion depth (callers: 1, flow/subtypes: 3, path: 5) |
| `--smart` | Auto-filter noise: accessors, generated code, protobuf, combinators. In flow: same-module only |
| `--exclude "p1,p2,..."` | Exclude symbols matching FQN or file path |
| `--exclude-test` | Exclude test source directories |
| `--exclude-pkg "p1,p2,..."` | Exclude by package prefix (dots auto-converted to /) |
| `--group-by-file` | Group callers output by source file |
| `--role R` | Filter occurrences (def/ref) |
| `--verbose, -v` | Full signatures and properties |
| `--source-only` | Exclude generated sources from lookup |
| `--no-accessors` | Exclude val/var accessors from flow/callees |

## Disambiguating symbols

If a name matches multiple symbols, sdbex picks the first by rank and prints a hint to stderr. Use these to disambiguate:

1. **`--kind method`** — narrows to methods only (most common fix)
2. **`--in OwnerClass`** — scopes to a specific owner class or file
3. **Full FQN** — zero ambiguity: `"com/example/OrderService#createOrder()."`

FQN syntax: `/` for packages, `#` for types, `.` for terms. E.g.:
- `example/MyService#` — class/trait
- `example/MyService#handle().` — method on a class
- `example/MyService.handle().` — method on an object

## Common workflows

```bash
# Impact analysis — who calls this? (exclude tests)
sdbex callers processPayment --exclude-test -w /project

# Transitive impact — trace back through service layers
sdbex callers add --depth 3 --exclude-test -w /project

# Understand a method — what it calls
sdbex callees createOrder --kind method --smart -w /project

# How does endpoint A reach database method B?
sdbex path OrderServer.createOrder EventStoreOperations.add -w /project

# Quick orientation — callers + callees + type in one shot
sdbex explain processPayment --kind method -w /project

# Multiple queries at once
sdbex batch "lookup UserService" "subtypes AuthProvider" "callers handleLogin" -w /project
```

## Troubleshooting

- **`permission denied`**: Run `chmod +x /path/to/sdbex-cli` once.
- **No .semanticdb files**: Run `./mill __.semanticDbData` first.
- **Empty or wrong results after code changes**: Re-run `./mill __.semanticDbData`.
