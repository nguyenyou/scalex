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

## How It Works

```
                    ┌────────────┐
                    │sdbx  │
                    │    CLI     │
                    └─────┬──────┘
                          │
                          v
                    ┌────────────┐
                    │  SemIndex  │
                    └──┬────┬──┬─┘
                       │    │  │
          ┌────────────┘    │  └───────────────┐
          │                 │                  │
          v                 v                  v
  ┌──────────────┐  ┌────────────────┐  ┌────────────────┐
  │.semanticdb   │  │.scalex/        │  │  Discovery      │
  │protobuf files│  │semanticdb.bin  │  │(Mill out/ scan) │
  └──────────────┘  └────────────────┘  └────────────────┘
```

- **sdbx CLI** — 15 commands focused on compiler-unique capabilities
- **SemIndex** — lazy indexes: symbolByFqn, occurrencesBySymbol, subtypeIndex, memberIndex, definitionRanges
- **Discovery** — targeted scan of Mill's `out/` for `semanticDbDataDetailed.dest` directories (fast, Mill-only for now)
- **.semanticdb protobuf** — compiler output: symbols with resolved types, every occurrence with DEFINITION/REFERENCE role
- **.scalex/semanticdb.bin** — binary cache with string interning

### Pipeline

```
  1. Discover .semanticdb files (Mill only)
     │  Find semanticDbDataDetailed.dest dirs under out/, walk only data/META-INF/semanticdb/
     │  ~2s for 15k files (parallel across modules).
     │
  2. Parse protobuf (parallel)
     │  TextDocuments.parseFrom() via Java parallelStream().
     │  Converts SymbolInformation → SemSymbol, SymbolOccurrence → SemOccurrence.
     │  ~4s for 15k files on all CPU cores.
     │
  3. Deduplicate
     │  Mill copies shared sources to jsSharedSources.dest/.
     │  Remove duplicates by source identity (package path + filename).
     │  ~35ms.
     │
  4. Save to .scalex/semanticdb.bin
     │  Binary format with string interning (deduplicates FQNs, file paths).
     │  ~3.5s write, ~1.5s load on subsequent runs.
     │
  5. Answer the query
     │  Index maps build lazily — each query only pays for the indexes it needs.
```

### What's in the index

Each `.semanticdb` file (one per source file) contains the compiler's full picture:

**Symbols** — every definition with its resolved signature:
```
example/Dog#         → class Dog extends Animal { +5 decls }
example/Dog#fetch(). → method fetch(item: String): String
```

**Occurrences** — every reference with its exact target:
```
Dog.scala:3:36  example/Animal#     REFERENCE   (extends Animal)
Main.scala:8:4  example/Dog#fetch() REFERENCE   (dog.fetch("ball"))
```

From these two data sources, the index builds:

| Index | What it maps | Enables |
|---|---|---|
| `symbolByFqn` | FQN → symbol info | `lookup`, `type` |
| `symbolsByName` | display name → symbols | fuzzy `lookup` |
| `occurrencesBySymbol` | FQN → all occurrences | `refs`, `callers`, `related` |
| `occurrencesByFile` | file → all occurrences | `occurrences`, `callees` |
| `subtypeIndex` | parent FQN → child FQNs | `subtypes` |
| `memberIndex` | owner FQN → member symbols | `members` |
| `definitionRanges` | FQN → (file, line) | `callers`, `callees`, `flow` |
| `symbolsByFile` | file → symbols | `symbols` |

### How `flow` works

The killer feature traces call chains by combining `callees` recursively:

1. Resolve the target method → get its definition range from `definitionRanges`
2. Find the next sibling definition (same owner) → that's the body boundary
3. Collect all REFERENCE occurrences between def line and body end
4. Each referenced symbol is a callee → resolve to SemSymbol
5. Filter out stdlib (`scala/`, `java/`) → repeat from step 1 for each callee up to `--depth`

This is why it needs compiler data: step 3 requires knowing which exact symbol each reference points to. Text search would match the wrong `process()` across overloads.

## Getting .semanticdb files

Currently optimized for **Mill projects only**. Other build tools (sbt, scala-cli) will be supported once the Mill workflow is rock-solid.

**Mill**:
```bash
./mill __.semanticDbData
```

This produces `.semanticdb` files under `out/` in each module's `semanticDbDataDetailed.dest/data/META-INF/semanticdb/` directory. Auto-discovery finds them — no configuration needed.

**Other build tools**: Not yet supported. See [issue tracker](https://github.com/nguyenyou/scalex/issues) for sbt/Gradle/scala-cli requests.

## Usage

```bash
sdbx <command> [args] [options]

# Call flow tree — the killer feature
sdbx flow processPayment --depth 3

# Who calls this method?
sdbx callers handleRequest

# What does this method call?
sdbx callees main

# Zero-false-positive references
sdbx refs MyService

# Resolved type signature
sdbx type myFunction

# Co-occurring symbols
sdbx related UserService
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
| `daemon [idle] [max]` | Stdin/stdout JSON-lines server (keeps index hot, <10ms/query) |

Daemon-only options: `--socket` (listen on Unix domain socket instead of stdin — works across independent shell invocations, requires Java 16+).

### Options

```
-w, --workspace PATH         Set workspace (default: cwd, must be Mill project root)
--limit N                    Max results (default: 50, 0=unlimited)
--json                       JSON output
--verbose, -v                Full signatures and properties
--kind K                     Filter by kind and narrow symbol resolution
--role R                     Filter occurrences (def/ref)
--depth N                    Max depth for flow/subtypes (default: 3)
--no-accessors               Exclude val/var accessors from flow/callees
--smart                      Auto-filter infrastructure noise (accessors, generated, plumbing)
--exclude "p1,p2,..."        Exclude symbols matching FQN or file path
--timings                    Print timing info to stderr
```

## Why this matters: tool call comparison

Tested on a production Scala monorepo (15k files, 2M symbols). Each scenario shows how many round-trip tool calls a coding agent needs:

| Scenario | grep | scalex | sdbx |
|---|---|---|---|
| **"What happens when `createRouter` is called?"** (depth 2, 25 callees) | Impossible | Impossible | **1 call** (`flow`) |
| **"Full call chain from `start()` depth 3"** (40+ nodes across 4 services) | ~30+ calls | ~15+ calls | **1 call** (`flow`) |
| **"What does `TokenService.start()` call?"** (7 callees) | ~10 calls (find file, read, grep each) | ~6 calls (def, read, def each) | **1 call** (`callees`) |
| **"Who calls `authCheck`?"** (9 callers) | 1 call — raw lines, no caller names | 1 call — text matches, no caller context | **1 call** — 9 exact caller methods |
| **"What type does `configLayer` return?"** (inferred `ULayer[AppConfig]`) | Cannot answer | Shows `val configLayer` (no type) | **1 call** — `ULayer[AppConfig]` |
| **"What symbols are related to `TokenService`?"** | Cannot answer | Cannot answer | **1 call** (`related`) — 1,048 co-occurring symbols ranked |
| **"Precise refs of `authCheck`"** (no false positives) | 10 lines (includes def) | 10 text matches | **9 exact references** |

The `flow` command is the clearest win: what takes a coding agent **15–30 round trips** with grep/scalex takes **1 call** with sdbx.

For a 3-level deep trace like `start() → checkToken → getTokenClaim → loadContext`, the agent would need to `def` → `Read` → `def` → `Read` → `def` → `Read` at minimum. sdbx returns the entire tree in a single invocation.

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
# Output: ./sdbx (~49MB, requires Java 11+ at runtime)
```

The assembly JAR is shipped instead of a GraalVM native image because the JVM's JIT compiler is ~11x faster on warm loads for this protobuf-heavy workload (1.8s vs 20s for 3M symbols).
