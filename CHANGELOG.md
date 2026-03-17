# Changelog

## [Unreleased]

### Added
- Package-qualified symbol lookup — `scalex def coursier.cache.Cache` resolves by fully-qualified name; partial qualification also works (`cache.Cache` matches `coursier.cache.Cache`); benefits all commands that use `findDefinition` (#101)
- Companion merging in `explain` — auto-shows companion object/class members alongside the primary symbol; eliminates the most common follow-up query after `explain` (#102, #103)
- `--expand N` flag for `explain` — recursively expand implementations N levels deep with cycle detection; `explain Trait --expand 1` shows each implementation's members in one call (#102)
- Type-param parent indexing — `impl Foo` now finds `class Bar extends Mixin[Foo]`; type arguments in extends clauses are indexed as `typeParamParents`; single-letter type params (T, A, F) filtered out; index format bumped v6→v7 (#101)
- `scalex package <pkg>` command — list all symbols in a package grouped by kind; supports `--verbose`, `--kind`, `--no-tests`, `--path`; fuzzy match on package name (exact → suffix → substring) (#95)
- `--focus-package PKG` flag for `overview` — scopes `--architecture` dependency graph to a single package, showing direct dependencies and dependents; auto-enables `--architecture` when used (#96)
- Fuzzy "did you mean?" suggestions on not-found — when `def`, `explain`, `members`, `doc`, `body`, `hierarchy`, `overrides`, `deps`, `impl` return zero results, suggests close matches from the index; shown in text, batch, and JSON output (#94)
- `overview --no-tests` filtering — excludes test files from symbol counts, top packages, most-extended lists, and hub types (#93)

### Changed
- Move tests from `src/` to dedicated `tests/` directory — cleaner separation of production code and test suite; run with `scala-cli test src/ tests/`
- Move benchmark scala3 clone from repo root `scala3/` to `benchmark/scala3/` — prevents 17.7k benchmark files from polluting grep/search results; `benchmark-results/` consolidated to `benchmark/results/`
- Add search scope guidance to CLAUDE.md — directs agents to search `src/` and `tests/` instead of repo-wide
- Update BENCHMARK.md with fresh numbers — all metrics improved significantly since last capture

### Fixed
- `hierarchy` hangs on large codebases — recursive `walkDown`/`walkUp` had no depth limit, causing exponential fan-out on types like `Phase` (40+ direct children, each with dozens more); added `--depth N` flag (default 5) to cap tree expansion

## [1.15.0] — 2026-03-16

### Added
- `--timings` flag — prints per-phase timing breakdown to stderr (git-ls-files, cache-load, oid-compare, parse, index-build, cache-save, bloom-screen, text-search); works in both JVM and native image
- Microbenchmark harness (`src/bench.scala`) — isolated per-function benchmarks with warmup and statistical measurement (mean/median/p99/stddev)
- async-profiler integration (`profiling/profile.sh`) — CPU/wall/alloc/lock flame graphs
- JFR config (`profiling/scalex.jfc`) — custom Java Flight Recorder settings for GC, allocation, I/O, and thread analysis
- Enhanced `bench.sh` — index size reporting, diverse query benchmarks, `--timings` integration
- `bench-compare.sh` — compare two hyperfine JSON exports, flag >5% regressions

### Changed
- Lazy map building in `WorkspaceIndex` — derived indexes (`symbolsByName`, `parentIndex`, `filesByPath`, etc.) are now `lazy val` fields computed on first access instead of eagerly built in a monolithic `index-build` phase. Commands that use only 1–2 maps skip building the rest. Benchmarked on scala3 compiler (17.7k files): `file` 2.16x faster (951→441ms), `impl` 2.00x (928→465ms), `packages` 1.86x (904→486ms), `def` 1.31x (905→693ms), `grep` 1.59x (1442→904ms)

### Fixed
- `explain` now ranks class/trait/object/enum above val/def when selecting the primary symbol — previously took the first unranked result, so `explain Observer` could resolve to a `val observer` instead of `trait Observer` (#80)
- `hierarchy --up` and `--down` now correctly walk the inheritance tree — cycle-detection was pre-seeded with the root symbol, causing both directions to always return `(none)` (#80)

## [1.14.0] — 2026-03-16

### Fixed
- `overview --architecture` "Most extended" and "Hub types" no longer dominated by stdlib/framework types (`None`, `AnyVal`, `Object`, etc.) — both lists now filter to types defined in the indexed codebase (#64)

### Changed
- Extract `CmdResult` enum — commands return structured data, rendering happens at the boundary in `cli.scala`
- Make `cli.scala` a thin orchestrator — arg parsing and `@main` entry only, all command logic in `commands.scala`
- Replace 27-param `runCommand` with `CommandContext` case class and dispatch map
- Split single-file `scalex.scala` (2660 lines) into 6 focused source files under `src/`: `model.scala`, `extraction.scala`, `index.scala`, `analysis.scala`, `format.scala`, `commands.scala`, `cli.scala`, `project.scala`
- Split monolithic test suite `scalex.test.scala` (2262 lines) into 4 test suites + shared base: `ExtractionSuite`, `IndexSuite`, `AnalysisSuite`, `CliSuite`
- Refactor all tuples to named tuples for readability — `extractSymbols`, `extractImports`, `extractDeps`, `grepFiles`, `fixPosixRegex`, `parseWorkspaceAndArg`, `extractTestName`, and all inline tuple types
- Add named tuple code style rule to `CLAUDE.md`

## [1.13.0] — 2026-03-16

### Fixed
- False "parse error" reports for files with no extractable symbols — files containing only `Pkg.Object` (package objects), top-level `export` statements, or anonymous `given` aliases are no longer misreported (#61)
- `parseFailedFiles` now tracks actual parse failures via a `parseFailed` flag on `IndexedFile`, instead of the heuristic `symbols.isEmpty && file-size > 0`

### Added
- Index `Pkg.Object` (package objects) as `SymbolKind.Object` — previously ignored by the `visit` function

### Changed
- Index format bumped to v6 (adds `parseFailed` boolean per file) — first run after upgrade triggers a full reindex

## [1.12.0] — 2026-03-16

### Added
- `scalex tests [<pattern>] [--verbose] [--path PREFIX]` — list test cases structurally from MUnit, ScalaTest, and specs2 frameworks; optional name filter shows bodies inline (#56)
- `scalex coverage <symbol>` — "is this symbol tested?" shorthand: refs filtered to test files only, with count and file list (#56)
- `scalex body` now finds test cases — match `test("exact name")`, `it("name")`, `describe("name")`, `"name" in { }`, `"name" >> { }` by string literal (#56)
- `isTestFile` detects scala-cli `*.test.scala` convention (#56)

## [1.11.0] — 2026-03-16

### Added
- `scalex body <symbol> [--in <owner>]` — extract method/val/class body from source using Scalameta spans; eliminates follow-up Read calls (#53, #54)
- `scalex hierarchy <symbol> [--up] [--down]` — full inheritance tree: parents up + children down from extends clauses (#53, #54)
- `scalex overrides <method> [--of <trait>]` — find all override implementations of a method across implementors (#54)
- `scalex explain <symbol> [--impl-limit N]` — composite one-shot summary: definition + scaladoc + members + implementations + import count (#53, #54)
- `scalex deps <symbol>` — show what a symbol depends on: file imports + body type/term references (#53)
- `scalex context <file:line>` — show enclosing scopes at a given line: package, class, method (#54)
- `scalex diff <git-ref>` — symbol-level diff vs a git ref: added/removed/modified symbols (#54)
- `scalex ast-pattern [--has-method NAME] [--extends TRAIT] [--body-contains PAT]` — structural AST search with composable predicates (#54)
- `members --inherited` flag — walk extends chain, collect members from parent types with dedup (#53)
- `overview --architecture` flag — package dependency graph from imports + hub types (#53)

## [1.10.0] — 2026-03-16

### Added
- `scalex members <Symbol> [--verbose]` — list member declarations (def/val/var/type) inside a class/trait/object body; on-the-fly source parse, not stored in index (#48)
- `scalex doc <Symbol>` — extract leading scaladoc comment attached to a symbol; on-the-fly line scan (#48)
- `scalex overview` — one-shot architectural summary: symbols by kind, top packages, most-extended traits/classes (#48)
- `search --definitions-only` flag — filter search results to only class/trait/object/enum definitions (#48)
- `refs --category <cat>` flag — filter categorized refs to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment) (#48)

## [1.9.0] — 2026-03-16

### Added
- `search --exact` flag — only return symbols with exact name match (#43)
- `search --prefix` flag — only return symbols whose name starts with the query (#43)
- `-c` short alias for `--categorize` on `refs` — saves tokens in agent workflows (#43)
- Condensed "not found" output in batch mode — single line instead of multi-line hints (#43)

### Changed
- `refs` now categorizes output by default — `--categorize`/`-c` are kept as no-ops for backwards compatibility (#46)
- Added `--flat` flag to `refs` — opt into the old flat-list output (#46)

## [1.8.0] — 2026-03-16

### Improved
- `grep` auto-corrects POSIX regex to Java regex — `\|` → `|`, `\(` → `(`, `\)` → `)` with a stderr note; JSON output includes `"corrected"` field (#39)

## [1.7.0] — 2026-03-15

### Added
- `grep -e PATTERN` flag — multi-pattern grep in one call; patterns combined with `|` (#35)
- `grep --count` flag — output match/file count without full results for quick triage (#35)
- Regex syntax hint — when grep returns zero results and pattern contains `\|`, `\(`, or `\)`, shows hint about Java regex syntax (#35)
- Document `grep` support in batch mode (#35)

## [1.6.0] — 2026-03-15

### Added
- `--json` flag on all commands — structured JSON output for programmatic consumption (#32)
- `scalex annotated <annotation>` — find symbols with a specific annotation (e.g. `@deprecated`, `@main`); supports `--kind`, `--path`, `--no-tests` filters (#32)
- `scalex grep <pattern>` — regex search inside `.scala` file contents with `--path`, `--no-tests` filtering and 20s timeout; supports `-C N` context lines (#32)
- Annotation extraction from Scalameta AST during indexing — stored in binary index (format bumped to v5)

## [1.5.0] — 2026-03-15

### Added
- `--kind` filter now works on `def` and `impl` commands — `scalex def Driver --kind class` filters by symbol kind (#29)
- `--no-tests` global flag — excludes test files (`test/`, `tests/`, `testing/`, `bench-*`, `*Spec.scala`, `*Test.scala`, `*Suite.scala`) from results; works on `def`, `search`, `impl`, `refs`, `imports` (#29)
- `--path PREFIX` filter — restricts results to files under a path prefix, e.g. `scalex def Driver --path compiler/src/`; works on all query commands (#29)
- `refs -C N` context lines — shows N lines before/after each reference with line numbers and `>` marker, like `grep -C` (#29)
- Smarter `def` ranking — results sorted by: class/trait/object/enum first, then type/given, then def/val/var; non-test before test; shorter paths first (#29)

## [1.4.0] — 2026-03-15

### Added
- `-w` / `--workspace` flag — named flag for setting workspace path, avoids ambiguity with positional args
- Path-as-symbol hint — when a symbol looks like a filesystem path, suggests correct arg order

### Fixed
- zsh compatibility — bash re-exec guard in `scalex-cli` bootstrap script fixes `(eval):1: permission denied:` when zsh eval's the script (#22)

## [1.3.0] — 2026-03-15

### Added
- `scalex file <query>` — fuzzy search file names with camelCase matching
- Fuzzy camelCase search — `search "hms"` matches `HttpMessageService`, `search "usl"` matches `UserServiceLive`
- `scalex index --verbose` now lists files that had parse errors
- Not-found hint directs users to `scalex index --verbose` to see failed files

## [1.2.0] — 2026-03-15

### Performance
- Lazy bloom filter deserialization — non-bloom commands (`def`, `search`, `impl`, `symbols`, `packages`) skip deserializing blooms, cutting ~45% off index load time
- Skip index save when nothing changed — warm index no longer writes 22MB to disk when all files hit OID cache
- Eliminate double file read — `extractSymbols` reads each file once instead of twice (bloom filter + symbol extraction)
- Single-pass post-index map building — symbol/file maps built in 2 passes instead of 7 separate passes over 200K+ symbols
- Pre-computed search deduplication — `distinctBy` computed once at index time instead of every `search` call
- Adaptive bloom filter capacity — `max(500, source.length / 15)` scales bloom size with file size, reducing false positives for large files

## [1.1.0] — 2026-03-14

### Added
- Confidence annotations for `refs` output (High/Medium/Low) based on import resolution
  - **High**: reference is in the same package or has an explicit import
  - **Medium**: reference has a wildcard import (`import pkg._`) matching the target's package
  - **Low**: no matching import found
- `refs --categorize` groups by confidence level, then by category
- `refs` (non-categorized) sorts by confidence with section headers
- Wildcard import resolution in `imports` command — `import com.example._` now surfaces when searching for symbols in `com.example`
- Import alias tracking — `import X as Y` (Scala 3) and `import {X => Y}` (Scala 2) are now detected and followed
  - `refs X` also finds usages of alias `Y` in files that rename the import
  - Alias imports are classified as High confidence
  - Aliases survive binary cache roundtrip (index format bumped to v4)
- Alias-aware confidence: `resolveConfidence` now checks alias mappings — searching by alias name (e.g. `refs TextAlignE`) returns High confidence instead of Low
- Alias annotation in output: references found via alias show `[via alias Y]` suffix (e.g. `AliasClient.scala:6 — val svc: US = ??? [via alias US]`)

## [1.0.0] — 2025-05-20

### Added
- Initial release
- Symbol search, find definitions, find references, find implementations
- Import graph (`imports` command)
- File symbols and package listing
- Batch mode for multiple queries
- OID-based binary caching with bloom filters
- Categorized references (`--categorize` flag)
- Scala 2 and Scala 3 dialect support
- GraalVM native image build
- Claude Code plugin structure
