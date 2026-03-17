# Scalex — Roadmap

## POC — DONE

- [x] Git file listing, Scalameta parsing, in-memory index
- [x] Find definition, find references, CLI interface
- [x] Tested on circe-sanely-auto (92 files) and mill (1415 files)

## Phase 1: Persistent Index + OID Caching — DONE

- [x] `.scalex/index.bin` binary persistence with string interning
- [x] OID-based skip — only re-parses changed files
- [x] Auto-reindex on cache miss

## Phase 2: CLI Polish — DONE

- [x] Default workspace (current directory)
- [x] Search ranking: exact → prefix → substring
- [x] `--kind` filter, `--limit N` flag
- [x] Quiet indexing on query commands

## Phase 3: Claude Code Plugin — DONE

- [x] `plugin.json` manifest (validates)
- [x] `SKILL.md` — triggers, commands, examples, output format
- [x] Launcher script (PATH lookup → scala-cli fallback)
- [x] Install instructions in skill (auto-detect platform, download binary)

## Phase 4: Native Binary — DONE

- [x] GraalVM native image: 28MB standalone binary
- [x] `build-native.sh` build script

## Phase 5: Optimizations — DONE

- [x] Binary persistence format v3 (DataOutputStream + string interning)
- [x] Bloom filters (Guava) for reference search pre-screening
- [x] Time-boxed search (20s timeout on refs and imports)
- [x] Tested on production monorepo (14k files), Scala 3 compiler (17.7k files)

## Phase 6: CI + Distribution — DONE

- [x] GitHub Actions: build native images on tag push (macOS arm64, macOS x64, Linux x64)
- [x] GitHub Releases: upload binaries per platform
- [x] `install.sh` in plugin: detect platform, download from releases
- [x] README with install + usage instructions
- [ ] Publish plugin to Claude Code marketplace (when available)

## Phase 7: AI-Agent Innovations — DONE

- [x] `scalex batch` — multiple queries, load index once
- [x] `scalex def --verbose` — return signatures, extends clauses, param types
- [x] `scalex impl <trait>` — find implementations (parse extends/with from AST)
- [x] `scalex refs --categorize` — group by Definition/ExtendedBy/ImportedBy/UsedAsType/Comment/Usage
- [x] `scalex imports <symbol>` — show import statements for a symbol
- [x] Fallback hints — on "not found", suggest Grep/Glob/Read as fallback
- [x] Scala 2 dialect fallback — auto-detect per file, works on mixed codebases

## Phase 8: Testing — DONE

- [x] 176 MUnit tests covering all features (ExtractionSuite: 47, IndexSuite: 53, AnalysisSuite: 20, CliSuite: 56)
- [x] Scala 3 symbol extraction (class, trait, object, def, val, type, enum, given, extension)
- [x] Scala 2 fallback (procedure syntax, implicit class, mixed projects)
- [x] Binary persistence roundtrip (parents, signatures survive save/load)
- [x] OID caching (cold vs warm, incremental reparse)
- [x] Bloom filter correctness
- [x] Search ranking, find definition, find implementations
- [x] Categorized references, import finding
- [x] Word boundary matching

### Benchmarks (native image)

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Production monorepo | 13,979 | 214,301 | 4.6s | 540ms |
| Scala 3 compiler | 17,733 | 203,077 | 2.9s | 412ms |

## Future

### Import-scoped confidence annotation — DONE
- [x] Annotate `refs` results with confidence (High/Medium/Low) based on file's imports
  - **High**: explicit import match or same package
  - **Medium**: wildcard import (`import pkg._`/`import pkg.*`) of a package containing the symbol
  - **Low**: no matching import (could be fully qualified, re-export, etc.)
- [x] Resolve wildcard imports in `imports` command using existing package→symbol data
- [x] All results kept (zero false negatives) — confidence used to sort/group, not filter

### Import alias tracking — DONE
- [x] Detect `import X as Y` (Scala 3) and `import {X => Y}` (Scala 2) as High confidence matches
- [x] Follow aliases: when searching `refs X`, also search for `Y` in files that alias `X as Y`

### Alias tracking improvements
- [x] Confidence for alias refs: refs found via alias are now High confidence (`resolveConfidence` checks alias names)
- [x] Alias annotation in output: show `[via alias Y]` when a reference was found through alias tracking
- ~~Reverse alias lookup~~: deliberately skipped — agent can do 2-step lookup (`def TextAlignE` → see alias → `refs TextAlign`), and reverse lookup risks worse-than-grep perf on large codebases

### Performance improvements — DONE
- [x] Eliminate double file read in `extractSymbols` — read file once, pass source to bloom filter builder
- [x] Skip `IndexPersistence.save` when nothing changed — avoid rewriting 22-28MB index when `parsedCount == 0`
- [x] Lazy bloom filter deserialization — non-bloom commands skip deserializing blooms, cutting ~45% off load time
- [x] Pre-compute search deduplication — `distinctBy` computed once at index time instead of every `search` call
- [x] Adaptive bloom filter capacity — `max(500, source.length / 15)` scales bloom size with file size
- [x] Single-pass post-index map building — 2 passes instead of 7 separate passes over 200K+ symbols

### Parse error diagnostics — DONE
- [x] Track which files had parse errors (not just the count)
- [x] `scalex index --verbose` lists the files with parse errors
- [x] Not-found hint directs users to `scalex index --verbose` to see failed files

### Fuzzy camelCase search — DONE
- [x] Support camelCase abbreviation matching in `search` — e.g. "hms" matches `HttpMessageService` by matching initials of each camelCase segment
- [x] Rank fuzzy results below exact/prefix/substring matches

### zsh compat + UX improvements (#22) — DONE
- [x] Bash re-exec guard in `scalex-cli` — fixes `(eval):1: permission denied:` when zsh eval's the script
- [x] `-w` / `--workspace` flag — named flag for workspace, avoids ambiguity with positional args
- [x] Path-as-symbol hint — detect when symbol looks like a path and suggest correct arg order

### AI-agent ergonomics (#29)

Feedback from real agent usage on large codebases (scala3 compiler, 14k+ files).

**High priority — noise reduction:** — DONE
- [x] `--kind` filter on `def` command — `def` ignores `--kind` today; `scalex def Driver --kind class` should filter by symbol kind (search already supports it)
- [x] `--exclude-tests` / `--no-tests` global flag — skip common test dirs (`tests/`, `**/test/**`, `bench-*`, `**/testing/**`); ~50% noise reduction on large repos
- [x] `--path` filter — restrict results to a subtree, e.g. `scalex def Driver --path compiler/src/`; essential for monorepos

**Medium priority — richer output:** — DONE
- [x] `refs -C N` context lines — show N lines before/after each reference (like `grep -C`); reduces follow-up Read calls
- [x] Smarter `def` ranking — rank class/trait/object above val/def, main source above test files, shorter package paths first

**Lower priority — new capabilities:** — DONE
- [x] Override search — `scalex overrides Phase.isRunnable` finds methods overriding a specific def; combines impl lookup with method-level filtering (completed in #53/#54)
- [x] `scalex hierarchy <class>` — show full inheritance chain (parents + children); `--up` for syntactic extends clause, `--down` for name-based impl tree (#48, completed in #53/#54)

### JSON output (#32) — DONE
- [x] `--json` flag on all commands — structured output for programmatic consumption; eliminates fragile text parsing by agent consumers

### Annotation search (#32) — DONE
- [x] `scalex annotated <annotation>` — find symbols with a specific annotation (e.g. `@deprecated`, `@tailrec`)
- [x] Combine with `--path`, `--no-tests`, and `--kind` filters
- [x] Extract annotations from Scalameta AST during indexing; stored in binary index (v5)

### Body search (#32) — DONE
- [x] `scalex grep <pattern>` — regex search inside file contents, combining scalex's file-filtering intelligence (`--path`, `--no-tests`) with content search
- [x] 20s timeout, sorted output, `-C N` context lines support
- [x] Eliminates the #1 reason agents fall back to grep for Scala files

### Grep improvements for AI agents (#35) — DONE

Feedback from real AI agent usage — `grep` is the most-used subcommand but has the most friction.

**Hint on bad regex syntax:**
- [x] Detect common regex mistakes (`\|`, `\(`, `\)`) when grep returns zero results — emit hint: "scalex uses Java regex (use `|` not `\|` for alternation)"

**Multi-pattern grep:**
- [x] `-e` flag for multiple patterns in one call — `scalex grep -e "Ystop" -e "stopAfter" --path compiler/src/`; reduces separate process invocations during exploratory search

**Grep in batch mode:**
- [x] `grep` already works in batch mode — batch dispatches via `runCommand()` which handles all commands including grep

**`--count` flag for grep:**
- [x] `scalex grep "pattern" --count` — output match/file count without full results; lets agent triage before committing to reading all output

### POSIX regex auto-correction (#39) — DONE
- [x] Auto-correct common POSIX regex patterns in `grep` (`\|` → `|`, `\(` → `(`, `\)` → `)`) instead of only hinting after empty results
- [x] Print correction note to stderr; JSON output includes `"corrected"` field

### AI agent UX improvements (#43) — DONE

Feedback from heavy AI agent usage — three targeted improvements for large-codebase workflows.

**Prefix/exact match for `search`:** — DONE
- [x] `--prefix` flag — only return symbols whose name starts with the query; eliminates noise from substring/fuzzy matches in large codebases (1300+ results → ~20)
- [x] `--exact` flag — only return symbols with exact name match

**Short alias for `--categorize`:** — DONE
- [x] `-c` short alias for `--categorize` on `refs` — saves tokens in agent workflows

**Condensed "not found" in batch mode:** — DONE
- [x] In batch mode, condense not-found output to a single line instead of multi-line hints; reduce noise when running 5+ queries

### Default categorized refs (#46) — DONE
- [x] `--categorize` is now the default for `refs` — AI agents always used it anyway
- [x] `--flat` flag to opt into the old flat-list behavior
- [x] `--categorize`/`-c` kept as accepted no-ops for backwards compatibility

### Codebase exploration (#48)

Feedback from AI agent usage focused on *understanding unfamiliar codebases* — extending scalex from targeted lookup to codebase comprehension.

**High priority:** — DONE
- [x] `scalex members <Symbol> [--verbose]` — list member declarations (def/val/var/type) inside a class/trait/object body; on-the-fly source parse, NOT stored in index; biggest single improvement for eliminating file reads
- [x] `scalex doc <Symbol>` — extract leading scaladoc comment attached to a symbol; on-the-fly line scan, ~<5ms per file

**Medium priority — noise reduction:** — DONE
- [x] `search --definitions-only` — filter search results to only class/trait/object/enum definitions, excluding vals/defs whose *name* matches; reduces noise on common names
- [x] `refs --category <cat>` — filter categorized refs to a single category (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment); e.g. `refs Signal --category ExtendedBy`

**Lower priority — composite:** — DONE
- [x] `scalex overview` — one-shot architectural summary: top packages by symbol count, most-extended traits/classes; computed from existing in-memory data

### Codebase comprehension (#53, #54)

Feedback from real-world AI-assisted exploration of the Scala 3 compiler (17.7k files) and Airstream library (~240 files). Focus: eliminate round-trips and manual file reads during codebase exploration.

**High priority — biggest round-trip eliminators:** — DONE
- [x] `scalex body <symbol> [--in <class>]` — extract method/val body from source using Scalameta spans; eliminates ~50% of follow-up Read calls (#54)
- [x] `scalex explain <symbol>` — composite one-shot summary: def + doc + members + top-N impl + deps; eliminates 4–5 round-trips per type (#53, #54)
- [x] `scalex hierarchy <symbol>` — full inheritance tree (parents up + children down) from extends clauses; `--up`/`--down` to limit direction; single biggest time sink in exploration (#53, #54, extends #48)
- [x] `scalex overrides <method> --of <trait>` — find all override implementations; compose impl lookup with member-level filtering (#54, extends #29)

**Medium priority — richer exploration:** — DONE
- [x] `scalex members --inherited` — walk extends chain, collect members from each mixed-in trait; show full API surface, not just directly-defined members (#53)
- [x] `scalex overview --architecture` — package dependency flow (from imports), hub types (most-referenced + most-extended), key type relationships (#53)
- [x] `scalex deps <symbol>` — reverse of refs: what does this type *use*? Parse file imports + body refs to other indexed symbols (#53)
- [x] `scalex context <file:line>` — enclosing scope info: walk up Scalameta tree from position → enclosing class, method, package, imports (#54)

**Lower priority — advanced:**
- [x] `scalex ast-pattern <pattern>` — structural AST search with predicates (e.g. `--body-contains`); biggest differentiator vs grep, unique to Scalameta (#54)
- [x] `scalex diff [git-ref]` — changed symbols since a git ref; cheap via OID diffing of two indices (#54)
- [ ] `scalex pattern <name>` — detect common Scala design patterns (F-bounded, typeclass, strategy via implicits) via heuristic AST matching (#53)

### Test awareness (#56)

Feedback from dogfooding scalex on itself — test cases (`test("name") { ... }`) are expression statements, not declarations, so they're invisible to the index. AI agents frequently need to find, list, and read test cases.

**High priority:** — DONE
- [x] `scalex tests [--verbose] [--path PREFIX]` — extract test names from common frameworks: MUnit `test("...")`, ScalaTest `"name" in { }` / `it("...") { }` / `describe("...") { }`, specs2. On-the-fly parse, heuristic pattern matching on `templ.stats` expressions. Returns test name + line + enclosing suite. Biggest gap when using scalex for test navigation.
- [x] `scalex body` for test cases — extend body extraction to find `test("exact name") { ... }` blocks by matching the string literal in the first argument. Agent can do `body "extractBody finds method body" --in ScalexSuite` to read a specific test without opening the file.

**Medium priority:** — DONE
- [x] `scalex coverage <symbol>` — "is this function tested?" shorthand: refs filtered to test files only, with count and file list. Faster than `refs X` followed by manual test-file filtering. Shows test file names + line numbers where the symbol appears.

### Overview hub type filtering (#64) — DONE
- [x] Filter standard library / framework types from `overview --architecture` hub type rankings — `None`, `AnyVal`, `Object` etc. are noise
- [x] Heuristic: exclude types not defined in the indexed codebase from "Most extended" and "Hub types" sections
- [x] Surface the project's own architectural hub types instead

### ~~Multi-workspace / cross-project awareness (#64)~~
- ~~Support indexing additional source roots beyond the current workspace~~ — unnecessary complexity; users can simply instruct the agent to run scalex with `-w` on the target workspace instead

### Symbol disambiguation (#80) — DONE

Feedback from real-world usage exploring the Airstream library (~240 files).

**`explain` should use `def` ranking:**
- [x] Apply the same `sortBy` ranking (class/trait/object/enum > type/given > def/val) in `explain` before selecting the primary symbol — was taking `defs.head` unranked, so `explain Observer` resolved to a `val observer` instead of `trait Observer`

**`hierarchy` cycle-detection fix:**
- [x] Fix `hierarchy --up` and `--down` returning `(none)` — `buildHierarchy` initialized `visited = Set(sym.name)` before calling `walkUp`/`walkDown`, causing them to exit immediately; fixed by passing `Set.empty`

### Profiling & benchmarking
- [x] `--timings` flag — built-in per-phase timing breakdown (git-ls-files, cache-load, oid-compare, parse, index-build, cache-save, bloom-screen, text-search), prints to stderr; works in both JVM and native image
- [x] async-profiler scripts (`profiling/profile.sh`) — CPU/wall/alloc/lock flame graphs via JVM agent, zero code changes
- [x] JFR config (`profiling/scalex.jfc`) — custom Java Flight Recorder settings for GC, allocation, file I/O, and thread contention analysis
- [x] Microbenchmark harness (`src/bench.scala`) — isolated per-function benchmarks with warmup, mean/median/p99/stddev; covers extractSymbols, bloom filter, persistence, search, refs
- [x] Enhanced `bench.sh` — index size reporting, diverse query benchmarks (miss, heavy refs, fuzzy, grep, hierarchy), `--timings` integration, `bench-compare.sh` for regression detection

### Warm-load optimization — DONE

Benchmark data shows every query pays ~770ms baseline just to load+build the index. Actual query logic adds only 28–470ms. The warm-load path (`cache-load` 38% + `index-build` 48%) is the dominant bottleneck.

**High priority:**
- [x] Lazy map building — `lazy val` fields in `WorkspaceIndex` compute derived maps on first access; most commands use only 1–2 maps. Measured: `file` 2.16x, `impl` 2.00x, `packages` 1.86x, `def` 1.31x faster on scala3 (17.7k files)
- [x] ~~Separate bloom storage~~ — **won't do.** Benchmarked: `cache-load` is 231ms without blooms vs 269ms with blooms — only ~38ms difference, not the estimated 100–150ms. The existing `skipBytes` in `IndexPersistence.load` already skips bloom data efficiently; separating into a second file adds sync/versioning complexity for marginal gain

**Medium priority:**
- [ ] Varint encoding — replace fixed 4-byte `writeInt` for string table indices with variable-length encoding (1–3 bytes); most of ~180K indices fit in 2 bytes. Expected: shrink index from 22MB to ~15MB
- [ ] Parallel index-build — partition `indexedFiles` into chunks, build sub-maps in parallel via `parallelStream`, merge. Expected: ~2x speedup on index-build phase (~130ms savings)

**Lower priority:**
- [ ] Memory-mapped I/O — replace `DataInputStream(BufferedInputStream(...))` with `MappedByteBuffer` for cache-load; eliminates kernel→user copy, lets OS page in only needed data

### Warm-load optimization round 2 — TRIED, NO GAIN

Post lazy-maps benchmarks (scala3, 17.7k files, 203K symbols) show two remaining bottlenecks:

| Bottleneck | Time | % of total | Affects |
|---|---|---|---|
| `cache-load` | ~290ms | 45–79% | Every command |
| `build-symbolsByName` | ~230ms | 40% | `def`, `refs`, `imports` |

Cheapest commands (`grep`, `file`) are ~361ms. Most expensive (`def`, `refs`) are ~591–666ms.

**Attempted (v7 format, reverted — no measurable improvement):**
- ~~Blooms at end~~ — moved bloom filters to contiguous section at end of `index.bin`. Non-bloom commands skip ~12MB of blooms (saves ~67ms on cache-load), but adding lowerName strings grew the index from 22MB→24MB, offsetting the savings. Net: +10ms on `file`, -11ms on `def` — within noise
- ~~2-byte indices~~ — plan assumed ~60K string table entries fitting in unsigned short. Actual table has **269K entries** (signatures, imports, parents, annotations all interned). `indexWidth=4` always, dead code on real codebases
- ~~Pre-interned lowercase names~~ — stored `name.toLowerCase` per symbol. Added ~80K extra strings (+2MB index). `build-symbolsByName` unchanged at ~230ms because **`toLowerCase` is not the bottleneck — `groupBy` hash map building is**

Hyperfine v6 vs v7 (7 runs each, warmup 2): `file` 361→371ms, `def` 591→580ms, `impl` 387→395ms, `refs` 666→646ms. All within σ.

**Remaining ideas:**
- [ ] Lazy symbol deserialization — deserialize `IndexedFile.symbols` on demand; `grep`/`file` never access symbols, skip deserialization entirely. The 269K-string readUTF loop is the true cache-load bottleneck
- [ ] Pre-grouped symbol storage — store symbols pre-grouped by `name.toLowerCase` in binary index; skip the `groupBy` over 203K symbols entirely (the hash map building, not toLowerCase, is the ~230ms cost)
- [ ] Memory-mapped I/O — `MappedByteBuffer` instead of `DataInputStream`; eliminates kernel→user copy, OS pages in only needed data

### Exploration & UX improvements (#93, #94, #95, #96)

Feedback from real-world usage on large codebases (1,000+ files) — improving codebase exploration and reducing agent round-trips.

**High priority — round-trip eliminators:** — DONE
- [x] Fuzzy "did you mean?" on not-found (#94) — when `def`/`explain` returns zero results, auto-run fuzzy matching against the index and suggest close matches (packages, symbols); saves a round-trip for both humans and AI agents. Infrastructure already exists in `search` command
- [x] `scalex package <pkg>` command (#95) — list all symbols in a package grouped by kind (trait/class/object/enum/etc.); supports `--verbose`, `--kind`, `--no-tests`; fills gap between `overview` (top packages) and `symbols` (per-file); enables top-down exploration: overview → package → explain

**Medium priority — noise reduction:** — DONE
- [x] `overview --no-tests` filtering (#93) — wire existing `--no-tests` flag into `overview` command; exclude test files from symbol counts, exclude test-only types from "Most extended" / "Hub types" lists; currently test fixtures dominate the architecture view on projects with extensive test suites
- [x] `overview --focus-package <pkg>` (#96) — scope `--architecture` dependency graph to a single package: show direct dependencies + direct dependents; makes architecture output practical for targeted exploration instead of full-project dumps

### Community feedback (#101, #102, #103)

Consolidated feedback from real-world usage on coursier (603 files), Mill (1,415 files), and zinc (809 files). All suggestions are within the Scalameta-only constraint.

**High priority — biggest wins with lowest effort:** — DONE
- [x] Package-qualified symbol lookup (#101) — `scalex def coursier.cache.Cache` resolves by full qualified name; partial qualification also works (e.g. `cache.Cache`); eliminates the most common disambiguation friction
- [x] Extends clause type parameter indexing (#101) — `impl Foo` now finds `class Bar extends SomeMixin[Foo]`; type args in extends clauses are indexed as `typeParamParents`; single-letter type params (T, A, F) are filtered out; index format bumped to v7
- [x] Companion object merging in `explain` (#102, #103) — auto-include companion object/class members when explaining a trait/class (and vice versa); eliminates the most common follow-up query after `explain`
- [x] `explain --expand N` for recursive expansion (#102) — expand implementations N levels deep with cycle detection; `explain Task --expand 2` shows Task + all subtypes' members in one call; eliminates N follow-up explains

**Medium priority — solid improvements:**
- [ ] Smarter refs/search ranking (#101, #102) — weight results by: symbol kind (class/trait > def/val), import count, number of referencing files, distance from definition (same package > distant package); data already in index
- [ ] Lightweight Java file awareness (#103) — regex-based extraction of Java interface/class/method declarations; many Scala projects (zinc, sbt, akka) have Java interfaces at their boundaries; doesn't need full Java parsing
- [ ] `deps --depth N` (#103) — follow dependency chains N levels deep; helps understand initialization order and spot circular dependency risks; low effort extension of existing `deps`
- [ ] `members --verbose` as default (#102) — show parameter types by default; current default omits params making overloads indistinguishable; `--brief` flag for old behavior
- [ ] `refs --strict` exact identifier matching (#101) — `refs Cache --strict` matches only `Cache`, not `FileCache`/`CachePolicy`; opt-in stricter word-boundary matching for short/common names

**Lower priority — ambitious features:**
- [ ] Static call graph from method bodies (#101, #102) — `scalex callgraph <method> --in <Class>`; walk Scalameta AST to extract call sites + type refs; cross-reference with index; not compiler-accurate but useful for understanding execution flow. Must pass the "better than grep" gate
- [ ] `scalex path <A> <B>` — shortest dependency path between two types (#102); BFS over extends + import graph edges; helps understand how distant parts of a codebase connect
- [ ] Module/subproject awareness (#103) — detect `build.sbt`/`build.mill`/`build.sc`, group symbols by module in `overview`; "zinc-core: 396 symbols, zinc-persist: 197 symbols" gives better architectural view than flat package counts
- [ ] `scalex api <package>` / `entrypoints` (#102, #103) — show symbols imported by other packages (the public API surface); cross-reference import data to find what a package exports
- [ ] Call-site vs override distinction in refs (#103) — for method refs, distinguish "calls this" vs "overrides this" vs "passes as value" using surrounding AST context (Apply node = call, def with same name = override)
- [ ] Structural pattern detection (#103) — detect companion+trait (smart constructor), `trait Foo` + `class FooLive` (service pattern), sealed ADTs, factory objects; one-liner annotations like "Analysis: sealed ADT with 1 impl (MAnalysis)". Extends existing `scalex pattern` roadmap item

### Other
- [x] `scalex file <query>` — fuzzy search file names (camelCase-aware, like IntelliJ's "search files")
- [ ] `scalex imports <file>` — show what a file imports (its dependencies)
- [ ] Publish plugin to Claude Code marketplace
