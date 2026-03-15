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
| **Cold index** (no cache) | 3.304s ± 0.290s | 4.6s |
| **Warm index** (fully cached) | 776.9ms ± 22.5ms | — |

Cold index time correlates with symbol count, not file count — the enterprise repo has fewer files but more symbols (214K vs 203K) and takes longer.

## Query performance — Scala 3 compiler (hyperfine, 5 runs, warm cache)

| Command | Mean ± σ | Range |
|---|---|---|
| `impl Compiler` | 728.5ms ± 11.1ms | 714–745ms |
| `packages` | 730.3ms ± 1.4ms | 729–733ms |
| `def Compiler` | 739.4ms ± 18.2ms | 727–771ms |
| `search Compiler` | 753.6ms ± 7.0ms | 744–763ms |
| `refs Compiler` | 807.6ms ± 7.2ms | 800–814ms |
| `imports Compiler` | 877.1ms ± 7.6ms | 872–891ms |

All query times include index deserialization from disk. Actual query logic runs in single-digit milliseconds.

## Performance improvements (v1.1.0 → current)

| Metric | Before | After | Change |
|---|---|---|---|
| Cold index | 3.618s | 3.304s | **-8.7%** |
| Warm index | 1.360s | 776.9ms | **-42.9%** |
| `def` | 1.299s | 739.4ms | **-43.1%** |
| `search` | 1.353s | 753.6ms | **-44.3%** |
| `impl` | 1.292s | 728.5ms | **-43.6%** |
| `refs` | 1.316s | 807.6ms | **-38.6%** |
| `imports` | 1.341s | 877.1ms | **-34.6%** |
| `packages` | 1.300s | 730.3ms | **-43.8%** |

Optimizations applied:
1. **Lazy bloom filter deserialization** — non-bloom commands (`def`, `search`, `impl`, `symbols`, `packages`) skip deserializing blooms entirely, cutting ~45% off index load time
2. **Skip save when nothing changed** — warm index no longer writes 22MB back to disk when all files hit OID cache
3. **Eliminate double file read** — cold index reads each file once instead of twice (bloom filter + symbol extraction)
4. **Single-pass index building** — symbol/file maps built in 2 passes instead of 7
5. **Pre-computed search deduplication** — `distinctBy` computed once at index time
6. **Adaptive bloom capacity** — bloom filter size scales with file size, reducing false positives

## Observations

- **Lazy bloom deserialization is the biggest win**: Non-bloom commands dropped from ~1.3s to ~730ms. Bloom deserialization was consuming ~45% of index load time.
- **Warm index skip-save halves warm index time**: From 1.36s to 777ms by skipping the unnecessary 22MB write.
- **Bloom commands add ~80–150ms**: `refs` and `imports` add overhead from bloom deserialization + file scanning, but are still ~40% faster than before.
- **Cold indexing improved ~9%**: Eliminating the double file read shaves ~300ms off cold index.
