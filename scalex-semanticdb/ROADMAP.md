# scalex-semanticdb Roadmap

## Bugs (found via scalex comparison)

- [x] **Entrypoints false positives** — `extends App` check matches any parent containing "App" (ZIOApp, ScalaJSApp, etc.). Tighten to exact `scala/App#` FQN. *(Fixed: 117 → 17 results on production monorepo)*
- [x] **Imports heuristic too conservative** — line < 15 threshold misses imports on later lines. *(Fixed: use first definition line as boundary instead of hardcoded 15. 8 → 17 results for AuthRoutes)*

## Performance

- [ ] **Faster warm load** — 1.5s for 2M symbols. Investigate memory-mapped I/O for `semanticdb.bin` instead of `DataInputStream`
- [x] **Incremental index updates** — use `TextDocument.md5` to skip unchanged files on rebuild instead of full re-index
- [ ] **Parallel index save** — save phase is 3.5s, could be improved with buffered writes or parallel serialization
- [ ] **Lazy index maps** — some maps (overrideIndex, symbolsByPackage) are only needed for specific commands. Already lazy, but could defer even more.

## Precision improvements

- [ ] **Overload disambiguation in output** — when multiple overloads match, show parameter types to distinguish them
- [ ] **Access modifier filtering for `api`** — SemanticDB has PrivateAccess/ProtectedAccess fields. Use them to exclude truly private symbols instead of just filtering by kind.
- [ ] **Synthetic tracking** — SemanticDB synthetics contain implicit/given resolution. Could power an `implicits <file:line>` command showing what the compiler inferred.
- [ ] **`doc` from SemanticDB** — `SymbolInformation.documentation` field exists but is often empty. Works when compiler emits it.

## Plugin / UX

- [x] **Disambiguation hints** — `resolveOne` prints candidates with FQN + kind to stderr when multiple symbols match. Applied to 7 single-symbol commands. [#297](https://github.com/nguyenyou/scalex/issues/297)
- [ ] **`--count` flag for refs** — show category counts without listing all occurrences
- [ ] **`--top N` flag for refs** — rank files by reference count
- [ ] **`--no-stdlib` flag** — filter out `scala/`, `java/` symbols from results globally
- [ ] **`--expect` on `members`**
- [ ] **`--max-output N`** — truncate output at N characters like scalex's budget system
- [ ] **`--path` / `--exclude-path`** — filter results by file path prefix

## Feature requests

- [#284](https://github.com/nguyenyou/scalex/issues/284) — `batch` mode (other requests — `exists`, `--fields-only`, `--expect`, string literals — either agent-side or handled by scalex)
- [#297](https://github.com/nguyenyou/scalex/issues/297) — Feature suggestions from real-world usage:
  - [x] **Transitive callers (`callers --depth N`)** — recursive caller traversal reusing `flow`'s depth/cycle infrastructure. Default depth=1 (flat, backward compatible), depth>1 produces FlowTree.
  - [x] **`path` command** — BFS on call graph to find shortest call path between two symbols. Supports `--depth N`, `--smart`, `--exclude`.
  - [x] **`explain` command** — composite of `type` + `callers` + `callees --smart` in one output. Saves agent round-trips.
  - [x] **Incremental index updates** — use `TextDocument.md5` to skip unchanged `.semanticdb` files on rebuild. Auto-staleness via file mtime comparison. *(overlaps with Performance section)*

## Future ideas

- [ ] **Call graph visualization** — `flow` output as mermaid/graphviz/ASCII diagram
- [ ] **Dead code detection** — symbols with 0 references (no occurrences with role=REFERENCE)
- [ ] **Circular dependency detection** — find cycles in the call graph or type dependency graph
- [ ] **API diff between versions** — compare two `.scalex/semanticdb.bin` files to show added/removed/changed symbols
- [ ] **Cross-project references** — index multiple projects and trace dependencies across them (e.g., library → consumer)
