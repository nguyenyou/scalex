# Changelog

## [Unreleased]

### Added

- `batch` command — run multiple queries in one invocation, amortizing index load time. Each positional arg is a sub-command string with its own flags. Results delimited in text mode, wrapped in `{"batch":[...]}` in JSON mode. ([#284](https://github.com/nguyenyou/scalex/issues/284))
- `--no-accessors` flag for `flow` and `callees` — filters out val/var field accessors (e.g., `.userId`, `.entityId`, `.config`) that add noise without insight. Reduced callees from 77 to 43 on a real-world service method. ([#284](https://github.com/nguyenyou/scalex/issues/284))
- `--exclude "p1,p2,..."` flag for `flow`, `callees`, and `callers` — filters symbols whose FQN contains any of the given comma-separated patterns. In `flow`, also prevents recursion into excluded symbols. ([#284](https://github.com/nguyenyou/scalex/issues/284))

### Changed

- `resolveSymbol` now prefers source-defined symbols over generated code (protobuf, codegen) when multiple symbols share the same display name. Generated files (URIs starting with `out/` or containing `compileScalaPB.dest`) are deprioritized in ranking.

## [0.1.0] - 2026-03-22

Initial release.

### Added

- SemanticDB file discovery — optimized for Mill (`out/`), with fallback for sbt (`target/`) and Bloop (`.bloop/`)
- Parallel protobuf parsing via `parallelStream()` and `TextDocuments.parseFrom`
- Binary index persistence at `.scalex/semanticdb.bin` with string interning
- Deduplication of Mill `jsSharedSources` generated copies
- 14 commands focused on compiler-unique capabilities:
  - **Call graph:** `flow`, `callers`, `callees`
  - **Compiler-precise queries:** `refs`, `type`, `related`, `occurrences`
  - **Navigation:** `lookup`, `supertypes`, `subtypes`, `members`, `symbols`
  - **Index:** `index`, `stats`
- JSON output (`--json`) for all commands
- Kind filter (`--kind`) and role filter (`--role`)
- Timing instrumentation (`--timings`)
- Assembly JAR build via `build-native-semanticdb.sh` (JVM JIT is 11x faster than GraalVM native for this workload)
