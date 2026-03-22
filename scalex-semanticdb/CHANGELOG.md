# Changelog

## [Unreleased]

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
