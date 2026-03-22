# scalex-semanticdb

Compiler-precise code intelligence from SemanticDB data. Companion tool to [scalex](https://github.com/nguyenyou/scalex).

## What this tool does that scalex can't

scalex parses source text — fast, zero-setup, but blind to types. scalex-semanticdb reads the compiler's output and provides capabilities that source parsing fundamentally cannot:

| Capability | Why it needs the compiler |
|---|---|
| **Call graph** (`flow`, `callers`, `callees`) | Text search can't resolve which `process()` is being called across overloads and packages |
| **Zero-false-positive references** (`refs`) | Compiler resolves each reference to its exact fully-qualified symbol |
| **Resolved types** (`type`) | Source may have `def foo = ???` — only the compiler knows the return type |
| **Related symbols** (`related`) | Precise co-occurrence depends on compiler-resolved occurrences, not text matching |
| **Occurrence map** (`occurrences`) | Every symbol usage in a file with exact DEFINITION/REFERENCE role |

For everything else — grep, body extraction, scaladoc, AST patterns, test discovery, overview — use scalex.

## Getting .semanticdb files

**Mill** (easiest):
```bash
./mill __.semanticDbData
```

**sbt**:
```scala
// build.sbt
ThisBuild / semanticdbEnabled := true
```

## Usage

```bash
scalex-sdb <command> [args] [options]

# Call flow tree — the killer feature
scalex-sdb flow processPayment --depth 3

# Who calls this method?
scalex-sdb callers handleRequest

# What does this method call?
scalex-sdb callees main

# Zero-false-positive references
scalex-sdb refs MyService

# Resolved type signature
scalex-sdb type myFunction

# Co-occurring symbols
scalex-sdb related UserService
```

## Commands

**Call graph** (compiler-only — no scalex equivalent):

| Command | Description |
|---|---|
| `flow <method>` | Downstream call tree with `--depth N` (default 3) |
| `callers <symbol>` | Reverse call graph — who calls this |
| `callees <symbol>` | Forward call graph — what does this call |

**Compiler-precise queries:**

| Command | Description |
|---|---|
| `refs <symbol>` | Zero-false-positive references, `--role def\|ref` |
| `type <symbol>` | Resolved type signature |
| `related <symbol>` | Co-occurring symbols ranked by frequency |
| `occurrences <file>` | All symbol occurrences in file with roles |

**Navigation** (also in scalex, but with resolved types here):

| Command | Description |
|---|---|
| `lookup <symbol>` | Find symbol by FQN or display name |
| `supertypes <symbol>` | Resolved parent type chain |
| `subtypes <symbol>` | Who extends this |
| `members <symbol>` | Declarations with resolved types |
| `symbols [file]` | Symbols defined in file |

**Index:**

| Command | Description |
|---|---|
| `index` | Force rebuild |
| `stats` | Index statistics |

### Options

```
-w, --workspace PATH         Set workspace (default: cwd)
--semanticdb-path PATH       Explicit path to .semanticdb files
--limit N                    Max results (default: 50, 0=unlimited)
--json                       JSON output
--verbose, -v                Full signatures and properties
--kind K                     Filter by kind
--role R                     Filter occurrences (def/ref)
--depth N                    Max depth for flow/subtypes (default: 3)
--timings                    Print timing info to stderr
```

## Why this matters: tool call comparison

Tested on a production Scala monorepo (15k files, 2M symbols). Each scenario shows how many round-trip tool calls a coding agent needs:

| Scenario | grep | scalex | scalex-sdb |
|---|---|---|---|
| **"What happens when `createRouter` is called?"** (depth 2, 25 callees) | Impossible | Impossible | **1 call** (`flow`) |
| **"Full call chain from `start()` depth 3"** (40+ nodes across 4 services) | ~30+ calls | ~15+ calls | **1 call** (`flow`) |
| **"What does `TokenService.start()` call?"** (7 callees) | ~10 calls (find file, read, grep each) | ~6 calls (def, read, def each) | **1 call** (`callees`) |
| **"Who calls `authCheck`?"** (9 callers) | 1 call — raw lines, no caller names | 1 call — text matches, no caller context | **1 call** — 9 exact caller methods |
| **"What type does `configLayer` return?"** (inferred `ULayer[AppConfig]`) | Cannot answer | Shows `val configLayer` (no type) | **1 call** — `ULayer[AppConfig]` |
| **"What symbols are related to `TokenService`?"** | Cannot answer | Cannot answer | **1 call** (`related`) — 1,048 co-occurring symbols ranked |
| **"Precise refs of `authCheck`"** (no false positives) | 10 lines (includes def) | 10 text matches | **9 exact references** |

The `flow` command is the clearest win: what takes a coding agent **15–30 round trips** with grep/scalex takes **1 call** with scalex-sdb.

For a 3-level deep trace like `start() → checkToken → getTokenClaim → loadContext`, the agent would need to `def` → `Read` → `def` → `Read` → `def` → `Read` at minimum. scalex-sdb returns the entire tree in a single invocation.

## Performance

| Project | Files | Symbols | Occurrences | Cold Index | Warm Index |
|---|---|---|---|---|---|
| Production monorepo | 15,268 | 2,057,320 | 10,435,517 | 10s | 1.5s |

## Build

```bash
# Run via scala-cli (development)
scala-cli run scalex-semanticdb/src/ -- <command> [args...]

# Run tests
scala-cli test scalex-semanticdb/src/ scalex-semanticdb/tests/

# Build assembly JAR (requires scala-cli, produces self-executable JAR)
./build-native-semanticdb.sh
# Output: ./scalex-sdb (~49MB, requires Java 11+ at runtime)
```

The assembly JAR is shipped instead of a GraalVM native image because the JVM's JIT compiler is ~11x faster on warm loads for this protobuf-heavy workload (1.8s vs 20s for 3M symbols).
