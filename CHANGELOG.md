# Changelog

## [Unreleased]

### Added
- Codex CLI and desktop plugin packaging and repository marketplace, sharing the existing skill and binary bootstrap with Claude Code.

### Fixed
- `graph --parse` no longer drops sibling nested boxes: a box containing two or more boxes side by side wrongly promoted all but one of them to top level (and leaked their border characters into the parent's text). Box containment is now resolved by linking each box to its smallest container
- `grep --in <owner>`: an unreadable file no longer discards matches already found in other files defining the same owner; the file is skipped instead

### Changed
- `ast-pattern --json` now emits the same symbol object as `search`/`def` (gains `parents`, `typeParamParents`, and `annotations` fields) — one JSON shape for all symbol-returning commands
- When both `-w` and `--workspace` are passed, the last occurrence now wins (previously `--workspace` always took precedence regardless of position)
- `refs --top` now uses the same timed-out suffix as every other command (`(timed out — partial results)` instead of `(timed out — results may be incomplete)`)
- `overview --architecture/--concise/--focus-package` reads imports recorded in the index instead of re-parsing every source file — on the scala3 corpus (17.7k files) this drops `overview --concise` from ~3.7s to ~0.75s (4.9×)
- Native-image note: bloom-loading commands (`refs`/`imports`/`coverage`) run 7–15% slower in this build (~50–90ms on the scala3 corpus) while most other commands got 10–18% faster. The slowdown is entirely in the untouched index-deserialization phase and does not reproduce on the JVM (parity there) — it traces to GraalVM GC pacing/codegen shifts from the binary growing, not to any changed code path. Pinning the heap (`-Xms1g`) recovers most of it on both old and new binaries
- Line-number gutters are now uniformly left-aligned: `refs -C` previously right-aligned line numbers while `body` left-aligned them
- `refs`/`imports` now report `timedOut: true` when the deadline expires mid-file on the last scanned file (previously the partial result could be reported as complete)

### Fixed
- `grep --json` no longer emits invalid JSON when the auto-corrected pattern contains backslashes or quotes (e.g. `grep 'foo\|\d+' --json` produced an unescaped `"corrected":"foo|\d+"` hint)
- `graph` no longer corrupts its edge-list args when a value-taking global flag (e.g. `--limit 5`) is passed — the flag strip-list is now derived from the flag registry instead of a hardcoded subset
- `--expand` without a numeric value no longer consumes a following flag (e.g. `explain Foo --expand --no-doc` now applies `--no-doc`; bare `--expand` still means depth 1)
- A positional argument that equals a flag's value elsewhere in the arg list (e.g. `scalex def --kind class class`) is no longer dropped — flag parsing is now a single position-aware pass
- Corrupted or truncated index caches now report the failure cause on stderr (`index load failed (EOFException: …) — rebuilding`) instead of a generic message
- Git subprocess readers are closed after use; a missing `git` binary now produces a clear error instead of a stack trace

### Changed (internal)
- JSON emission helpers (`jStr`/`jArr`/`jStrArr`/`jOpt`, `jsonMemberFields`, `jsonBodyFields`) replace ~50 hand-rolled `jsonEscape` interpolation fragments across all renderers — every string value is escaped exactly once by construction
- One `DeadlineScan` chassis (deadline, result queue, unreadable-file counter, parallel per-file/per-line scanning) behind `grepFiles`, `findReferences`, and `findImports`
- Shared `pathPredicate` for the `--no-tests`/`--path`/`--exclude-path` filter chain (previously copied in `filterSymbols`, `filterRefs`, `grepFiles`, `astPatternSearch`, `tests`)
- One `nameMatchTier` classifier (exact/prefix/contains/reverse-contains/camelCase) behind symbol search, file search, and owner-scoped suggestions
- `requireArg` helper replaces the 21 identical `args.headOption match … UsageError` command preambles
- `buildMultiIndex` builds the inverted lazy indexes (`parentIndex`, `typeParamParentIndex`, `annotationIndex`); `distinctSymbols` uses `distinctBy`
- Java extraction shares `javaParams`/`javaMethodSig`/`javaFieldInfo`/`javaLine` between file-symbol and member extraction (signatures can no longer drift apart), and Java body extraction uses one `addBody` slice helper
- `findTypeDefs` names the repeated "definitions that are types" lookup; `SymbolKind.label`, `Confidence.label/explanation`, `refCategoryOrder`, `wildcardImportPkg`, `pkgSuffix`, `numberedLine`, `renderShown` (the `... and N more` footer), and a single recursive hierarchy printer replace their scattered copies
- `renderSourceBlocks` reads each file at most once for context windows (previously twice per block in text mode); context slicing shares one `contextWindow` helper and `readSourceLines`
- `coverage` computes its not-found hint in the command instead of inside the renderer; `bench.scala` is `return`-free
- New tests: a structural JSON validator sweeps every `--json` command output; locks on search ranking, overview package-dependency edges, Java signature consistency, truncation footers, and context-line rendering
- New `clibase` Mill module — app-agnostic CLI plumbing usable as a base for other CLIs: declarative flag registry (each flag declared once: spellings, value shape, default, help text), single-pass lenient parser (unknown `--long` flags ignored), typed `Flags` bag, generated `Options:` help, `Timings`, `OutputBudget` (line-boundary truncation), `BatchLoop` (stdin REPL)
- The self-contained `asciiGraph` package moved from `src/graph/` to its own `graph` Mill module
- Deleted `ParsedFlags` (45 parallel fields), the hand-written parser match, and the hand-written options help — flag spellings, defaults, and help text now live in one declaration per flag in `src/flags.scala`, and `CommandContext` is built in a single place (`flagsToContext`)
- Removed the dead `parseWorkspaceAndArg` helper
- `findReferences`, `findImports`, and `categorizeReferences` return `timedOut` as part of their result instead of mutating shared state on `WorkspaceIndex` — concurrent queries can no longer clobber each other's timeout flag
- Member extraction (`members`, `grep --each-method`) now shares one traversal; body extraction and the Scala 3 → 2.13 parse fallback are deduplicated
- Removed all remaining `return` statements from production code (34) per the codebase-wide policy
- Deduplicated command-layer logic into shared helpers: dotted-name splitting (`simpleNameOf`, `splitOwnerMember`), member decoration (`decorateMembers`), body-size gating (`bodyWithinLimit`), package resolve-or-not-found (`withResolvedPackage`), stdlib package detection/ranking (`isStdlibPackage`, `stdlibPkgRank`), timed-out suffix, and kind counting (`countByKind`); `grep` shares one owner-lookup and span-scan path, and `overview` computes its parent ranking once for both most-extended and hub types
- Simplicity pass (−443 lines net, no behavior change beyond the items above). Benchmarked on the scala3 corpus (17.7k files): index size byte-identical, cold index −12%, warm index and all index-backed queries at parity. The bloom-loading commands (`refs`/`imports`) show another ~10% native-binary slowdown isolated to the untouched index-deserialization phase with exact JVM parity (cache-load 237 ms on both HEAD and this change) — the same GraalVM GC-pacing/codegen artifact documented above, this time from the binary shrinking 42→40 MB:
  - graph module: deleted the unused library surface (`EdgeType`/`connections`, `CrossingCalculator`, `OccupancyGrid`, `GraphUtils.topologicalSort`/`hasCycle`, six unused `Graph` methods, `QuadTree.collisions`, assorted dead members) and collapsed single-implementation abstractions: the `Lens` machinery (replaced by a plain child-node list), `VertexRenderingStrategy` (with its `asInstanceOf` casts), and the `LayoutPrefs`/`RendererPrefs` trait tower (now one `LayoutPrefs` case class); `Direction` is a Scala 3 enum; the renderer's twelve per-character defs are three indexed glyph strings, and the swapped `lineHorizontalChar`/`lineVerticalChar` names are fixed
  - `graph --parse` text collection no longer rebuilds the all-edge-labels point set once per scanned character (was O(edges × area) per box cell)
  - deleted parallel types: `AstPatternMatch` (≡ `SymbolInfo`), dead `CategorizedRef`, `OverviewData.hubTypes` (same data as `mostExtended`), write-only `SymbolList.total`/`StringList.total`, write-only `TestCaseInfo.suiteName`/`suiteFile`; `HierarchyNode`'s four parallel `Option` fields are now one `Option[SymbolInfo]`; the `symbols --summary` "already printed" sentinel is an explicit `CmdResult.Silent`
  - folded duplicated logic: `index()` cached/uncached parse branches are one path, `search`/`searchFiles` share one tier bucketer, `findImplementations`/`fixPosixRegex`/`cmdGrep` lost their copy-pasted branches, `grep --in` reads each file once per owner definition (was once per body block), categorized refs resolve confidence once per ref (was once per confidence level), and the overview/index-stats/entrypoints renderers share their repeated print blocks and JSON fragments (`jKindCounts`, `InheritedGroup` alias)

## [1.40.0] — 2026-06-01

### Changed
- Migrated the project build from scala-cli directives to Mill, including CLI/test/benchmark modules and native image packaging.
- Bumped Scalex to 1.40.0, Scalameta to 4.17.0, JavaParser to 3.28.2, and munit to 1.3.1.

## [1.39.0] — 2026-04-23

### Changed
- Bumped Scala to 3.8.3, Scalameta to 4.16.1, Guava to 33.6.0-jre, and munit to 1.3.0

## [1.38.0] — 2026-03-22

### Fixed
- `grep` with POSIX-style `\|` alternation (e.g. `Optimizer\|IncOptimizer`) now correctly converts to Java regex alternation instead of silently matching literal pipe characters — previously returned zero results because `\|` is valid Java regex (literal `|`)

## [1.37.0] — 2026-03-22

### Fixed
- `grep` no longer errors on invalid regex patterns (e.g. `MyRenderer(`) — auto-quotes them as literal strings instead of failing with "Invalid regex: Unclosed group"

### Changed
- `tests --count` now reports dynamic test sites (string interpolation, concatenation) alongside literal test count; output format changed from `N suites, M tests` to `M tests (literal names only) across N suites` with dynamic site count when detected (#259)
- Plugin directory reorganized: `plugin/` → `plugins/scalex/`

## [1.36.0] — 2026-03-20

### Added
- `--max-output N` — global character budget that truncates any command's output at N characters with a pagination hint; works on all commands including batch mode (#252)
- `--in-package PKG` — filter symbols and references to files whose package matches PKG prefix; cheaper than `--path` for cross-compiled projects where package != directory (#252)
- `grep --in <Type> --each-method` — iterate members of a type, grep each method body for the pattern, report which methods matched with counts; single command replaces N `body --in` + manual grep round-trips (#253)

## [1.35.0] — 2026-03-19

### Added
- `overview --concise` — fixed-size summary (~60 lines) regardless of codebase size; compact header, inline symbols, top packages, dep stats (not full graph), hub types, and drill-down hints; implies `--architecture` (#248)

## [1.34.0] — 2026-03-19

### Fixed
- Owner.Member dotted syntax (`Outer.Inner`) now works across all commands: `members`, `hierarchy`, `impl`, `def`, `explain`, and `body --in` (#239)
- `def Outer.Inner` no longer duplicates results (#239)
- `explain Outer.Inner` now includes members section (#239)

## [1.33.0] — 2026-03-19

### Fixed
- `explain --related` no longer resolves stdlib/language type names (e.g. `Option`, `List`, `String`, `Future`) to unrelated project types (#228)

## [1.32.0] — 2026-03-18

### Added
- `explain --related` — show project-defined types referenced in member signatures as "Related types" section (#221)
- `package --explain` — composite mode: brief explain per type with definition, top 3 members, and impl count (#221)

### Improved
- `overview --architecture` — better hub detection: expanded stdlib name list (~30 more types) and new `isStdlibPackageOnly` filter excludes types only defined in java.*/scala.* packages (#221)

## [1.31.0] — 2026-03-18

### Fixed
- `body` command now shows signature for abstract defs instead of "No body found" (#208)
- `body --in` "Did you mean" suggestions now scoped to owner's members instead of unrelated global symbols (#209)

### Changed
- SKILL.md: `graph` command no longer auto-triggers — only runs when user explicitly asks to draw/render a graph

## [1.30.0] — 2026-03-18

### Added
- `graph` command: render directed graphs as ASCII/Unicode art and parse diagrams back into structured data
  - `graph --render "A->B, B->C"` — Sugiyama-style layered layout
  - `graph --parse` — extract boxes, edges, labels from ASCII art (stdin)
  - Flags: `--unicode`/`--no-unicode`, `--vertical`/`--horizontal`, `--rounded`, `--double`, `--json`
  - Ported from [ascii-graphs](https://github.com/scalameta/ascii-graphs) (45 Scala 2 files consolidated into 11 Scala 3.8 files)
- `docs/ARCHITECTURE.md` — project architecture documented with `scalex graph`-generated diagrams

### Fixed
- Replace deprecated Scalameta `.stats` with `.body.stats` on `Pkg` and `Template` (9 call sites) (#210)

## [1.29.0] — 2026-03-18

### Added
- `members`: `--limit 0` shows all members (no truncation); `--offset N` for pagination (#198)

### Fixed
- `body --in` now also searches the owner's file when the symbol is indexed in a different file (#197)

## [1.28.0] — 2026-03-18

### Fixed
- `overview --architecture` no longer shows duplicate "Most extended" section — hub types supersedes it (#192)

## [1.27.0] — 2026-03-18

### Fixed
- `members` companion section no longer duplicates the primary type's members — each side now shows only its own members (#184)
- `members` now uses simple name for `extractMembers` lookup instead of fully qualified name
- `explain` now respects `--kind` filter; fixed vacuous test assertion

## [1.26.0] — 2026-03-18

### Fixed
- Fuzzy fallback now deprioritizes `java.*`/`javax.*`/`scala.*` packages — project-specific types rank above standard library stubs (#185)

## [1.25.0] — 2026-03-18

### Added
- `members --body` / `explain --body` — inline method bodies into member listings; eliminates N follow-up `body --in` calls (#180)
- `--max-lines N` flag — only inline bodies ≤ N lines (0 = unlimited); works with `members`, `overrides`, and `explain` (#180)
- `body -C N` — show N context lines above and below the extracted body span (#180)
- `body --imports` — prepend the file's top-level import block to body output (#180)
- `overrides --body` — show each override's source body inline (#180)
- `grep --in <symbol>` — scope grep to a specific class or method body; supports `Owner.member` dot syntax (#180)

### Changed
- SKILL.md restructured: 693 → 404 lines; low-traffic commands and options table moved to `references/commands.md` for progressive disclosure (#180)
- `explain` flag docs restructured from wall-of-text paragraph to bulleted list (#180)

### Fixed
- `-e PATTERN` options table entry now correctly says `|` (Java regex), not `\|` (POSIX) (#180)

## [1.24.0] — 2026-03-18

### Improved
- Not-found hints now explain that scalex indexes top-level declarations only (local defs, parameters, and pattern bindings are not indexed) (#176)
- Fallback suggestion now recommends `scalex grep` alongside Grep/Glob/Read tools (#176)
- Batch mode not-found output includes "top-level only" note (#176)

## [1.23.0] — 2026-03-18

### Fixed
- Java parser crash: `parseJavaSource` now catches `Error` (not just `Exception`) — fixes `NoSuchFieldError` crash from JavaParser on certain Java files (#172)
- `body` command fallback owner lookup now applies `filterSymbols` — `--path`, `--no-tests`, `--exclude-path` are respected when searching for the `--in` owner's files (#172)
- `body` command now finds nested local defs inside methods (e.g. `body runPhases --in Run` finds a `def runPhases` inside `compileUnits`) — tree walker now recurses into def bodies and general AST nodes (#172)
- Batch mode now parses per-line flags (`--path`, `--no-tests`, `--in`, etc.) — previously all per-line flags were ignored and only top-level flags applied (#172)

### Changed
- Extracted `parseFlags` / `flagsToContext` helpers from `main` — eliminates flag-parsing duplication between batch loop and single-command path (#172)

## [1.22.0] — 2026-03-17

### Added
- `explain --brief` flag — condensed output with definition + top 3 members only; no doc, companion, inherited, impls, or imports (#164)
- `explain` disambiguation now prints copy-paste `scalex explain pkg.Name` commands on stderr instead of just a count + generic hint (#164)

### Changed
- `otherMatches` in `explain` JSON output changed from integer to string array of package-qualified names (#164)

## [1.21.0] — 2026-03-17

### Added
- `explain --no-doc` flag to suppress Scaladoc section — useful when exploring many types rapidly and doc dominates output (#157)

### Fixed
- `search` now returns reverse-suffix matches — querying `ScalaJSClassEmitter` suggests `ClassEmitter` when no exact match exists (#156)
- `explain` fuzzy auto-resolve now checks suffix matches — `explain ScalaJSClassEmitter` auto-shows `ClassEmitter` (#156)
- `search` not-found now shows "Did you mean?" suggestions — previously returned bare "0 matches" with no hints (#156)
- `coverage` not-found now shows "Did you mean?" suggestions — previously returned bare hint with no suggestions (#156)

### Changed
- Extracted `resolvePackage()` and `mkPackageNotFound()` shared helpers — deduplicated identical package resolution logic from `package`, `api`, `summary` commands (#156)

## [1.20.0] — 2026-03-17

### Changed
- `identifierBloom` field is now `Option[BloomFilter]` — eliminates null sentinel; index format bumped to v8 (old caches auto-rebuild) (#148)
- Exception catches narrowed from `Exception` to `java.io.IOException` for file I/O; parse fallbacks log to stderr: `scalex: parse failed: <path>` (#148)
- Parallel file reads (grep, refs, imports) use atomic counters with summary: `scalex: N file(s) unreadable during <op>` (#148)
- Index load failure now logs: `scalex: index load failed, rebuilding` (#148)

### Fixed
- `diff` command now detects Given, GivenAlias, ExtensionGroup, and Pkg.Object symbols — previously missed by `extractSymbolsFromSource` (#148)
- `diff` command no longer reports local vals inside def bodies as added/removed — traversal now correctly skips def/val/var/given bodies (#148)
- `body` command now supports `Owner.method` dotted syntax (e.g. `body DynamicOwner.activate`) — splits into member name + implicit `--in` owner (#147)
- `body` not-found error message now includes the owner name when `--in` is used (e.g. `No body found for "onStart" in WritableObservable`) (#147)

## [1.19.0] — 2026-03-17

### Added
- `entrypoints` command — find `@main` annotated, `def main(...)` methods, `extends App`, and test suites in one call; supports `--no-tests`, `--path`, `--json` (#141)
- Override markers on `members --inherited` and `explain --inherited` — own members that shadow parent members are annotated with `[override]` in text output and `"isOverride":true` in JSON (#141)

### Fixed
- `explain` package fallback now warns on stderr: `(no type "X" found — showing package summary instead)` instead of silently falling back (#141)

## [1.18.0] — 2026-03-17

### Fixed
- `isTestFile` now detects root-level `test/`, `tests/`, and `testing/` directories (previously required a leading `/`) (#132–#135)
- `explain` import refs now respect `--path` and `--exclude-path` filters (previously unfiltered) (#133, #134)

### Added
- `explain --inherited` — merge parent members into explain output with provenance markers; same as `members --inherited` but in the composite view (#137)
- `refs --top N` — rank files by reference count descending; shows heaviest users first for impact analysis (#137)
- `explain` disambiguation hint — when multiple symbols match, prints "(N other matches — use package-qualified name or --path to disambiguate)" to stderr; JSON includes `otherMatches` field (#132, #133, #134)
- `explain` package fallback — if `explain foo` finds no symbol but `foo` matches a package name, automatically falls back to `summary foo` (#132)
- `explain --shallow` — skip implementations and import refs; show only definition + members + companion (#134)
- `explain` totalImpls hint — when more implementations exist than `--impl-limit`, shows "(showing N of M — use --impl-limit to adjust)" (#134)
- `explain` companion members deduplication — when companion shares members with primary type, collapses duplicates with a note (#134)
- `overview` hub type PascalCase — "most extended" and "hub types" now preserve original name casing instead of lowercased keys (#132, #133)
- `overview` hub type noise filter — single-character names excluded; sorted by distinct-extending-package count (secondary: impl count) (#133, #135)
- `overview` hub type signatures — one-line signature shown next to each hub type name (#132)
- `overview --path` — scope entire architecture view (hub types, package deps, counts) to a path prefix (#133, #135)
- `--exclude-path PREFIX` — negative path filter available on all commands; exclude files under a directory (#135)
- `symbols --summary` — grouped counts by kind (e.g. "12 classes, 3 traits, 45 defs") instead of full listing (#133)

## [1.17.0] — 2026-03-17

### Fixed
- `extractSymbols` no longer indexes local vals/defs/vars inside method bodies — traversal now stops at `Defn.Def`/`Val`/`Var`/`Given`/`GivenAlias` boundaries; matches SKILL.md documentation (#127)
- `extractMembers` now includes case class constructor params as val members; regular class params only if marked `val`/`var` (#127)

### Added
- `package --definitions-only` — filter to class/trait/object/enum only, same as `search --definitions-only` (#121)
- `overview` hub types filter stdlib — `mostExtended` and `hubTypes` now skip scala/java base types (object, serializable, anyval, matchable, etc.) to surface real domain types (#121)
- `explain` members sorted by kind — classes/traits first, then defs, then vals/vars, then types; add `--members-limit N` (default: 10) (#121)
- `explain` fuzzy fallback — if exact match fails, tries fuzzy search and auto-shows best type match with stderr hint (#121)
- `hierarchy` truncation count — shows "... and N more children" at depth limit instead of silent cut-off (#121)
- `scalex summary <package>` — sub-package breakdown with symbol counts; middle ground between `overview` and `package` for top-down exploration (#121)
- `overview` defaults to `--no-tests` — production code is almost always the intent; use `--include-tests` to opt in (#119, #120)
- `explain --verbose` shows member signatures instead of just names (#119, #120)
- `explain` inlines "Imported by" file list when count <= 10; shows count + hint otherwise (#119, #120)
- `refs --count` summary mode — category counts without full file lists (e.g. "12 importers, 4 extensions, 30 usages") (#119, #120)
- Companion-aware `members` — auto-shows companion object/class members alongside the primary type (#119, #120)
- `Owner.member` dotted syntax for `def` and `explain` — `def MyService.findUser` resolves to the member directly (#119, #120)
- `api --used-by <package>` — filter importers to only those from a specific package; coupling analysis (#120)
- `search` ranking includes import popularity — symbols from heavily-imported types surface first (#119, #120)
- `search --returns <Type>` / `--takes <Type>` — filter search results by signature substring (#119, #120)

## [1.16.0] — 2026-03-17

### Added
- `scalex api <package>` command — show a package's public API surface by cross-referencing imports; symbols sorted by external importer count; supports `--kind`, `--no-tests`, `--path`, `--json`; zero index change, pure in-memory query (#102, #103)
- `--brief` flag for `members` command — show names only instead of signatures (signatures are now the default) (#102)
- `--strict` flag for `refs` and `imports` commands — treats `_` and `$` as word characters for stricter boundary matching (#101)
- `deps --depth N` — transitive dependency expansion with cycle detection and depth indentation; hard-capped at 5 (#103)
- Lightweight Java file awareness — regex-based extraction of class/interface/enum/record declarations from `.java` files (#103)
- Java files are included in `searchFiles`, `findDefinition`, `findImplementations`, and `isTestFile`
- Package-qualified symbol lookup — `scalex def coursier.cache.Cache` resolves by fully-qualified name; partial qualification also works (`cache.Cache` matches `coursier.cache.Cache`); benefits all commands that use `findDefinition` (#101)
- Companion merging in `explain` — auto-shows companion object/class members alongside the primary symbol; eliminates the most common follow-up query after `explain` (#102, #103)
- `--expand N` flag for `explain` — recursively expand implementations N levels deep with cycle detection; `explain Trait --expand 1` shows each implementation's members in one call (#102)
- Type-param parent indexing — `impl Foo` now finds `class Bar extends Mixin[Foo]`; type arguments in extends clauses are indexed as `typeParamParents`; single-letter type params (T, A, F) filtered out; index format bumped v6→v7 (#101)
- `scalex package <pkg>` command — list all symbols in a package grouped by kind; supports `--verbose`, `--kind`, `--no-tests`, `--path`; fuzzy match on package name (exact → suffix → substring) (#95)
- `--focus-package PKG` flag for `overview` — scopes `--architecture` dependency graph to a single package, showing direct dependencies and dependents; auto-enables `--architecture` when used (#96)
- Fuzzy "did you mean?" suggestions on not-found — when `def`, `explain`, `members`, `doc`, `body`, `hierarchy`, `overrides`, `deps`, `impl` return zero results, suggests close matches from the index; shown in text, batch, and JSON output (#94)
- `overview --no-tests` filtering — excludes test files from symbol counts, top packages, most-extended lists, and hub types (#93)

### Changed
- `members` command now shows signatures by default (previously required `--verbose`); use `--brief` for names-only output (#102)
- `search` results now have secondary sort within each tier by (kind rank, test rank, path length) for more deterministic output (#101, #102)
- `refs` categorized output now sorts within each confidence group by (file path, line number) for stable output (#101, #102)
- `refs` flat output now sorts by (confidence, file path, line number) (#101, #102)
- `--depth` flag now uses sentinel -1 for "not specified", defaulting to 5 for hierarchy and 1 for deps
- `deps` JSON output now includes `"depth"` field; text output indents by depth level
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

## [1.0.0] — 2026-03-14

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
