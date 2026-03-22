# scalex-semanticdb Roadmap

## Bugs (found via scalex comparison)

- [x] **Entrypoints false positives** — `extends App` check matches any parent containing "App" (ZIOApp, ScalaJSApp, etc.). Tighten to exact `scala/App#` FQN. *(Fixed: 117 → 17 results on production monorepo)*
- [x] **Imports heuristic too conservative** — line < 15 threshold misses imports on later lines. *(Fixed: use first definition line as boundary instead of hardcoded 15. 8 → 17 results for AuthRoutes)*

## Generated file / symbol noise

On a production monorepo: 3,008 generated files (26% of index), 600K symbols from `out/` URIs.

- [x] **Deduplicate `jsSharedSources.dest/`** — Mill copies shared sources to `out/**/jsSharedSources.dest/`. These are exact duplicates of real source files and pollute lookup results (e.g. `Page` appears twice). Keep only the source version. *(Done: 1,967 files removed, 267K fewer symbols, 35ms cost)*
- [ ] **`--no-generated` flag** — optional filter to exclude files with `out/` URI prefix. Useful for `lookup`, `symbols`, `annotated`, `packages` where generated code inflates results (e.g. `annotated deprecated` returns 790 vs 9 because scalapb generates `@deprecated` on accessors).
- [ ] **Filter compiler synthetics by default** — hide `$lessinit$greater`, `_1`, `_2`, `copy$default$N`, `unapply`, `$anonfun` in `lookup`/`symbols` output unless `--verbose`. These are compiler-generated methods on case classes that clutter results.
- [ ] **Keep protobuf-generated types indexed** — `compileScalaPB.dest/` types are referenced by real Scala code. Don't exclude them, but mark them as generated so they can be filtered optionally.

## Output quality

- [ ] **Show line numbers in default output** — use `definitionRanges` to add `:line` to file paths (currently only shown with `--verbose`)
- [ ] **Show signatures by default** — members/lookup should show type signatures without requiring `--verbose`
- [ ] **Filter `java/lang/Object#` from supertypes** — always present, never useful to show
- [ ] **Package-qualified lookup** — `lookup routing.Page` should disambiguate by package prefix, like scalex's `def routing.Page`
- [ ] **Categorized refs** — scalex categorizes refs (Definition/ExtendedBy/ImportedBy/UsedAsType/Usage/Comment). sdb has the data to do this via occurrence context.
- [ ] **Entrypoints categorization** — group by @main, def main, extends App, test suites like scalex does

## Missing features (possible with SemanticDB)

- [ ] **`search` with fuzzy/camelCase matching** — scalex's `search hms` finds `HttpMessageService`. sdb's `lookup` only does exact/partial name match.
- [x] **`batch` mode** — multiple queries in one invocation, amortize index load
- [ ] **`exists` command**
- [ ] **`--fields-only` on `members`**
- [ ] **`diff <git-ref>`** — compare two index snapshots to show symbol-level changes
- [ ] **`hierarchy` composite** — combine supertypes + subtypes into one tree view like scalex's `hierarchy`
- [ ] **Wildcard import resolution** — scalex finds 28 importers vs sdb's 8 because scalex resolves `import pkg.*`

## Missing features (need source text — cannot do from SemanticDB alone)

- [ ] `grep` — regex content search
- [ ] `body` — extract method/class source body
- [ ] `ast-pattern` — structural AST search

These require reading source files from disk. Could add as optional feature if source files are available alongside `.semanticdb` files.

## Performance

- [ ] **Faster warm load** — 1.5s for 2M symbols. Investigate memory-mapped I/O for `semanticdb.bin` instead of `DataInputStream`
- [ ] **Incremental index updates** — use `TextDocument.md5` to skip unchanged files on rebuild instead of full re-index
- [ ] **Parallel index save** — save phase is 3.5s, could be improved with buffered writes or parallel serialization
- [ ] **Lazy index maps** — some maps (overrideIndex, symbolsByPackage) are only needed for specific commands. Already lazy, but could defer even more.

## Precision improvements

- [ ] **Overload disambiguation in output** — when multiple overloads match, show parameter types to distinguish them
- [ ] **Access modifier filtering for `api`** — SemanticDB has PrivateAccess/ProtectedAccess fields. Use them to exclude truly private symbols instead of just filtering by kind.
- [ ] **Synthetic tracking** — SemanticDB synthetics contain implicit/given resolution. Could power an `implicits <file:line>` command showing what the compiler inferred.
- [ ] **`doc` from SemanticDB** — `SymbolInformation.documentation` field exists but is often empty. Works when compiler emits it.

## Plugin / UX

- [ ] **Disambiguation hints** — when multiple symbols match, show ready-to-run commands with FQN like scalex does
- [ ] **`--count` flag for refs** — show category counts without listing all occurrences
- [ ] **`--top N` flag for refs** — rank files by reference count
- [ ] **`--no-stdlib` flag** — filter out `scala/`, `java/` symbols from results globally
- [ ] **`--expect` on `members`**
- [ ] **`--max-output N`** — truncate output at N characters like scalex's budget system
- [ ] **`--path` / `--exclude-path`** — filter results by file path prefix

## Feature requests

- [#284](https://github.com/nguyenyou/scalex/issues/284) — `batch` mode (other requests — `exists`, `--fields-only`, `--expect`, string literals — either agent-side or handled by scalex)

## Future ideas

- [ ] **Call graph visualization** — `flow` output as mermaid/graphviz/ASCII diagram
- [ ] **Dead code detection** — symbols with 0 references (no occurrences with role=REFERENCE)
- [ ] **Circular dependency detection** — find cycles in the call graph or type dependency graph
- [ ] **API diff between versions** — compare two `.scalex/semanticdb.bin` files to show added/removed/changed symbols
- [ ] **Cross-project references** — index multiple projects and trace dependencies across them (e.g., library → consumer)
