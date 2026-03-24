---
name: sdbex
description: "Compiler-precise Scala code intelligence from SemanticDB data. Call graph analysis (callers, callees, flow), precise references with zero false positives, resolved type signatures, related symbol discovery. Requires compiled .semanticdb files (Scala 2/3). Triggers: \"who calls X\", \"what does X call\", \"trace the call flow from X\", \"what calls this method\", \"precise references of X\", \"what type does X return\", \"show callers of X\", \"call graph of X\", \"what symbols are related to X\", \"what methods does this class have with types\", \"trace execution flow\", \"trace the service layers\", \"how does A reach B\", \"find call path from X to Y\", \"explain this method\", or when you need compiler-precise (not text-based) code intelligence. Use this over scalex when you need call graphs, type information, or zero-false-positive references. Use scalex for zero-setup exploration; use sdbex when the project has been compiled with SemanticDB enabled. Daemon mode: <10ms queries."
---

You have access to `sdbex`, a compiler-precise Scala code intelligence CLI. It reads `.semanticdb` files produced by the Scala compiler and provides capabilities that source-text parsing fundamentally cannot:

- **Reverse call graph** (`callers`) — "who calls this method?" resolved to the exact enclosing method, not just the file. No scalex equivalent.
- **Zero-false-positive references** (`refs`) — the compiler resolves each reference to its exact fully-qualified symbol. `refs Config` in scalex matches every `Config` across all packages; sdbex resolves each to its exact FQN.
- **Exhaustive subtypes** (`subtypes`) — compiler-verified, catches everything scalex's text-based `impl` might miss.
- **Forward call graph** (`callees`, `flow`) — what does a method call? `callees` gives a flat list; `flow` gives a recursive tree. Use `--smart` on large codebases to filter infrastructure noise.
- **Resolved types** (`type`) — source may have `val x = ???` with no annotation. Only the compiler knows the actual type.
- **Related symbols** (`related`) — precise co-occurrence ranked by frequency, not text matching.

**Use scalex as your primary tool** for exploration — it's zero-setup and fast. Reach for sdbex when you need precision: `callers`, precise `refs`, exhaustive `subtypes`, or resolved types.

First run discovers and indexes `.semanticdb` files from build output (~10s for large codebases). Subsequent CLI runs load from cache (~1.5s). For agents making many queries, start the **daemon** once (`daemon -w /project &`) — all subsequent queries auto-detect it and take **<10ms** instead of 1.5s. Index is stored at `.scalex/semanticdb.bin`.

## Setup

A bootstrap script at `scripts/sdbex-cli` (next to this SKILL.md) handles everything automatically — downloading the correct JAR from GitHub releases, checking for Java, and caching at `~/.cache/scalex-semanticdb/`. It auto-upgrades when the skill version changes. Requires Java 11+ at runtime.

**Invocation pattern** — use the absolute path to `sdbex-cli` directly in every command. Do NOT use shell variables (`$SDB`) — coding agent shells are non-persistent, so variables are lost between commands.

```bash
# Pattern: bash "<path-to-scripts>/sdbex-cli" <command> [args] -w <workspace>
bash "/absolute/path/to/skills/sdbex/scripts/sdbex-cli" callers handleRequest -w /project
bash "/absolute/path/to/skills/sdbex/scripts/sdbex-cli" refs PaymentService -w /project
```

Replace `/absolute/path/to/skills/sdbex` with the absolute path to the directory containing this SKILL.md. Remember this path and substitute it directly into every command.

## Troubleshooting

- **`permission denied`**: Run `chmod +x /path/to/sdbex-cli` once, then retry.
- **`Java is required`**: Install Java 11+ (e.g. `brew install openjdk` or `sdk install java`).
- **No .semanticdb files found**: The project must be compiled with SemanticDB enabled. See "Prerequisites" below.

## Prerequisites — run this first, every time

Before using sdbex (CLI or daemon), generate SemanticDB data for the **entire** codebase:

```bash
./mill __.semanticDbData
```

This step is non-negotiable. Run it before your first query, and re-run it after any code change you want reflected in results.

### Why this matters

sdbex does not read source code. It reads `.semanticdb` files — binary artifacts the Scala compiler produces alongside `.class` files. These contain the compiler's resolved understanding of every symbol: which exact method `validate` refers to (not just the name, but the specific overload in the specific package), what type was inferred for `val result = ...`, which class implements which trait. This is what makes callers/refs/types precise instead of approximate.

**If you skip this step, sdbex has nothing to read.** No `.semanticdb` files means no index, which means every query returns empty results. The tool will tell you "No .semanticdb files found" and exit.

### Why you need ALL modules, not just some

The `__` in `./mill __.semanticDbData` is Mill's wildcard — it means "every module in the project." This is deliberate. Consider what happens if you only generate SemanticDB for module A but not module B:

- `callers processPayment` — finds callers *within module A* but silently misses every call from module B. You think there are 3 callers when there are actually 12. You refactor based on this, and break 9 call sites you didn't know existed.
- `subtypes Repository` — finds implementations in module A but misses the ones in module B. You think you've found every implementation when you haven't.
- `refs Config` — shows 8 references instead of 40. You conclude a type is barely used when it's actually central to the codebase.
- `path A B` — reports "no path found" between two symbols because the connecting module wasn't indexed. The path exists, but sdbex can't see the bridge.

**Partial data is worse than no data** because it gives you false confidence. With no data, the tool fails loudly and you know to fix it. With partial data, every answer looks plausible but is silently incomplete. You make decisions based on a partial view of the codebase without knowing it's partial.

Think of it like searching a book but only having half the pages. You won't get an error — you'll just get fewer results and wrongly conclude they're all the results.

### After code changes

SemanticDB files are generated at compile time. If you change code and query without regenerating, sdbex uses stale data — it might reference methods that no longer exist or miss new call sites. The daemon auto-detects stale files and rebuilds its index, but only from whatever `.semanticdb` data exists on disk. If the disk data is old, the index is old.

Re-run after significant code changes:
```bash
./mill __.semanticDbData
```

This is incremental — Mill only recompiles changed modules, so it's fast after the first run.

### Mill only (for now)

sdbex currently discovers `.semanticdb` files from Mill's `out/` directory structure only. Other build tools (sbt, Gradle, scala-cli) are not yet supported. If you're interested in support for other tools, [file an issue](https://github.com/nguyenyou/scalex/issues).

## When to use sdbex vs scalex

scalex is the better default — zero setup, fast, good enough for 90% of queries. Reach for sdbex only when scalex fundamentally can't answer your question:

| Question | scalex can't because... | sdbex command |
|---|---|---|
| "Who calls `processPayment`?" | Text search finds the name but can't identify which *method* contains the call | `callers processPayment` |
| "Who transitively calls this?" | No call graph | `callers X --depth 3` |
| "How does A reach B?" | No call graph | `path A B` |
| "All references to this specific `Config`" | `refs Config` matches every `Config` in every package | `refs Config` (resolves to exact FQN) |
| "Every class implementing `Repository`" | Text-based `impl` misses implementations in unexpected packages | `subtypes Repository` |
| "What type does `val result = ...` have?" | Inferred types aren't written in source | `type result` |
| "What service methods does `createOrder` call?" | Text search can't resolve which overload is being called | `callees createOrder --smart` |

For everything else — grep, body extraction, scaladoc, AST patterns, test discovery, overview — use scalex.

## Commands

### Precision queries (the primary reason to use sdbex)

#### `sdbex callers <symbol> [--depth N] [--kind K] [--exclude "p1,p2"]` — who calls this method?

The most valuable command — no scalex equivalent exists. Tells you *which method* contains each call, not just which file. `--kind` narrows symbol resolution (e.g., `--kind method` picks the method over a companion object). `--exclude` filters callers matching FQN or file path patterns.

With `--depth N` (N>1), shows a transitive caller tree — "which HTTP endpoints eventually reach this internal method?" Default depth is 1 (flat list). Supports `--smart` (same-module only) and cycle detection.

```bash
sdbex callers handleRequest -w /project
sdbex callers handleRequest --kind method --exclude "test,integ" -w /project

# Transitive callers — trace back through service layers:
sdbex callers add --depth 3 -w /project
```
```
Caller tree of 'add' (depth=3)
method add (EventStoreOperations.scala:42)
  method executeEvent (FlowOperations.scala:15)
    method createOrder (OrderFlowOperations.scala:36)
```

#### `sdbex refs <symbol> [--role def|ref]` — zero-false-positive references

Every occurrence is compiler-resolved — distinguishes overloads, resolves across renames. Filter by role: `--role def` for definitions, `--role ref` for references only.

```bash
sdbex refs PaymentService -w /project
sdbex refs processPayment --role ref -w /project
```

#### `sdbex subtypes <symbol> [--depth N]` — exhaustive subtype tree

Compiler-verified — catches every implementation, including those in unexpected packages that text-based search might miss.

```bash
sdbex subtypes Shape --depth 2 -w /project
```

#### `sdbex type <symbol>` — resolved type signature

Shows the compiler-resolved signature, including inferred types not written in source.

```bash
sdbex type configLayer -w /project
```
```
configLayer: val method configLayer ULayer[AppConfig]
```

#### `sdbex path <source> <target> [--depth N] [--smart] [--exclude "p1,p2"]` — find call path between two symbols

"How does symbol A reach symbol B?" BFS on the call graph to find the shortest path. Default max depth is 5. Uses compiler-resolved FQNs — zero false positives from name collisions.

```bash
sdbex path OrderServer.createOrder EventStoreOperations.add -w /project
```
```
Call path from 'createOrder' to 'add' (3 hops)
method createOrder (OrderServer.scala:80)
  -> method createOrder (OrderFlowOperations.scala:36)
    -> method executeEvent (FlowOperations.scala:15)
      -> method add (EventStoreOperations.scala:42)
```

#### `sdbex explain <symbol> [--kind K] [--smart]` — one-shot method/type summary

Combines type signature, callers, callees, members, and subtypes into a single output. Saves multiple round-trips when understanding a method or type. For traits and abstract classes, shows direct subtypes (first 3 + total count). Member listing hides compiler-generated case class synthetics by default.

```bash
sdbex explain createOrder --kind method -w /project
```
```
method createOrder
  Type: (request: CreateOrderRequest): Task[CreateOrderResponse]
  Defined: OrderService.scala:123
  Called by: OrderServer, OrderAgentService (6 total)
  Calls: verifyMembership, validatePlan, createTeam (17 total)
```
```bash
sdbex explain Repository --kind trait -w /project
```
```
trait Repository
  Extends: ...
  Subtypes: UserRepository, OrderRepository, ConfigRepository (5 total)
  Members: find, save, delete (6 total)
```

### Forward call graph

#### `sdbex callees <symbol> [--kind K] [--no-accessors] [--smart] [--exclude "p1,p2"]` — what does this method call?

Flat list of all symbols referenced within a method's body. On large codebases, raw output includes val accessors (`.userId`, `.config`), generated code, and functional plumbing — use `--smart` to auto-filter all of this, or `--no-accessors` and `--exclude` for manual control.

`--kind` narrows symbol resolution (e.g., `--kind method` picks the method over a companion object). `--exclude` matches against both FQN and file path.

```bash
sdbex callees main -w /project

# Recommended for large codebases — clean, flat list of meaningful calls:
sdbex callees createOrder --kind method --smart -w /project

# Fine-tune manually:
sdbex callees createOrder --no-accessors --exclude "protobuf,generatedFactories" -w /project
```
```
5 callees of 'main'
  method register (AnimalService.scala)
  method greet (Animal.scala)
  method fetch (Dog.scala)
```

#### `sdbex flow <method> [--depth N] [--kind K] [--smart] [--exclude "p1,p2"]` — recursive call tree

Traces what a method calls, recursively through service layers. Default depth is 3.

On large codebases, **always use `--smart`** — it filters accessors, generated code, and functional plumbing, and only recurses into callees from the same module (cross-module calls appear as leaves). Without `--smart`, depth 3 can produce thousands of lines of infrastructure noise.

If the output is still too verbose, prefer `callees --smart` (flat list) over `flow` — it gives the same signal without the depth explosion.

```bash
# Small codebases — works fine without filters:
sdbex flow main --depth 2 -w /project

# Large codebases — always use --smart:
sdbex flow createOrder --kind method --depth 3 --smart -w /project
```
```
Call flow from 'main' (depth=2)
method main (Main.scala:10)
  method register (AnimalService.scala:11)
    method find (Repository.scala:5)
  method greet (Animal.scala:6)
  method fetch (Dog.scala:5)
```

### Discovery

#### `sdbex related <symbol>` — co-occurring symbols

Discovers symbols that frequently appear alongside the target. Ranked by co-occurrence count. Useful for discovering the "conceptual neighborhood" around a type.

```bash
sdbex related UserService -w /project
```
```
Symbols related to 'UserService' (by co-occurrence in 4 files)
  [12] trait Database (com/example/Database#)
  [8]  class User (com/example/User#)
  [4]  class UserRepository (com/example/UserRepository#)
```

#### `sdbex occurrences <file> [--role def|ref]` — all occurrences in file

Every symbol usage in a file with exact DEFINITION/REFERENCE role and position.

```bash
sdbex occurrences Main.scala -w /project
```

### Navigation (with resolved types)

#### `sdbex lookup <symbol> [--verbose] [--smart] [--source-only]` — find symbol info

Resolves by exact FQN, suffix match, or display name. Results ranked: source files before generated code (e.g., protobuf), classes/traits first, locals last. When multiple symbols share a name, use the FQN to target the exact one. Use `--source-only` or `--smart` to exclude generated code (protobuf, codegen) from results.

```bash
sdbex lookup PaymentService --verbose -w /project
sdbex lookup "com/example/PaymentService#" -w /project
sdbex lookup MyEntity --source-only -w /project
```

#### `sdbex supertypes <symbol>` — resolved parent type chain

```bash
sdbex supertypes AnimalRepository -w /project
```

#### `sdbex members <symbol> [--kind K] [--smart] [--verbose]` — declarations with resolved types

Hides compiler-generated case class synthetics by default. Use `--verbose` to show all, `--smart` to also filter accessors and generated-code noise.

```bash
sdbex members PaymentService -w /project
sdbex members MyEntity --smart -w /project
```

#### `sdbex symbols [file] [--kind K]` — symbols in file

```bash
sdbex symbols Dog.scala -w /project
sdbex symbols --kind trait -w /project
```

### Batch

#### `sdbex batch "cmd1" "cmd2" ...` — multiple queries in one invocation

Amortizes the ~1.5s index load across many queries. Each positional arg is a full sub-command string (command + args + flags). Results are separated by `--- <command> ---` delimiters. Unknown sub-commands produce an error for that entry without affecting others.

```bash
sdbex batch "lookup Dog" "members Animal" "subtypes Shape" -w /project
sdbex batch "callers handleRequest" "callees processPayment --smart" -w /project
```

### Daemon mode (for coding agents)

The daemon keeps the index hot in memory so queries take **<10ms** instead of ~1.5s. It listens on a Unix domain socket so any process can connect — perfect for coding agent environments (like Claude Code) where each shell invocation is independent.

**Start the daemon once at the beginning of a session:**

```bash
bash "/path/to/sdbex-cli" daemon -w /project &
```

The daemon builds the index, binds the socket, and runs in the background. It self-terminates after 5 minutes of inactivity (or 30 minutes max lifetime). No cleanup needed.

**All subsequent queries auto-detect the daemon — no special handling:**

```bash
bash "/path/to/sdbex-cli" callers handleRequest -w /project   # <10ms via socket
bash "/path/to/sdbex-cli" refs Config -w /project              # <10ms via socket
bash "/path/to/sdbex-cli" subtypes Repository -w /project      # <10ms via socket
```

If the daemon isn't running yet (still building index, or not started), queries fall back to local index loading (~1.5s) transparently.

**Safety**: The daemon self-terminates aggressively — idle timeout, max lifetime, heap pressure, shutdown hook. One daemon max per workspace (socket file acts as lock). Socket is owner-only (`rwx------`). No orphan risk.

#### When to use daemon vs CLI vs batch

| Scenario | Best approach | Why |
|---|---|---|
| 1-2 queries | CLI (`sdbex callers ...`) | Simplest, no setup |
| 3-5 related queries | `batch` | Amortizes 1.5s load |
| Many queries across a session | Daemon | <10ms, works across independent shell calls |

### Index management

```bash
sdbex index -w /project              # Force rebuild
sdbex stats -w /project              # Show counts
```

## Options

| Flag | Description |
|---|---|
| `-w`, `--workspace PATH` | Set workspace (default: cwd) |
| `--limit N` | Max results (default: 50, 0=unlimited) |
| `--verbose`, `-v` | Full signatures and properties |
| `--kind K` | Filter by kind AND narrow symbol resolution (class, trait, object, method, field, type) |
| `--role R` | Filter occurrences (def, ref) |
| `--depth N` | Max recursion depth (callers: 1, flow/subtypes: 3, path: 5) |
| `--no-accessors` | Exclude val/var accessors from flow/callees output |
| `--smart` | Auto-filter infrastructure noise: accessors, generated code, protobuf boilerplate, effect-system combinators, case class synthetics. In flow, only recurses into same-module callees. In members, hides synthetics + accessors. In lookup, excludes generated sources. |
| `--source-only` | Exclude generated/compiled sources from lookup results |
| `--exclude "p1,p2,..."` | Exclude symbols matching FQN or file path (flow/callees/callers) |
| `--exclude-test` | Exclude symbols from test source directories (/test/, /tests/, /it/, /spec/, *Test.scala, etc.) |
| `--exclude-pkg "p1,p2,..."` | Exclude symbols by package prefix (dots auto-converted to /). Works with callers, callees, flow, path, explain. E.g. `--exclude-pkg "zio,cats.effect"` |
| `--in <scope>` | Scope symbol resolution by owner class, file path, or package. Avoids full-FQN round-trip. E.g. `--in OrderService` |
| `--timings` | Print timing info to stderr |

## Getting the most out of sdbex

### Avoiding the biggest pitfalls

**Pitfall 1: Ambiguous symbol names.** If a name matches multiple symbols, sdbex picks the first by rank and prints a disambiguation hint to stderr. Add `--kind method` to disambiguate, or use the full FQN from `lookup`:

```bash
# Bad: might match the wrong symbol
sdbex callees createOrder

# Good: explicitly target the method
sdbex callees createOrder --kind method

# Best: use the FQN when you know it (zero ambiguity)
sdbex callees "com/example/OrderService#createOrder()."
```

**Pitfall 2: Using `flow` without `--smart` on large codebases.** Raw `flow` at depth 3 can produce thousands of lines. On large codebases, always use `--smart`. If still too verbose, fall back to `callees --smart` (flat list, no depth explosion).

**Pitfall 3: Paying 1.5s index load per query.** Use `batch` for a handful of queries, or the daemon for many (see "When to use daemon vs CLI vs batch" above).

### Decision tree: which command to use

```
What do you need?
│
├─ "Who calls X?" ──────────────── callers X
├─ "Who transitively calls X?" ── callers X --depth 3
├─ "What does X call?" ─────────── callees X --kind method --smart
├─ "Trace X through layers" ────── flow X --kind method --smart --depth 3
├─ "How does A reach B?" ────────── path A B
├─ "Quick summary of X" ─────────── explain X
├─ "All references to X" ──────── refs X
├─ "Who implements trait X?" ───── subtypes X
├─ "What type is X?" ──────────── type X
├─ "What members does X have?" ── members X
├─ "What's related to X?" ─────── related X
├─ "Verify 5 symbols at once" ── batch "cmd1" "cmd2" "cmd3" ...
└─ "Many queries in a session" ── daemon (keeps index hot, <10ms/query)
```

### Common workflows

```bash
# Impact analysis — who calls this? (exclude tests)
sdbex callers processPayment --exclude "test,integ" -w /project

# Transitive impact — who *eventually* calls this internal method?
sdbex callers add --depth 3 --exclude "test,integ" -w /project

# Understand a method — clean flat list of what it calls
sdbex callees createOrder --kind method --smart -w /project

# Trace through service layers — recursive call tree
sdbex flow createOrder --kind method --depth 3 --smart -w /project

# How does endpoint A reach database method B?
sdbex path OrderServer.createOrder EventStoreOperations.add -w /project

# Quick orientation — callers, callees, type in one shot
sdbex explain processPayment --kind method -w /project

# Verify many claims at once (amortizes index load)
sdbex batch "lookup UserService" "subtypes AuthProvider" "callers handleLogin" -w /project
```
