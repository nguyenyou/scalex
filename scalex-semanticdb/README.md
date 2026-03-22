# scalex-semanticdb

Compiler-precise code intelligence from SemanticDB data. Companion tool to [scalex](https://github.com/nguyenyou/scalex).

## How it differs from scalex

**scalex** parses source files directly (via Scalameta AST) — no compilation needed. Fast, zero-setup, but limited to structural information.

**scalex-semanticdb** reads `.semanticdb` files produced by the Scala compiler — requires compilation, but gives type-resolved, compiler-precise results.

| Aspect | scalex | scalex-semanticdb |
|---|---|---|
| **Setup** | None — works on any git repo | Requires compiled `.semanticdb` files |
| **Data source** | Source text → AST | Compiler output → protobuf |
| **References** | Text search + bloom filters (fuzzy) | Exact compiler-resolved occurrences (zero false positives) |
| **Types** | Not available | Full resolved types, return types, inferred types |
| **Overload disambiguation** | Cannot distinguish | Each overload has a unique symbol |
| **Implicit/given resolution** | Invisible | Tracked in synthetics |
| **Call graph** | Not possible | Precise callers/callees/flow from occurrence data |
| **Speed (cold)** | ~3s (source parsing) | ~50s (protobuf parsing, large codebase) |
| **Speed (warm)** | ~350ms | ~2s |
| **Java support** | Yes (JavaParser) | Only if compiled with semanticdb-javac |
| **Scala version** | Scala 2 + 3 source | Scala 2 + 3 compiled output |

## Command comparison

| Capability | scalex | scalex-semanticdb | Notes |
|---|---|---|---|
| Find definition | `def` | `lookup` | sdb uses FQN, more precise |
| Find references | `refs` | `refs` | sdb is compiler-precise, no false positives |
| Find implementations | `impl` | `subtypes` | sdb uses type hierarchy from compiler |
| List members | `members` | `members` | sdb shows full type signatures |
| Type hierarchy | `hierarchy` | `supertypes` / `subtypes` | sdb has resolved parent chain |
| Search by name | `search` | `lookup` | scalex has fuzzy/camelCase search |
| Show type info | — | `type` | **sdb only** |
| Callers (reverse call graph) | — | `callers` | **sdb only** |
| Callees (forward call graph) | — | `callees` | **sdb only** |
| Call flow tree | — | `flow` | **sdb only** — recursive callees with depth |
| Related symbols | — | `related` | **sdb only** — co-occurrence analysis |
| Compiler diagnostics | — | `diagnostics` | **sdb only** |
| File occurrences | — | `occurrences` | **sdb only** — all symbols in a file with roles |
| Extract source body | `body` | — | **scalex only** |
| Scaladoc | `doc` | — | **scalex only** |
| Package overview | `overview` | — | **scalex only** |
| API surface | `api` | — | **scalex only** |
| Grep (regex search) | `grep` | — | **scalex only** |
| AST pattern search | `ast-pattern` | — | **scalex only** |
| Git diff analysis | `diff` | — | **scalex only** |
| Test discovery | `tests` | — | **scalex only** |
| Test coverage | `coverage` | — | **scalex only** |
| Entrypoints | `entrypoints` | — | **scalex only** |
| Import tracking | `imports` | — | **scalex only** |
| Annotations | `annotated` | — | **scalex only** |
| Dependencies | `deps` | — | **scalex only** |
| Scope context | `context` | — | **scalex only** |
| Explain symbol | `explain` | — | **scalex only** |
| Overrides | `overrides` | — | **scalex only** |
| ASCII graph rendering | `graph` | — | **scalex only** |

## When to use which

**Use scalex when:**
- You want zero-setup, instant results from source code
- You need grep, AST patterns, body extraction, scaladoc
- The project hasn't been compiled yet
- You're doing broad exploration (search, overview, entrypoints)

**Use scalex-semanticdb when:**
- You need precise, compiler-verified references (no false positives)
- You need call graph analysis (callers, callees, flow)
- You need type information (return types, resolved signatures)
- You need to trace execution flow across service layers
- You're building a knowledge base and need to discover related symbols

**Use both together:** scalex for fast exploration and source-level queries, scalex-semanticdb for precision queries and call graph analysis.

## Getting .semanticdb files

**Mill** (easiest — works for both Scala 2 and 3):
```bash
./mill __.semanticDbData
```

**sbt** (built-in plugin, works for both Scala 2 and 3):
```scala
// build.sbt — SemanticdbPlugin is auto-triggered, just enable it
ThisBuild / semanticdbEnabled := true
```

Output: `META-INF/semanticdb/` under the classes directory.

## Usage

```bash
scalex-sdb <command> [args] [options]

# Index SemanticDB data (auto-discovers from out/, target/, .bloop/)
scalex-sdb index

# Precise references
scalex-sdb refs MyService

# Call flow tree (the killer feature)
scalex-sdb flow processPayment --depth 3

# Who calls this method?
scalex-sdb callers handleRequest

# What does this method call?
scalex-sdb callees main

# Co-occurring symbols
scalex-sdb related UserService

# Type information
scalex-sdb type myFunction

# Compiler warnings/errors
scalex-sdb diagnostics
```

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

## Planned features

- [ ] `implicits <file:line>` — show implicit/given resolution from synthetics
- [ ] `overloads <name>` — list all overloads with disambiguated signatures
- [ ] `endpoint <route>` — trace HTTP route to handler to service (framework-aware)
- [ ] `search` — fuzzy symbol search (like scalex's search)
- [ ] `body` — extract source body using occurrence ranges
- [ ] `diff` — show symbols changed since a git ref
- [ ] `overview <package>` — package summary with type info and call relationships
- [ ] Batch mode — multiple queries in one invocation
- [ ] Incremental index updates via md5 comparison

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
