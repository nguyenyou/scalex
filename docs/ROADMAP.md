# Scalex — Roadmap

## Pending

- [ ] Publish plugin to Claude Code marketplace

### sdbex: trait-aware callers & group-by-file (#331)

- [x] Trait-aware callers — when querying callers of an impl method, automatically include callers of the overridden trait/abstract method; uses `overriddenSymbols` to find parent methods, unions their callers; default behavior (no flag needed)
- [x] Transitive trait-aware callers — `--depth N` walks through trait indirection automatically
- [x] `callers --group-by-file` — group caller output by source file for readability

### sdbex: daemon --fifo for non-interactive shells (#317)

- [x] `--fifo <path>` flag — daemon reads from named pipe instead of stdin; solves immediate exit when backgrounding with `&` in non-interactive shells
- [x] Document `coproc` workaround in SKILL.md for shells that prefer keeping bidirectional pipes

### Community feedback: output budgets & package-scoped refs (#252)

- [x] `--max-output N` global output budget — truncate any command's output at N characters with pagination hint; wraps `render()` centrally via `BudgetPrintStream` in `runCommand`; also serves as per-query budget in `batch` mode
- [x] `--in-package PKG` — filter symbols and references to files whose package declaration matches prefix; applied in `filterSymbols` and `filterRefs`; cheaper than `--path` for cross-compiled projects where package != directory
- Deferred: `--format agent` compact output — maintenance burden of dual formatters across 25+ renderers; `--max-output` + existing `--brief`/`--shallow`/`--no-doc` flags cover the same need
- Discarded: stale symbol hints in `explain` — false positives (cross-repo impls, macros) waste more agent tokens than they save; agent can run `impl` + `refs` directly
- Deferred: `members --diff <ref>` — medium effort, niche; `diff` + `git diff` already cover this well

### Scoped body grep per method (#253)

- [x] `grep --in <Type> --each-method` — iterate members of a type, grep each method body for the pattern, report which methods matched with counts; combines `extractMembers` + `extractBody` + regex match in a single command

### Concise overview mode (#248)

- [x] `overview --concise` — fixed-size summary (~60 lines) regardless of codebase size; compact header, inline symbols, top packages, dep stats (not full graph), hub types, drill-down hints; implies `--architecture`

### Bug fix: Owner.Member dotted syntax fails for nested classes (#239)

- [x] Owner-qualified fallback in `findDefinition` — resolves `Outer.Inner` by finding owner type, then filtering symbols by same file; fixes `members`, `hierarchy`, `def`, `explain` automatically
- [x] `impl Outer.Inner` — verify owner exists via `findDefinition`, then use simple name for parentIndex lookup; return `Nil` for unknown owners
- [x] `body --in Outer.Inner` — extract simple name from dotted `--in` owner for `extractBody`
- ~~Centralize `resolveDottedMember()`~~ — not needed; `findDefinition` fallback makes it a dead path for type members; still useful for non-type members (defs/vals inside objects)

### Bug fix: explain --related resolves stdlib names to unrelated project types (#228)

- [x] Hybrid `isStdlibType` check: minimal predef set (`Option`, `List`, `Map`, etc.) + package-based filtering via index for non-predef names
- [x] Project-defined types with library names (`Task`, `Stream`, `IO`, etc.) correctly surface; only Scala predef auto-imports and unindexed stdlib types are suppressed

### Community feedback: agent exploration improvements (#221)

- [x] Better hub detection in `overview --architecture` — deprioritize types not defined in the indexed source (java.*, scala.*, scala.collection.*); rank project-defined types higher using index membership check; extends existing `isStdlibParent` filter
- [x] `explain --related` — extract type names from member signatures (param types, return types, field types), cross-reference with index to find definitions, list as "Related types" section; cuts agent exploration round-trips in half
- [x] `package --explain` — composite mode: run `explain --brief` for each definition in the package; batch-style output (definition + top members + impl count per type); single command replaces N sequential `explain` calls

### Graph rendering and parsing

- [x] Port ascii-graphs library to scalex as `graph` command
- [x] `graph --render "A->B, B->C"` — Sugiyama-style layered ASCII/Unicode graph rendering
- [x] `graph --parse` — parse ASCII diagrams from stdin into boxes + edges
- [x] Flags: `--unicode`/`--no-unicode`, `--vertical`/`--horizontal`, `--rounded`, `--double`, `--json`
- [x] Round-trip: render → parse → structured data

### Bug fix: body --in owner file search (#197)

- [x] When `--in Owner` is specified and the symbol is indexed in a different file, also search the owner's file for nested defs

### Community feedback: members pagination (#198)

- [x] `members --limit 0` — show all members without truncation
- [x] `members --offset N` — skip first N members for pagination

### Community feedback: overview dedup & onboarding flow (#192)

- [x] `overview --architecture` dedup — skip "Most extended" section when `--architecture` is active; "Hub types" already covers the same `parentIndex` data with identical sorting; applies to both text and JSON output
- ~~`tour` composite command~~ — nice idea but hard to get right; auto-selecting which types to explain risks wrong choices and bloated output; coding agents already do selective sequential calls (`overview` → pick interesting types → `explain`), which adapts better than a fixed composite

### Community feedback: composite output modes to reduce round trips (#180)

- [x] `members --body` / `explain --body` — inline method bodies into member listings; combine existing `members` extraction with `body` span extraction in the same file pass; `--max-lines N` to skip large bodies
- [x] `body -C N` — context lines above/below the extracted span (line arithmetic around existing span)
- [x] `body --imports` — prepend the file's import block to body output (extract top-level `Import` nodes from same AST)
- [x] `overrides --body` — show each override's body inline; `overrides` already locates symbols + files, reuse `extractBody` on each; `--max-lines N` to cap
- [x] `grep --in <symbol>` — scope grep to a specific class/method body; parse AST to find symbol span, restrict grep to those line ranges; supports `Owner.member` dot syntax
- [ ] Fix `explain` disambiguation to respect `--path` — verify `--path` filters before disambiguation (may already work correctly)
- [ ] `explain --near <file>` — parse file's import block to rank/resolve ambiguous candidates (nice-to-have)

### On-the-fly local definition fallback (#176)

- [ ] When `def` returns 0 results, use bloom filters to find candidate files, parse them on-the-fly, and search for local defs/vals/vars inside method bodies matching the query name
- [ ] Return results marked as "local" with owner chain (package > class > method > local def)
- [ ] Zero index size growth — pays parse cost only on the miss path
- [ ] Performance safeguards: file count limit, timeout consistent with refs/imports (20s)
- [ ] Consider extending to `explain` and `body` commands as well

### Community feedback: agent UX improvements (#164)

- [x] Copy-paste disambiguation commands — when `explain` hits multiple matches, print ready-to-run `scalex explain pkg.Name` commands instead of just a count + generic hint. Saves one round-trip per disambiguation.
- [x] `explain --brief` — condensed output: definition + top 3 members only. No doc, companion, inherited, impls, imports. Pairs with `batch` for lightweight multi-explain.

### Community feedback: fuzzy not-found suggestions (#156)

- [x] `search` reverse-suffix matching — queries like `ScalaJSClassEmitter` suggest `ClassEmitter`
- [x] `explain` suffix auto-resolve — auto-shows best match when query is a superset
- [x] Unified not-found suggestions — `search`, `coverage` now show "Did you mean?" hints
- [x] Extracted `resolvePackage()`/`mkPackageNotFound()` — deduplicated package resolution from `package`/`api`/`summary`

### Community feedback: disambiguation & filtering (#132–#135)

**Bugs:**
- [x] `explain` import refs now filtered by `--path`/`--exclude-path` — were unfiltered before
- [x] `isTestFile` detects root-level `tests/`, `test/`, `testing/` — `startsWith` added alongside `contains`

**Disambiguation & ambiguity (reported in #132, #133, #134):**
- [x] `explain` disambiguation hints — "(N other matches — use package-qualified name or --path to disambiguate)"
- [x] `explain` package fallback — falls back to `summary` when symbol matches a package
- [x] `explain --shallow` — skip implementations and import refs
- [x] `explain --no-doc` — suppress Scaladoc section (#157)

**Overview quality (#132, #133, #135):**
- [x] Hub type casing — PascalCase preserved via `symbolsByName` lookup
- [x] Hub type noise filter — single-char names excluded; sorted by distinct-package count
- [x] Hub type signatures — one-line signature shown inline
- [x] `overview --path` — scopes all overview data to path prefix

**Filtering (#133, #135):**
- [x] `--exclude-path` — negative path filter on all commands via `filterSymbols`/`filterRefs`
- [x] `symbols --summary` — grouped counts by kind

**Output control (#134):**
- [x] Members deduplication — companion members deduplicated against primary in `explain`
- [x] `explain` totalImpls hint — "(showing N of M — use --impl-limit to adjust)"
- [ ] Batch output size estimation — warn or auto-limit when a single query in a batch would produce >100KB output

### Community feedback: exploration & impact analysis (#137)

- [x] `explain --inherited` — merge parent members into explain output, marking provenance; `members --inherited` already exists, expose it in the composite view
- [x] `refs --top N` — rank files by reference count (descending) instead of flat list; heaviest users surface first for impact analysis
- [ ] Directory-level dependency graph — `scalex dir-deps --path src/modules/auth/` aggregates imports by target directory with file counts; more granular than `overview --architecture` package-level graph (deferred — package→directory mapping adds complexity)

### Community feedback: UX & discovery (#141)

- [x] `explain` package fallback warning — stderr message when silently falling back to package summary
- [x] `entrypoints` command — find `@main`, `def main(`, `extends App`, test suites; small effort, useful for onboarding
- [x] Override markers in `members --inherited` — show `[override]` vs `[inherited from Parent]` markers

### Code quality improvements (#148)

- [x] Option bloom filters — `identifierBloom: Option[BloomFilter]` replaces null sentinel; index v8
- [x] Error handling — narrow `Exception` catches to `IOException` for file I/O; stderr logging for parse failures and unreadable files
- [x] Deduplicate extraction — shared `extractRawSymbols` used by both `extractSymbols` and `extractSymbolsFromSource`; fixes missing Given/Extension/Pkg.Object in diff command

### Discarded from #253

- ~~`refs --category CallSite`~~ — split `Usage` into `CallSite` vs `Usage` by checking if symbol appears in function position of `Term.Apply`; text heuristic (`name(` / `name[`) is cheap but noisy for common names (`get`, `apply`, `map`); AST-based detection adds parsing cost per file; same reasoning as discarded call-site extraction (#137) and call-site vs override distinction (#103)

### Discarded from #141

- ~~Approximate call graph (`calls` command)~~ — same reasoning as rejected "Static call graph (#101, #102)"; name-matched call targets produce false positives for common methods (`apply`, `map`, `get`)
- ~~Pipeline/flow command~~ — same reasoning as rejected in #121, #135; BFS without type resolution is fragile
- ~~Co-occurrence analysis~~ — pure text co-occurrence is what grep does; unclear value over `refs --categorize`

### Discarded from #137

- ~~Companion member dedup in `explain`~~ — already shipped in #136
- ~~Call-site extraction~~ — without type resolution, method name matching has high false positives for common names (`get`, `apply`, `map`); grep already does `\.method\(` matching; same reasoning as rejected "Static call graph (#101, #102)" and "Call-site vs override distinction (#103)"

### Discarded from #132–#135

- ~~Java member extraction (#135) [regex-based]~~ — regex-based approach was fragile; replaced with JavaParser AST-based extraction in #167
- ~~Re-exported type ranking (#132)~~ — detecting alias chains requires type resolution (unavailable without build server); name-based heuristics would produce false positives
- ~~Module/subproject awareness (#135)~~ — already discarded in #103; build file detection couples to external conventions
- ~~`flow`/`pipeline` command (#135)~~ — already discarded; BFS without type resolution is fragile via name collisions
- ~~`explain --expand-members` (#133, #134)~~ — `explain --expand N` + `body member --in Type` already covers this workflow; adding selective member expansion has diminishing returns vs composing existing commands
- ~~Cross-project most-imported ranking (#132)~~ — `search` already ranks by import count (shipped in #119)

### Discarded from #121

- ~~`scalex flow <from> <to>`~~ — BFS without type resolution is fragile via name collisions; fails "better than grep" gate (same reasoning as rejected `scalex path` in #102)
- ~~`scalex sealed <trait>`~~ — `hierarchy --down --depth 1` already serves this use case; marginal
- ~~Smarter default limits~~ — auto-summarize is hard to get right universally; better to give users explicit flags (`--members-limit`, `--definitions-only`)

### sdbex: noise filtering for flow/callees/callers

- [x] `--no-accessors` flag — filter val/var field accessors from flow/callees
- [x] `--exclude "p1,p2,..."` flag — filter symbols by FQN or file path from flow/callees/callers
- [x] `--smart` flag — auto-filter infrastructure noise (accessors, generated code, protobuf boilerplate, functional plumbing); in flow, only recurses into same-module callees
- [x] `--kind` narrows symbol resolution in flow/callees/callers (not just output filtering)
- [x] `resolveSymbol` prefers source over generated code in ranking
- [x] `batch` command — run multiple queries in one invocation

### sdbex: agent UX improvements (#303)

- [x] Fix `batch` FQN quoting — strip surrounding quotes in `runBatch()`
- [x] `--in <scope>` flag — scope symbol resolution by owner, FQN, or file without full FQN
- [x] `--exclude-test` flag — filter out test source directories
- [x] `--exclude-pkg "p1,p2,..."` flag — exclude symbols by package prefix
- [x] `--smart` filters effect-system combinators (flatMap, traverse, pure, succeed, etc.)
- [x] `lookup` shows `[object]`/`[class/trait]` annotations for method/field members
- [x] FQN resolution `#`↔`.` fallback with stderr hint

### sdbex: reduce noise in members/lookup output (#307)

- [x] `members` synthetic filtering — hide compiler-generated case class members (`_N`, `copy`, `copy$default$N`, `productElement`, `productPrefix`, `canEqual`, `apply`, `unapply`) by default; show with `--verbose`. Note: `hashCode`/`toString`/`equals` are not filtered because Scala 3 SemanticDB only emits them when user-overridden.
- [x] `--smart` on `members` — consistent with `--smart` on flow/callees/callers; filters synthetic case class methods + val accessors, showing only user-declared members
- [x] `lookup --source-only` — hard-exclude symbols from generated code directories (protobuf, codegen); apply existing `isGeneratedSource()` filter post-resolution; also available via `--smart` on lookup
- [x] `explain` subtypes — add `subtypes: N` line + first 3 names for traits/abstract classes; new field in `ExplainResult`, `findSubtypes()` call, formatter update
- Discarded: SKILL.md daemon nudge — already documented with decision tree (lines 374-382) recommending daemon for 3+ queries and exploratory sessions

### sdbex: daemon mode & Mill-only discovery

- [x] `daemon` command — Unix domain socket server, keeps index hot in memory (<10ms queries vs ~1.5s CLI). Text output identical to CLI mode.
- [x] 8 defensive termination layers: stdin EOF, parent PID monitoring, idle timeout, max lifetime, shutdown command, per-query timeout, heap pressure, shutdown hook
- [x] `--parent-pid PID` flag — daemon auto-exits when parent process dies
- [x] Auto-rebuild on staleness — checks `.semanticdb` directory mtimes before each query (~7ms)
- [x] Mill-only discovery — parallel `semanticDbDataDetailed.dest/data/` walk, ~44% faster
- [x] Removed `--semanticdb-path` flag — Mill's `out/` is the only supported layout
- [x] 7 subprocess-based daemon lifecycle tests (stdin-eof, idle-timeout, max-lifetime, shutdown, heartbeat, query-response, error-recovery)

## Completed

### JavaParser-based Java extraction (#167)

- [x] Replace regex-based Java symbol extraction with JavaParser AST parsing
- [x] Java symbol extraction (classes, interfaces, enums, records, methods, fields)
- [x] Java member extraction (constructors, nested types, enum constants, @Override detection)
- [x] Java body extraction with owner filtering
- [x] Centralized `isJavaFile()` routing — eliminates scattered `.endsWith(".java")` checks

### Extraction fixes (#127)

- Stop indexing local vals/defs inside method bodies — traversal skips `Defn.Def`/`Val`/`Var`/`Given`/`GivenAlias` bodies
- Case class constructor params in `extractMembers` — case class params are public vals; regular class params only if marked `val`/`var`

### Signal vs noise (#121)

- `package --definitions-only` — filter out vals/defs, show only class/trait/object/enum
- `overview` hub types filter stdlib — skip scala/java base types (object, serializable, anyval) to surface real domain hub types
- `explain` members sorted by kind — classes before defs before vals; `--members-limit N` flag (default 10)
- `explain` fuzzy fallback — if exact match fails, auto-show best fuzzy match with stderr hint
- `hierarchy` truncation count — "... and 87 more children" at depth limit instead of silent cut-off
- `scalex summary <package>` — sub-package view with symbol counts; middle ground between `overview` and `package`

### Reduce round-trips (#119, #120)

- `overview` defaults to `--no-tests`; `--include-tests` to opt in
- `Owner.member` dotted syntax for `explain` and `def` — `def MyService.findUser` resolves directly
- `explain --verbose` — show member signatures inline instead of just names
- `explain` inlines "Imported by" file list when count ≤ 10, shows count + hint otherwise
- `refs --count` summary mode — "12 importers, 4 extensions, 30 usages" without full file lists
- Companion-aware `members` — auto-shows companion object/class members
- `api --used-by <package>` — coupling analysis: which types from pkg A are used by pkg B
- `search` ranks by import count — heavily-imported types surface first
- `search --returns <Type>` / `--takes <Type>` — signature substring filter

### Phase 1–5: Foundation

- Git file listing, Scalameta parsing, in-memory index, find definition, find references, CLI
- `.scalex/index.bin` binary persistence with string interning, OID-based skip, auto-reindex
- Search ranking (exact → prefix → substring), `--kind` filter, `--limit N`, quiet indexing
- Claude Code plugin: `plugin.json`, `SKILL.md`, launcher script, install instructions
- GraalVM native image (28MB standalone binary), `build-native.sh`
- Binary format v3, bloom filters (Guava) for refs pre-screening, 20s timeout

### Phase 6: CI + Distribution

- GitHub Actions: native images on tag push (macOS arm64/x64, Linux x64)
- GitHub Releases, `install.sh` in plugin

### Phase 7: Coding Agent Innovations

- `batch`, `def --verbose`, `impl`, `refs --categorize`, `imports`
- Fallback hints, Scala 2 dialect fallback

### Phase 8: Testing

- 243 MUnit tests (ExtractionSuite: 50, IndexSuite: 68, AnalysisSuite: 23, CliSuite: 102)
- Coverage: symbol extraction, persistence roundtrip, OID caching, bloom filters, search ranking, refs categorization, word boundary matching

#### Benchmarks (native image)

| Project | Files | Symbols | Cold Index | Warm Index |
|---|---|---|---|---|
| Production monorepo | 13,979 | 214,301 | 4.6s | 540ms |
| Scala 3 compiler | 18,485 | 144,211 | 2.7s | 349ms |

### Post-v1 features

#### Import confidence & alias tracking
- Confidence annotation (High/Medium/Low) based on imports; wildcard import resolution
- Alias tracking (`import X as Y`, `import {X => Y}`); `[via alias Y]` annotation in output

#### Performance improvements
- Eliminate double file read, skip save when nothing changed
- Lazy bloom filter deserialization (~45% off load time)
- Pre-compute search deduplication, adaptive bloom capacity
- Single-pass post-index map building (2 passes vs 7)

#### Parse error diagnostics
- Track/list files with parse errors via `scalex index --verbose`

#### Fuzzy camelCase search
- "hms" matches `HttpMessageService`; ranked below exact/prefix/substring

#### zsh compat + UX (#22)
- Bash re-exec guard, `-w`/`--workspace` flag, path-as-symbol hint

#### Coding agent ergonomics (#29)
- `--kind` filter on `def`, `--no-tests` global flag, `--path` filter
- `refs -C N` context lines, smarter `def` ranking

#### Annotation search (#32)
- `scalex annotated <annotation>` with filters; annotations stored in binary index (v5)

#### Body search (#32)
- `scalex grep <pattern>` — regex search with `--path`, `--no-tests`, `-C N`, 20s timeout, `--count`
- Multi-pattern `-e` flag, POSIX regex auto-correction (`\|` → `|`)

#### Coding agent UX (#43, #46)
- `--prefix`/`--exact` for search, `-c` alias for `--categorize`, condensed batch not-found
- `--categorize` default on refs, `--flat` opt-in

#### Codebase exploration (#48)
- `members`, `doc`, `search --definitions-only`, `refs --category`, `overview`

#### Codebase comprehension (#53, #54)
- `body`, `explain`, `hierarchy` (parents up + children down), `overrides`
- `members --inherited`, `overview --architecture`, `deps`, `context`
- `ast-pattern`, `diff`

#### Test awareness (#56)
- `tests` (MUnit, ScalaTest, specs2), `body` for test cases, `coverage`
- [x] `tests --count` dynamic test site detection — reports non-literal test names separately (#259)

#### Overview hub type filtering (#64)
- Exclude stdlib/framework types from hub rankings; surface project-own types

#### Symbol disambiguation (#80)
- `explain` uses `def` ranking, `hierarchy` cycle-detection fix

#### Profiling & benchmarking
- `--timings` flag, async-profiler scripts, JFR config, microbenchmark harness, `bench.sh`/`bench-compare.sh`

#### Warm-load optimization
- Lazy map building — 1.3–2.2x faster on scala3 (17.7k files)

#### Exploration & UX (#93–#96)
- Fuzzy "did you mean?" on not-found, `package` command
- `overview --no-tests`, `overview --focus-package`

#### API surface (#102, #103)
- `scalex api <package>` — show which symbols are imported by other packages (public API surface); cross-reference import data with `packageToSymbols`; zero index change, pure in-memory query

#### Community feedback (#101–#103)
- Package-qualified symbol lookup, type param indexing in extends clauses
- Companion merging in `explain`, `explain --expand N`
- Smarter refs/search ranking, lightweight Java file awareness
- `deps --depth N`, `members --verbose` default, `refs --strict`
- `scalex file <query>` — fuzzy camelCase-aware file search

## Discarded & Dead Ends

Items tried or evaluated and rejected — documented here to avoid re-exploring.

### Warm-load optimization attempts

Bottleneck: every query pays ~290ms `cache-load` + ~230ms `build-symbolsByName`. Cheapest commands ~361ms, most expensive ~666ms. Root cause is 269K `readUTF` string constructions — I/O and file size are not the bottleneck.

**Round 1 — discarded after benchmarking:**
- ~~Separate bloom storage~~ — only ~38ms difference; `skipBytes` already efficient
- ~~Varint encoding~~ — smaller file doesn't help CPU-bound string construction
- ~~Parallel index-build~~ — ~130ms savings on a phase lazy maps already skip
- ~~Memory-mapped I/O~~ — bottleneck is string construction; 23MB is OS page-cached

**Round 2 — attempted v7 format, reverted (all results within σ):**
- ~~Blooms at end~~ — skip 12MB saves ~67ms, but lowerName strings grew index 22→24MB, net zero
- ~~2-byte string indices~~ — actual table has 269K entries (need 4 bytes), dead code on real codebases
- ~~Pre-interned lowercase names~~ — +80K strings, +2MB; `groupBy` hash map building is the cost, not `toLowerCase`

Hyperfine v6 vs v7 (7 runs, warmup 2): `file` 361→371ms, `def` 591→580ms, `impl` 387→395ms, `refs` 666→646ms. All within σ.

**Round 2 — remaining ideas, also discarded:**
- ~~Lazy symbol deserialization~~ — custom lazy format, high complexity for a subset of commands
- ~~Pre-grouped symbol storage~~ — similar to pre-interned names which already failed in v7

### Rejected features

- ~~Reverse alias lookup~~ — agent can do 2-step lookup; risks worse-than-grep perf
- ~~Multi-workspace / cross-project awareness (#64)~~ — unnecessary; use `-w` flag on target workspace
- ~~`scalex pattern <name>` (#53)~~ — marginal for coding agents who infer patterns from `explain`
- ~~Static call graph (#101, #102)~~ — name-matched call targets produce false positives (`apply`, `map`, `get`); fails "better than grep" gate; AST traversal risks blowing perf budget
- ~~`scalex path <A> <B>` (#102)~~ — BFS without type resolution is fragile via name collisions; niche use case vs `deps`/`refs`
- ~~Module/subproject awareness (#103)~~ — detecting build files couples to external conventions that change; directory heuristics unreliable across project layouts
- ~~Call-site vs override distinction in refs (#103)~~ — `refs --categorize` already provides ExtendedBy/UsedAsType/Usage categories; marginal improvement for significant complexity
- ~~Structural pattern detection (#103)~~ — marginal for coding agents who infer patterns from `explain` output
- ~~`scalex imports <file>`~~ — `deps` already shows symbol-level dependencies; agent can read file top for imports
