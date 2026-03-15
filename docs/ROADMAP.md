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

- [x] 57 MUnit tests covering all features
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
| Small library | 92 | 259 | ~50ms | ~10ms |
| Mill build tool | 1,415 | 12,778 | 214ms | 50ms |
| Production monorepo | 13,958 | 214,803 | 3.3s | 364ms |
| Scala 3 compiler | 17,731 | 202,916 | 3.1s | 723ms |

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

### Performance improvements
- [ ] Eliminate double file read in `extractSymbols` — `buildBloomFilter` and `extractSymbols` both call `Files.readString`; read once, pass source to both
- [ ] Skip `IndexPersistence.save` when nothing changed — avoid rewriting 22-28MB index when `parsedCount == 0`
- [ ] Lazy bloom filter deserialization — `def`/`search`/`impl`/`symbols`/`packages` don't need blooms; skip deserializing them to cut ~40-50% off load time
- [ ] Pre-compute search deduplication — `search` runs `distinctBy` over all 200K+ symbols on every call; compute once at index time
- [ ] Adaptive bloom filter capacity — hardcoded at 500 expected insertions; large files exceed this, raising false positive rate for `refs`/`imports`
- [ ] Single-pass post-index map building — build `filesByPath`, `symbolsByName`, `parentIndex`, etc. in one loop instead of 7 separate passes

### Other
- [ ] `scalex imports <file>` — show what a file imports (its dependencies)
- [ ] `scalex hierarchy <class>` — show full class hierarchy (parents + children)
- [ ] Publish plugin to Claude Code marketplace
