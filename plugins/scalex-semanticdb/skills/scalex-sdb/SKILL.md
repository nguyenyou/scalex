---
name: scalex-sdb
description: "Compiler-precise Scala code intelligence from SemanticDB data. Call graph analysis (callers, callees, flow), precise references with zero false positives, resolved type signatures, related symbol discovery. Requires compiled .semanticdb files (Scala 2/3). Triggers: \"who calls X\", \"what does X call\", \"trace the call flow from X\", \"what calls this method\", \"precise references of X\", \"what type does X return\", \"show callers of X\", \"call graph of X\", \"what symbols are related to X\", \"what methods does this class have with types\", \"trace execution flow\", \"trace the service layers\", or when you need compiler-precise (not text-based) code intelligence. Use this over scalex when you need call graphs, type information, or zero-false-positive references. Use scalex for zero-setup exploration; use scalex-sdb when the project has been compiled with SemanticDB enabled."
---

You have access to `scalex-sdb`, a compiler-precise Scala code intelligence CLI. It reads `.semanticdb` files produced by the Scala compiler and provides capabilities that source-text parsing fundamentally cannot:

- **Reverse call graph** (`callers`) — "who calls this method?" resolved to the exact enclosing method, not just the file. No scalex equivalent.
- **Zero-false-positive references** (`refs`) — the compiler resolves each reference to its exact fully-qualified symbol. `refs Config` in scalex matches every `Config` across all packages; scalex-sdb resolves each to its exact FQN.
- **Exhaustive subtypes** (`subtypes`) — compiler-verified, catches everything scalex's text-based `impl` might miss.
- **Forward call graph** (`callees`, `flow`) — what does a method call? `callees` gives a flat list; `flow` gives a recursive tree. Use `--smart` on large codebases to filter infrastructure noise.
- **Resolved types** (`type`) — source may have `val x = ???` with no annotation. Only the compiler knows the actual type.
- **Related symbols** (`related`) — precise co-occurrence ranked by frequency, not text matching.

**Use scalex as your primary tool** for exploration — it's zero-setup and fast. Reach for scalex-sdb when you need precision: `callers`, precise `refs`, exhaustive `subtypes`, or resolved types.

First run discovers and indexes `.semanticdb` files from build output (~10s for large codebases). Subsequent runs load from cache (~1.5s). Index is stored at `.scalex/semanticdb.bin`.

## Setup

A bootstrap script at `scripts/scalex-sdb-cli` (next to this SKILL.md) handles everything automatically — downloading the correct JAR from GitHub releases, checking for Java, and caching at `~/.cache/scalex-semanticdb/`. It auto-upgrades when the skill version changes. Requires Java 11+ at runtime.

**Invocation pattern** — use the absolute path to `scalex-sdb-cli` directly in every command. Do NOT use shell variables (`$SDB`) — coding agent shells are non-persistent, so variables are lost between commands.

```bash
# Pattern: bash "<path-to-scripts>/scalex-sdb-cli" <command> [args] -w <workspace>
bash "/absolute/path/to/skills/scalex-sdb/scripts/scalex-sdb-cli" callers handleRequest -w /project
bash "/absolute/path/to/skills/scalex-sdb/scripts/scalex-sdb-cli" refs PaymentService -w /project
```

Replace `/absolute/path/to/skills/scalex-sdb` with the absolute path to the directory containing this SKILL.md. Remember this path and substitute it directly into every command.

## Troubleshooting

- **`permission denied`**: Run `chmod +x /path/to/scalex-sdb-cli` once, then retry.
- **`Java is required`**: Install Java 11+ (e.g. `brew install openjdk` or `sdk install java`).
- **No .semanticdb files found**: The project must be compiled with SemanticDB enabled. See "Prerequisites" below.

## Prerequisites

scalex-sdb requires `.semanticdb` files in build output directories. Generate them:

**Mill** (best supported):
```bash
./mill __.semanticDbData
```

**sbt / scala-cli / other build tools**: Discovery and indexing should work but is not fully tested. Use `--semanticdb-path` to point to the directory containing your `.semanticdb` files if auto-discovery doesn't find them. If you run into issues, please [file an issue](https://github.com/nguyenyou/scalex/issues) — we're happy to add support.

```scala
// sbt: build.sbt
ThisBuild / semanticdbEnabled := true
```

scalex-sdb auto-discovers `.semanticdb` files. For Mill projects, it finds `semanticDbDataDetailed.dest` directories directly (fast targeted search). For other build tools, it falls back to walking `target/` and `.bloop/`. Use `--semanticdb-path` to override.

## When to use scalex-sdb vs scalex

| Need | Use |
|---|---|
| Zero-setup exploration, no compilation | `scalex` |
| Source body extraction, scaladoc, grep | `scalex` |
| AST patterns, test discovery, overview | `scalex` |
| **"Who calls this method?"** | `scalex-sdb callers` |
| **Precise references (zero false positives)** | `scalex-sdb refs` |
| **Exhaustive subtypes** | `scalex-sdb subtypes` |
| **"What does this method call?"** | `scalex-sdb callees --smart` |
| **Resolved type signatures** | `scalex-sdb type` |
| **Related symbol discovery** | `scalex-sdb related` |

## Commands

### Precision queries (the primary reason to use scalex-sdb)

#### `scalex-sdb callers <symbol> [--kind K] [--exclude "p1,p2"]` — who calls this method?

The most valuable command — no scalex equivalent exists. Tells you *which method* contains each call, not just which file. `--kind` narrows symbol resolution (e.g., `--kind method` picks the method over a companion object). `--exclude` filters callers matching FQN or file path patterns.

```bash
scalex-sdb callers handleRequest -w /project
scalex-sdb callers handleRequest --kind method --exclude "test,integ" -w /project
```
```
9 callers of 'handleRequest'
  method route (Router.scala)
  method main (Main.scala)
  method testHandler (HandlerSpec.scala)
```

#### `scalex-sdb refs <symbol> [--role def|ref]` — zero-false-positive references

Every occurrence is compiler-resolved — distinguishes overloads, resolves across renames. Filter by role: `--role def` for definitions, `--role ref` for references only.

```bash
scalex-sdb refs PaymentService -w /project
scalex-sdb refs processPayment --role ref -w /project
```

#### `scalex-sdb subtypes <symbol> [--depth N]` — exhaustive subtype tree

Compiler-verified — catches every implementation, including those in unexpected packages that text-based search might miss.

```bash
scalex-sdb subtypes Shape --depth 2 -w /project
```

#### `scalex-sdb type <symbol>` — resolved type signature

Shows the compiler-resolved signature, including inferred types not written in source.

```bash
scalex-sdb type configLayer -w /project
```
```
configLayer: val method configLayer ULayer[AppConfig]
```

### Forward call graph

#### `scalex-sdb callees <symbol> [--kind K] [--no-accessors] [--smart] [--exclude "p1,p2"]` — what does this method call?

Flat list of all symbols referenced within a method's body. On large codebases, raw output includes val accessors (`.userId`, `.config`), generated code, and functional plumbing — use `--smart` to auto-filter all of this, or `--no-accessors` and `--exclude` for manual control.

`--kind` narrows symbol resolution (e.g., `--kind method` picks the method over a companion object). `--exclude` matches against both FQN and file path.

```bash
scalex-sdb callees main -w /project

# Recommended for large codebases — clean, flat list of meaningful calls:
scalex-sdb callees createOrder --kind method --smart -w /project

# Fine-tune manually:
scalex-sdb callees createOrder --no-accessors --exclude "protobuf,radixFactories" -w /project
```
```
5 callees of 'main'
  method register (AnimalService.scala)
  method greet (Animal.scala)
  method fetch (Dog.scala)
```

#### `scalex-sdb flow <method> [--depth N] [--kind K] [--smart] [--exclude "p1,p2"]` — recursive call tree

Traces what a method calls, recursively through service layers. Default depth is 3.

On large codebases, **always use `--smart`** — it filters accessors, generated code, and functional plumbing, and only recurses into callees from the same module (cross-module calls appear as leaves). Without `--smart`, depth 3 can produce thousands of lines of infrastructure noise.

If the output is still too verbose, prefer `callees --smart` (flat list) over `flow` — it gives the same signal without the depth explosion.

```bash
# Small codebases — works fine without filters:
scalex-sdb flow main --depth 2 -w /project

# Large codebases — always use --smart:
scalex-sdb flow createOrder --kind method --depth 3 --smart -w /project
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

#### `scalex-sdb related <symbol>` — co-occurring symbols

Discovers symbols that frequently appear alongside the target. Ranked by co-occurrence count. Useful for discovering the "conceptual neighborhood" around a type.

```bash
scalex-sdb related UserService -w /project
```
```
Symbols related to 'UserService' (by co-occurrence in 4 files)
  [12] trait Database (com/example/Database#)
  [8]  class User (com/example/User#)
  [4]  class UserRepository (com/example/UserRepository#)
```

#### `scalex-sdb occurrences <file> [--role def|ref]` — all occurrences in file

Every symbol usage in a file with exact DEFINITION/REFERENCE role and position.

```bash
scalex-sdb occurrences Main.scala -w /project
```

### Navigation (with resolved types)

#### `scalex-sdb lookup <symbol> [--verbose]` — find symbol info

Resolves by exact FQN, suffix match, or display name. Results ranked: source files before generated code (e.g., protobuf), classes/traits first, locals last. When multiple symbols share a name, use the FQN to target the exact one.

```bash
scalex-sdb lookup PaymentService --verbose -w /project
scalex-sdb lookup "com/example/PaymentService#" -w /project
```

#### `scalex-sdb supertypes <symbol>` — resolved parent type chain

```bash
scalex-sdb supertypes AnimalRepository -w /project
```

#### `scalex-sdb members <symbol> [--kind K]` — declarations with resolved types

```bash
scalex-sdb members PaymentService -w /project
```

#### `scalex-sdb symbols [file] [--kind K]` — symbols in file

```bash
scalex-sdb symbols Dog.scala -w /project
scalex-sdb symbols --kind trait -w /project
```

### Batch

#### `scalex-sdb batch "cmd1" "cmd2" ...` — multiple queries in one invocation

Amortizes the ~1.5s index load across many queries. Each positional arg is a full sub-command string (command + args + flags). Results are separated by `--- <command> ---` delimiters in text mode, or wrapped in `{"batch":[...]}` in JSON mode.

```bash
scalex-sdb batch "lookup Dog" "members Animal" "subtypes Shape" -w /project
scalex-sdb batch "callers handleRequest" "callees processPayment --smart" -w /project
```

### Index management

```bash
scalex-sdb index -w /project              # Force rebuild
scalex-sdb stats -w /project              # Show counts
```

## Options

| Flag | Description |
|---|---|
| `-w`, `--workspace PATH` | Set workspace (default: cwd) |
| `--semanticdb-path PATH` | Explicit path to `.semanticdb` files |
| `--limit N` | Max results (default: 50, 0=unlimited) |
| `--json` | JSON output |
| `--verbose`, `-v` | Full signatures and properties |
| `--kind K` | Filter by kind AND narrow symbol resolution (class, trait, object, method, field, type) |
| `--role R` | Filter occurrences (def, ref) |
| `--depth N` | Max depth for flow/subtypes (default: 3) |
| `--no-accessors` | Exclude val/var accessors from flow/callees output |
| `--smart` | Auto-filter infrastructure noise: accessors, generated code, protobuf boilerplate, functional plumbing. In flow, only recurses into same-module callees. |
| `--exclude "p1,p2,..."` | Exclude symbols matching FQN or file path (flow/callees/callers) |
| `--timings` | Print timing info to stderr |

## Common workflows

**"Who calls this method?"** — the #1 reason to use scalex-sdb:
```bash
scalex-sdb callers processPayment -w /project
```

**"What does this method do?"** — flat list of calls, clean output:
```bash
scalex-sdb callees createOrder --kind method --smart -w /project
```

**"Precise references (no false positives)"**:
```bash
scalex-sdb refs MyService -w /project
```

**"What type does this return?"** — especially useful for inferred types:
```bash
scalex-sdb type computeResult -w /project
```

**"Who implements this trait?"** — exhaustive, compiler-verified:
```bash
scalex-sdb subtypes Repository -w /project
```

**"Verify multiple symbols in one shot"** — batch amortizes index load:
```bash
scalex-sdb batch "callers handleRequest" "subtypes Repository" "members Config" -w /project
```

## Why scalex-sdb over scalex

scalex is the better default — zero setup, fast, good enough for 90% of queries. Use scalex-sdb for the things scalex fundamentally cannot do:

| Capability | Why scalex can't |
|---|---|
| `callers` — reverse call graph | Text search finds the method name but can't identify the enclosing caller |
| `refs` — precise references | `refs Config` in scalex matches every `Config` in every package |
| `subtypes` — exhaustive | Text-based `impl` can miss implementations in unexpected packages |
| `type` — inferred types | Only the compiler knows the return type of `val x = computeStuff()` |
| `callees`/`flow` — forward call graph | Text search can't resolve which overload is being called |
