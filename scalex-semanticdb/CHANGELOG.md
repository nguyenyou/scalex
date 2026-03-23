# Changelog

## [Unreleased]

### Added

- `path <source> <target>` command — BFS shortest call path between two symbols. Default max depth 5. Supports `--smart`, `--exclude`. ([#297](https://github.com/nguyenyou/scalex/issues/297))
- `explain <symbol>` command — one-shot summary combining type signature, definition location, callers, callees, parents, and members. ([#297](https://github.com/nguyenyou/scalex/issues/297))
- `callers --depth N` — transitive caller tree with cycle detection. Default depth 1 (flat list, backward compatible). Depth > 1 produces indented FlowTree. Supports `--smart`, `--exclude`. ([#297](https://github.com/nguyenyou/scalex/issues/297))
- Disambiguation hints — when multiple symbols match a query, prints candidates with FQN + kind to stderr. Applied to all single-symbol commands (`flow`, `callees`, `callers`, `type`, `subtypes`, `supertypes`, `members`, `related`). ([#297](https://github.com/nguyenyou/scalex/issues/297))
- Auto-staleness detection — query commands now detect stale cache by comparing `.semanticdb` file mtimes against the cached index. No more manual `index` command needed after recompiling. ([#298](https://github.com/nguyenyou/scalex/issues/298))
- Incremental indexing — on stale cache, only re-converts `.semanticdb` files whose MD5 changed. Unchanged documents are reused from cache. Stats output shows `parsedCount`/`skippedCount`. ([#298](https://github.com/nguyenyou/scalex/issues/298))

### Changed

- `depth` parameter changed from `Int = 3` to `Option[Int] = None` — each command now supplies its own default (callers: 1, flow/subtypes: 3, path: 5).
- Shared helpers (`isTrivial`, `modulePrefix`) moved from `flow.scala` to `format.scala` for reuse by `callers` and `path`.
- `callers` flat mode now respects `--smart` and `--no-accessors` flags (previously only applied in tree mode).
- `rebuild` now uses incremental MD5 comparison when a cached index exists, avoiding full re-conversion of unchanged documents.

## [0.2.0] - 2026-03-23

### Added

- `batch` command — run multiple queries in one invocation, amortizing index load time. Each positional arg is a sub-command string with its own flags. Results delimited in text mode, wrapped in `{"batch":[...]}` in JSON mode. ([#284](https://github.com/nguyenyou/scalex/issues/284))
- `--no-accessors` flag for `flow` and `callees` — filters out val/var field accessors (e.g., `.userId`, `.entityId`, `.config`) that add noise without insight. Reduced callees from 77 to 43 on a real-world service method. ([#284](https://github.com/nguyenyou/scalex/issues/284))
- `--exclude "p1,p2,..."` flag for `flow`, `callees`, and `callers` — filters symbols whose FQN or file path contains any of the given comma-separated patterns. In `flow`, also prevents recursion into excluded symbols. ([#284](https://github.com/nguyenyou/scalex/issues/284))
- `--smart` flag for `flow` and `callees` — auto-filters infrastructure noise: val/var accessors and generated code (protobuf, codegen). In `flow`, only recurses into same-module callees (cross-module calls appear as leaves). ([#284](https://github.com/nguyenyou/scalex/issues/284))

### Changed

- `--kind` now narrows symbol resolution in `flow`, `callees`, and `callers` — e.g., `--kind method` picks the method over a companion object. Previously `--kind` only filtered output.
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
