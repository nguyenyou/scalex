# Changelog

## [Unreleased]

## [0.1.0] - 2026-03-22

Initial release.

### Added

- SemanticDB file discovery from Mill (`out/`), sbt (`target/`), and Bloop (`.bloop/`) build output directories
- Protobuf parsing via scalameta's `Locator` and `semanticdb-shared` library
- Binary index persistence at `.scalex/semanticdb.bin` with string interning and md5-based cache invalidation
- 15 commands:
  - `lookup` — find symbol by FQN or display name
  - `refs` — compiler-precise references with `--role` filter (def/ref)
  - `supertypes` — parent type chain from ClassSignature
  - `subtypes` — subtype tree from parent index
  - `members` — declarations of a class/trait/object
  - `type` — resolved type signature
  - `callers` — reverse call graph (who calls this method)
  - `callees` — forward call graph (what does this method call)
  - `flow` — recursive call tree with `--depth` control
  - `related` — co-occurring symbols ranked by frequency
  - `diagnostics` — compiler warnings/errors
  - `symbols` — list symbols in file or entire index
  - `occurrences` — all occurrences in a file with roles
  - `index` — force rebuild
  - `stats` — index statistics
- JSON output (`--json`) for all commands
- Kind filter (`--kind`) and role filter (`--role`)
- Timing instrumentation (`--timings`)
- GraalVM native image build via `build-native-semanticdb.sh`
