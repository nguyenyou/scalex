---
name: scalex-sdb
description: "Compiler-precise Scala code intelligence from SemanticDB data. Call graph analysis (callers, callees, flow), precise references with zero false positives, resolved type signatures, subtype/supertype hierarchy, related symbol discovery. Requires compiled .semanticdb files (Scala 2/3). Triggers: \"who calls X\", \"what does X call\", \"trace the call flow from X\", \"precise references of X\", \"what type does X return\", \"show callers of X\", \"call graph of X\", \"what symbols are related to X\", \"compiler diagnostics\", \"what methods does this class have with types\", or when you need compiler-precise (not text-based) code intelligence. Use this over scalex when you need call graphs, type information, or zero-false-positive references. Use scalex for zero-setup exploration; use scalex-sdb when the project has been compiled with SemanticDB enabled."
---

You have access to `scalex-sdb`, a compiler-precise Scala code intelligence CLI that reads SemanticDB data (`.semanticdb` files produced by the Scala compiler). Unlike `scalex` which parses source text, `scalex-sdb` uses the compiler's own type-resolved output — every reference is exact, every type is resolved, and call graphs are precise.

**Key advantage over scalex:** call graph analysis (callers/callees/flow), zero-false-positive references, resolved type signatures, and implicit/given tracking. The tradeoff is that the project must have been compiled with SemanticDB enabled.

First run discovers and indexes all `.semanticdb` files from build output directories (~50s for large codebases). Subsequent runs load from cache (~2s). Index is stored at `.scalex/semanticdb.bin`.

## Setup

A bootstrap script at `scripts/scalex-sdb-cli` (next to this SKILL.md) handles everything automatically — platform detection, downloading the correct native binary from GitHub releases, and caching at `~/.cache/scalex-semanticdb/`. It auto-upgrades when the skill version changes.

**Invocation pattern** — use the absolute path to `scalex-sdb-cli` directly in every command. Do NOT use shell variables (`$SDB`) — coding agent shells are non-persistent, so variables are lost between commands.

```bash
# Pattern: bash "<path-to-scripts>/scalex-sdb-cli" <command> [args] -w <workspace>
bash "/absolute/path/to/skills/scalex-sdb/scripts/scalex-sdb-cli" flow processPayment --depth 3 -w /project
bash "/absolute/path/to/skills/scalex-sdb/scripts/scalex-sdb-cli" callers handleRequest -w /project
```

Replace `/absolute/path/to/skills/scalex-sdb` with the absolute path to the directory containing this SKILL.md. Remember this path and substitute it directly into every command.

## Troubleshooting

- **`permission denied`**: Run `chmod +x /path/to/scalex-sdb-cli` once, then retry.
- **macOS quarantine**: `xattr -d com.apple.quarantine ~/.cache/scalex-semanticdb/*`
- **No .semanticdb files found**: The project must be compiled with SemanticDB enabled. See "Prerequisites" below.

## Prerequisites

scalex-sdb requires `.semanticdb` files in build output directories. Generate them:

**Mill** (easiest):
```bash
./mill __.semanticDbData
```

**sbt**:
```scala
// build.sbt
ThisBuild / semanticdbEnabled := true
```
Then compile: `sbt compile`

**Manual scalac** (Scala 3):
```bash
scalac -Xsemanticdb MyFile.scala
```

scalex-sdb auto-discovers `.semanticdb` files in `out/`, `target/`, and `.bloop/` directories. Use `--semanticdb-path` to point to a specific location.

## When to use scalex-sdb vs scalex

| Need | Use |
|---|---|
| Zero-setup exploration, no compilation | `scalex` |
| Source body extraction, scaladoc | `scalex` |
| Grep, AST patterns, test discovery | `scalex` |
| **Precise references (zero false positives)** | `scalex-sdb` |
| **Call graph (callers/callees/flow)** | `scalex-sdb` |
| **Resolved type signatures** | `scalex-sdb` |
| **Related symbol discovery** | `scalex-sdb` |
| **Compiler diagnostics** | `scalex-sdb` |

## Commands

All commands default to current directory. Set workspace with `-w` / `--workspace`. Every command auto-indexes on first run (discovers and parses `.semanticdb` files).

### `scalex-sdb flow <method> [--depth N]` — downstream call tree

The killer feature. Traces what a method calls, recursively. Shows the full call chain from an entry point through service layers. Filters out stdlib calls. Default depth is 3.

```bash
scalex-sdb flow processPayment --depth 3 -w /project
scalex-sdb flow main --depth 2 -w /project
```
```
Call flow from 'main' (depth=2)
method main (Main.scala:10)
  method register (AnimalService.scala:11)
    method find (Repository.scala:5)
  method greet (Animal.scala:6)
    method name (Animal.scala:4)
    method sound (Animal.scala:5)
  method fetch (Dog.scala:5)
```

### `scalex-sdb callers <symbol> [--kind K]` — reverse call graph

Finds all methods that call the target symbol. Compiler-precise — no false positives.

```bash
scalex-sdb callers handleRequest -w /project
scalex-sdb callers processPayment --kind method -w /project
```
```
3 callers of 'handleRequest'
  method route (Router.scala)
  method main (Main.scala)
  method testHandler (HandlerSpec.scala)
```

### `scalex-sdb callees <symbol> [--kind K]` — forward call graph

Finds all symbols referenced within a method's body. Shows what a method depends on.

```bash
scalex-sdb callees main -w /project
```
```
5 callees of 'main'
  method register (AnimalService.scala)
  method greet (Animal.scala)
  method fetch (Dog.scala)
  method listAll (AnimalService.scala)
```

### `scalex-sdb refs <symbol> [--role def|ref]` — compiler-precise references

Every occurrence is compiler-resolved — zero false positives, distinguishes overloads. Filter by role: `--role def` for definitions only, `--role ref` for references only.

```bash
scalex-sdb refs PaymentService -w /project
scalex-sdb refs processPayment --role ref -w /project
```
```
6 occurrences of 'processPayment'
  [10:6..10:20] <= com/example/PaymentService#processPayment().
    PaymentService.scala:11
  [25:4..25:18] => com/example/PaymentService#processPayment().
    Checkout.scala:26
```

### `scalex-sdb lookup <symbol> [--verbose]` — find symbol info

Resolves by exact FQN, suffix match, or display name. Shows kind, signature, parents, overridden symbols.

```bash
scalex-sdb lookup PaymentService --verbose -w /project
scalex-sdb lookup "com/example/PaymentService#" -w /project
```
```
trait PaymentService [abstract]
  fqn: com/example/PaymentService#
  file: PaymentService.scala
  signature: trait PaymentService extends Object { +3 decls }
  parents: java/lang/Object#
  owner: com/example/
```

### `scalex-sdb supertypes <symbol>` — parent type chain

Walks the resolved parent chain from ClassSignature. Shows external (stdlib) parents too.

```bash
scalex-sdb supertypes AnimalRepository -w /project
```
```
Supertypes of 'AnimalRepository'
class AnimalRepository
  trait CrudRepository (com/example/CrudRepository#)
    trait Repository (com/example/Repository#)
  [external] java/lang/Object#
```

### `scalex-sdb subtypes <symbol> [--depth N]` — who extends this

Finds all types that extend the target. Recursive with depth control.

```bash
scalex-sdb subtypes Animal -w /project
scalex-sdb subtypes Shape --depth 1 -w /project
```
```
Subtypes of 'Shape'
trait Shape
  class Circle (Shape.scala)
  class Rectangle (Shape.scala)
  class Triangle (Shape.scala)
```

### `scalex-sdb members <symbol> [--kind K]` — declarations

Lists members from the compiler's ClassSignature. Excludes parameters and type params. Full resolved signatures.

```bash
scalex-sdb members PaymentService -w /project
scalex-sdb members Dog --kind method -w /project
```

### `scalex-sdb type <symbol>` — resolved type signature

Shows the compiler-resolved signature of any symbol.

```bash
scalex-sdb type processPayment -w /project
```
```
processPayment: method processPayment(amount: BigDecimal): Boolean
```

### `scalex-sdb related <symbol>` — co-occurring symbols

Discovers symbols that frequently appear alongside the target in the same files. Ranked by co-occurrence count. Filters stdlib noise.

```bash
scalex-sdb related UserService -w /project
```
```
Symbols related to 'UserService' (by co-occurrence in 4 files)
  [12] trait Database (com/example/Database#)
  [8]  class User (com/example/User#)
  [6]  method findUser (com/example/UserService#findUser().)
  [4]  class UserRepository (com/example/UserRepository#)
```

### `scalex-sdb diagnostics [file]` — compiler warnings/errors

Shows compiler diagnostics. Optionally filter by file.

```bash
scalex-sdb diagnostics -w /project
scalex-sdb diagnostics MyFile.scala -w /project
```

### `scalex-sdb symbols [file] [--kind K]` — list symbols

Lists all indexed symbols, optionally filtered by file or kind.

```bash
scalex-sdb symbols Dog.scala -w /project
scalex-sdb symbols --kind trait -w /project
```

### `scalex-sdb occurrences <file> [--role def|ref]` — all occurrences in file

Shows every symbol occurrence in a file with positions and roles.

```bash
scalex-sdb occurrences Main.scala -w /project
scalex-sdb occurrences Main.scala --role def -w /project
```

### `scalex-sdb index` — force rebuild

Force re-discovers and re-indexes all `.semanticdb` files (ignores cache).

```bash
scalex-sdb index -w /project
scalex-sdb index --semanticdb-path /path/to/out -w /project
```

### `scalex-sdb stats` — index statistics

Shows file count, symbol count, occurrence count, and build time.

```bash
scalex-sdb stats -w /project
```
```
Files:        16718
Symbols:      3003764
Occurrences:  14916074
Diagnostics:  2
Build time:   1847ms (cached)
```

## Options

| Flag | Description |
|---|---|
| `-w`, `--workspace PATH` | Set workspace (default: cwd) |
| `--semanticdb-path PATH` | Explicit path to `.semanticdb` files |
| `--limit N` | Max results (default: 50, 0=unlimited) |
| `--json` | JSON output |
| `--verbose`, `-v` | Full signatures and properties |
| `--kind K` | Filter by kind (class, trait, object, method, field, type) |
| `--role R` | Filter occurrences (def, ref) |
| `--depth N` | Max depth for flow/subtypes (default: 3) |
| `--timings` | Print timing info to stderr |

## Common workflows

**"What happens when X is called?"** — Use `flow` to trace the full call chain:
```bash
scalex-sdb flow handleRequest --depth 3 -w /project
```

**"Who calls this method?"** — Use `callers` for the reverse call graph:
```bash
scalex-sdb callers processPayment -w /project
```

**"Find all precise references (no false positives)"** — Use `refs`:
```bash
scalex-sdb refs MyService -w /project
```

**"What type does this return?"** — Use `type`:
```bash
scalex-sdb type computeResult -w /project
```

**"What's related to this symbol?"** — Use `related` to discover the conceptual neighborhood:
```bash
scalex-sdb related BillingService -w /project
```

**"What are all the subtypes of this sealed trait?"** — Use `subtypes`:
```bash
scalex-sdb subtypes PaymentStatus -w /project
```

## Why scalex-sdb over scalex refs

scalex's `refs` command uses text matching with bloom filters — fast but produces false positives (e.g., `refs Config` matches every `Config` across all packages). scalex-sdb's `refs` uses the compiler's symbol resolution — each reference is resolved to its exact fully-qualified name. Zero false positives, distinguishes overloads, tracks across renames.

For call graph analysis (`callers`, `callees`, `flow`), there is no scalex equivalent — these features are only possible with compiler-resolved data.
