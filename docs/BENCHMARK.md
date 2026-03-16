# Benchmark

Native GraalVM binary, Macbook Pro, Apple Silicon M3 Max, measured March 2026.

Reproducible via `.claude/skills/benchmark/scripts/bench.sh` using [hyperfine](https://github.com/sharkdp/hyperfine) against the [Scala 3 compiler repo](https://github.com/scala/scala3) (17,733 files, cloned with `--depth=1`).

## Test project

| Metric | Value |
|--------|-------|
| Scala files | 17,733 |
| Symbols indexed | 203,077 |
| Packages | 571 |
| Cache size (`.scalex/index.bin`) | 22 MB |
| Parse failures | 1,147 (Scala 2 files without Scala 3 fallback) |

## Indexing (hyperfine, 5 runs)

| Metric | Mean ± σ | Range |
|--------|----------|-------|
| **Cold index** (no cache) | 3.433s ± 0.120s | 3.255–3.581s |
| **Warm index** (fully cached) | 843.8ms ± 25.2ms | 817–883ms |

## Query performance (hyperfine, 5 runs, warm cache)

### Core commands

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `packages` | 769.3ms ± 3.5ms | 764–773ms |
| `hierarchy Phase` | 762.8ms ± 4.1ms | 760–769ms |
| `def Phase` | 779.7ms ± 4.9ms | 774–787ms |
| `search tpd` (fuzzy) | 798.9ms ± 7.8ms | 788–808ms |
| `def Compiler` | 809.6ms ± 19.3ms | 784–828ms |
| `impl Compiler` | 848.7ms ± 40.8ms | 793–890ms |
| `refs-miss` | 843.9ms ± 8.9ms | 834–857ms |
| `imports Compiler` | 883.1ms ± 10.1ms | 869–893ms |
| `search Compiler` | 893.4ms ± 35.2ms | 847–926ms |
| `refs Compiler` | 902.4ms ± 27.7ms | 859–928ms |

### Heavy queries

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `refs Type` (heavy) | 1.132s ± 0.032s | 1.097–1.176s |
| `explain Phase` | 1.140s ± 0.074s | 1.097–1.271s |
| `grep 'override def' --count` | 1.238s ± 0.086s | 1.170–1.339s |

All query times include index deserialization from disk (~770ms baseline). Actual query logic adds 0–470ms depending on command complexity.

## Phase breakdown (`--timings`)

Measured via scala-cli (JVM mode) on the same scala3 repo. JVM timings are higher than native but the proportional breakdown is representative.

### Cold index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 64ms | 1% |
| cache-load | 1ms | 0% |
| parse | 6,650ms | 93% |
| index-build | 258ms | 4% |
| cache-save | 187ms | 3% |
| **total** | **7,161ms** | |

Cold indexing is dominated by Scalameta parsing (93%). Parse runs in parallel via `parallelStream`.

### Warm index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 55ms | 10% |
| cache-load | 202ms | 38% |
| oid-compare | 21ms | 4% |
| parse | 2ms | 0% |
| index-build | 257ms | 48% |
| **total** | **537ms** | |

Warm index is dominated by index-build (48%) and cache-load (38%). No parsing or saving needed.

### Refs query (warm cache)

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 57ms | 9% |
| cache-load | 231ms | 38% |
| oid-compare | 20ms | 3% |
| index-build | 251ms | 41% |
| bloom-screen | 16ms | 3% |
| text-search | 28ms | 5% |
| **total** | **605ms** | |

For `refs Compiler`, bloom-screen + text-search add only 44ms on top of index loading. The bloom filter screens 17.7K files down to candidates in 16ms.

## Performance history (v1.1.0 → current)

| Metric | v1.1.0 | Current | Change |
|--------|--------|---------|--------|
| Cold index | 3.618s | 3.433s | **-5.1%** |
| Warm index | 1.360s | 843.8ms | **-37.9%** |
| `def` | 1.299s | 809.6ms | **-37.7%** |
| `search` | 1.353s | 893.4ms | **-34.0%** |
| `impl` | 1.292s | 848.7ms | **-34.3%** |
| `refs` | 1.316s | 902.4ms | **-31.4%** |
| `imports` | 1.341s | 883.1ms | **-34.2%** |
| `packages` | 1.300s | 769.3ms | **-40.8%** |

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
