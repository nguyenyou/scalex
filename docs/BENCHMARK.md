# Benchmark

Native GraalVM binary, Macbook Pro, Apple Silicon M3 Max, measured March 2026.

Reproducible via `.claude/skills/benchmark/scripts/bench.sh` using [hyperfine](https://github.com/sharkdp/hyperfine) against the [Scala 3 compiler repo](https://github.com/scala/scala3) (17,733 files, cloned with `--depth=1`).

## Test projects

| | **Scala 3 compiler** | **Enterprise monorepo** |
|---|---|---|
| Scala files | 17,733 | 13,970 |
| Symbols indexed | 202,916 | 214,154 |
| Cache size (`.scalex/index.bin`) | 22 MB | 28 MB |

## Indexing (hyperfine, 5 runs)

| | Scala 3 (17.7K files) | Enterprise (14K files) |
|---|---|---|
| **Cold index** (no cache) | 3.618s ± 0.071s | 4.6s |
| **Warm index** (fully cached) | 1.360s ± 0.027s | 1.0s |

Cold index time correlates with symbol count, not file count — the enterprise repo has fewer files but more symbols (214K vs 203K) and takes longer.

## Query performance — Scala 3 compiler (hyperfine, 5 runs, warm cache)

| Command | Mean ± σ | Range |
|---|---|---|
| `def Compiler` | 1.299s ± 0.007s | 1.288–1.304s |
| `impl Compiler` | 1.292s ± 0.003s | 1.287–1.294s |
| `packages` | 1.300s ± 0.008s | 1.292–1.310s |
| `refs Compiler` | 1.316s ± 0.010s | 1.304–1.327s |
| `imports Compiler` | 1.341s ± 0.005s | 1.333–1.347s |
| `search Compiler` | 1.353s ± 0.048s | 1.269–1.382s |

All query times include full index deserialization from disk. Actual query logic runs in single-digit milliseconds.

## Observations

- **Index deserialization dominates**: All 6 query commands cluster within 1.29–1.35s. The query logic itself is negligible — the entire cost is loading and deserializing the ~22MB binary cache with bloom filters.
- **Warm index pays an unnecessary save cost**: Warm index (1.36s) is ~60ms slower than the fastest query (1.29s). The difference is `IndexPersistence.save` writing 22MB back to disk even when nothing changed (all files hit OID cache).
- **Bloom filters work**: `refs Compiler` and `imports Compiler` add only ~20–40ms over non-bloom commands (`def`, `impl`, `packages`), despite scanning bloom filters across 17.7K files.
- **Cold indexing parallelizes well**: 3.6s wall-clock with 10.5s user time = ~290% CPU utilization across Scalameta parsing.

## Improvement opportunities

These are ordered by estimated impact (see `docs/ROADMAP.md` for tracking):

1. **Lazy bloom filter deserialization** — `def`, `search`, `impl`, `symbols`, `packages` never use bloom filters, yet every command deserializes all ~17.7K of them. Skipping bloom deserialization for non-refs/imports commands could cut the 1.3s baseline by 40–50%.
2. **Skip save when nothing changed** — When `parsedCount == 0` (all files cached), skip the 22MB write. Would bring warm index time closer to query time (~1.3s → ~1.29s).
3. **Eliminate double file read** — During cold index, `buildBloomFilter` and `extractSymbols` both call `Files.readString` on the same file. Reading once and sharing the source string would cut I/O in half for cold indexing.
4. **Adaptive bloom filter capacity** — Bloom filters are created with 500 expected insertions. Large files with 2000+ identifiers exceed this, raising false positive rates and causing `refs`/`imports` to read more files than necessary.
