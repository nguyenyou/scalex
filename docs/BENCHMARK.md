# Benchmark

Native GraalVM binary, Macbook Pro, Apple Silicon M3 Max, measured March 2026.

## Test projects

| | **Project A** | **Project B** |
|---|---|---|
| Description | Compiler / language toolchain | Large enterprise monorepo |
| Scala files | 17,731 | 13,970 |
| Symbols indexed | 202,916 | 214,154 |
| Cache size (`.scalex/index.bin`) | 22 MB | 28 MB |

## Indexing

| | Project A (17.7K files) | Project B (14K files) |
|---|---|---|
| **Cold index** (no cache) | 3.4s | 4.6s |
| **Warm index** (fully cached) | 0.8s | 1.0s |

Cold index time correlates with symbol count, not file count — Project B has fewer files but more symbols (214K vs 203K) and takes longer.

## Query performance (warm cache)

| Command | Project A | Project B |
|---|---|---|
| `search` | ~1.4s | ~1.8s |
| `def` | ~1.3s | ~1.5s |
| `impl` | ~1.1s | ~1.5s |
| `refs` | ~1.3s | ~1.5s |
| `imports` | ~1.3s | ~1.5s |
| `symbols` | ~1.3s | ~1.5s |
| `packages` | ~1.3s | ~1.5s |

All query times include index deserialization from disk. Actual query logic runs in single-digit milliseconds — the ~1.2–1.5s baseline is entirely cache loading overhead.

## Observations

- **Index loading dominates**: Every command pays the same ~1.2–1.5s cost to deserialize the binary cache. Query execution itself is negligible.
- **Bloom filters work**: `refs Symbol` across 17.7K files returns 4,551 matches in 1.3s — bloom filters skip files that don't contain the symbol.
- **Cold indexing scales linearly**: ~3–5s for 14K–18K files. Parallel Scalameta parsing saturates available cores (327–418% CPU).
- **Cache hit is cheap**: Warm index compares git OIDs only — no file reads, no parsing. Sub-second for both projects.
