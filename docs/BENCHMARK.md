# Benchmark

Native GraalVM binary, Macbook Pro, Apple Silicon M3 Max, measured March 2026.

Reproducible via `.claude/skills/benchmark/scripts/bench.sh` using [hyperfine](https://github.com/sharkdp/hyperfine) against the [Scala 3 compiler repo](https://github.com/scala/scala3) (18,485 files, cloned with `--depth=1`).

## Test project

| Metric | Value |
|--------|-------|
| Files indexed | 18,485 |
| Symbols indexed | 144,211 |
| Packages | 603 |
| Cache size (`.scalex/index.bin`) | 21 MB |
| Parse failures | 1,354 (Scala 2 files without Scala 3 fallback) |

## Indexing (hyperfine, 5 runs)

| Metric | Mean ± σ | Range |
|--------|----------|-------|
| **Cold index** (no cache) | 2.709s ± 0.026s | 2.696–2.755s |
| **Warm index** (fully cached) | 349.3ms ± 4.2ms | 345.8–355.5ms |

## Query performance (hyperfine, 5 runs, warm cache)

### Core commands

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `impl Compiler` | 334.4ms ± 3.3ms | 331.1–340.0ms |
| `packages` | 334.4ms ± 1.4ms | 332.6–336.4ms |
| `search Compiler` (fuzzy) | 406.5ms ± 2.5ms | 403.5–409.9ms |
| `def Compiler` | 479.0ms ± 4.7ms | 474.2–485.0ms |
| `refs Compiler` | 556.1ms ± 2.1ms | 553.4–558.8ms |
| `imports Compiler` | 559.8ms ± 11.6ms | 547.5–572.6ms |
| `refs-miss` | 545.7ms ± 1.8ms | 543.5–548.1ms |
| `def-miss` | 567.0ms ± 2.8ms | 563.2–570.7ms |

### Heavy queries

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `refs Type` (heavy) | 678.8ms ± 4.4ms | 675.4–685.0ms |
| `grep 'override def' --count` | 751.4ms ± 11.3ms | 733.6–763.4ms |
| `hierarchy Compiler` | 624.1ms ± 6.2ms | 615.8–633.0ms |
| `explain Compiler` | 810.7ms ± 12.1ms | 790.9–823.4ms |

All query times include index deserialization from disk (~295ms baseline). Actual query logic adds 0–520ms depending on command complexity.

## Phase breakdown (`--timings`)

Measured via native binary on the same scala3 repo.

### Cold index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 41ms | 2% |
| cache-load | 0ms | 0% |
| parse | 2,328ms | 90% |
| cache-save | 200ms | 8% |
| build-allSymbols | 8ms | 0% |
| build-packages | 8ms | 0% |
| **total** | **2,584ms** | |

Cold indexing is dominated by Scalameta parsing (89%). Parse runs in parallel via `parallelStream`.

### Warm index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 46ms | 14% |
| cache-load | 226ms | 68% |
| oid-compare | 32ms | 10% |
| parse | 0ms | 0% |
| build-allSymbols | 15ms | 4% |
| build-packages | 12ms | 3% |
| **total** | **329ms** | |

Warm index is dominated by cache-load (73%). No parsing or saving needed.

### Refs query (warm cache)

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 40ms | 7% |
| cache-load | 295ms | 52% |
| oid-compare | 32ms | 6% |
| build-symbolsByName | 165ms | 29% |
| bloom-screen | 8ms | 1% |
| text-search | 7ms | 1% |
| build-indexedByPath | 5ms | 1% |
| **total** | **569ms** | |

For `refs Compiler`, bloom-screen + text-search add only 15ms on top of index loading. The bloom filter screens 18.5K files down to candidates in 8ms.

## Performance history (v1.1.0 → current)

| Metric | v1.1.0 | Current | Change |
|--------|--------|---------|--------|
| Cold index | 3.618s | 2.709s | **-25.1%** |
| Warm index | 1.360s | 349.3ms | **-74.3%** |
| `def` | 1.299s | 479.0ms | **-63.1%** |
| `search` | 1.353s | 406.5ms | **-70.0%** |
| `impl` | 1.292s | 334.4ms | **-74.1%** |
| `refs` | 1.316s | 556.1ms | **-57.7%** |
| `imports` | 1.341s | 559.8ms | **-58.3%** |
| `packages` | 1.300s | 334.4ms | **-74.3%** |

Optimizations applied:
1. **Lazy bloom filter deserialization** — non-bloom commands skip deserializing blooms, cutting ~45% off index load time
2. **Skip save when nothing changed** — warm index no longer writes 22MB back to disk when all files hit OID cache
3. **Eliminate double file read** — cold index reads each file once instead of twice
4. **Single-pass index building** — symbol/file maps built in 2 passes instead of 7
5. **Pre-computed search deduplication** — `distinctBy` computed once at index time
6. **Adaptive bloom capacity** — bloom filter size scales with file size

## Profiling tools

| Layer | Tool | Use case |
|-------|------|----------|
| `--timings` | Built-in flag | Per-phase breakdown (works in native + JVM) |
| hyperfine | `bench.sh` | Reproducible before/after comparison |
| async-profiler | `profiling/profile.sh` | CPU/alloc/lock flame graphs (JVM only) |
| JFR | `profiling/scalex.jfc` | GC, file I/O, thread analysis (JVM only) |
| Microbenchmarks | `src/bench.scala` | Per-function isolation with statistics |

See `profiling/README.md` for usage details.
