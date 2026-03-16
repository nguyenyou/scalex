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
| **Cold index** (no cache) | 2.950s ± 0.184s | 2.766–3.161s |
| **Warm index** (fully cached) | 419.1ms ± 7.1ms | 410–430ms |

## Query performance (hyperfine, 5 runs, warm cache)

### Core commands

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `impl Compiler` | 386.2ms ± 7.4ms | 381–399ms |
| `packages` | 391.2ms ± 6.1ms | 386–399ms |
| `search tpd` (fuzzy) | 482.6ms ± 2.6ms | 479–486ms |
| `search Compiler` | 483.8ms ± 9.3ms | 476–500ms |
| `def Phase` | 578.8ms ± 6.5ms | 569–586ms |
| `def Compiler` | 572.3ms ± 4.0ms | 567–577ms |
| `refs Compiler` | 639.2ms ± 5.0ms | 632–644ms |
| `imports Compiler` | 640.2ms ± 9.3ms | 633–656ms |
| `refs-miss` | 645.6ms ± 3.4ms | 640–649ms |
| `def-miss` | 704.1ms ± 2.9ms | 700–707ms |

### Heavy queries

| Command | Mean ± σ | Range |
|---------|----------|-------|
| `refs Type` (heavy) | 750.0ms ± 3.8ms | 746–755ms |
| `grep 'override def' --count` | 851.8ms ± 100.1ms | 787–1029ms |

All query times include index deserialization from disk (~380ms baseline). Actual query logic adds 0–470ms depending on command complexity.

## Phase breakdown (`--timings`)

Measured via native binary on the same scala3 repo.

### Cold index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 42ms | 2% |
| cache-load | 0ms | 0% |
| parse | 2,463ms | 89% |
| cache-save | 241ms | 9% |
| build-allSymbols | 9ms | 0% |
| build-packages | 10ms | 0% |
| **total** | **2,766ms** | |

Cold indexing is dominated by Scalameta parsing (89%). Parse runs in parallel via `parallelStream`.

### Warm index

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 40ms | 10% |
| cache-load | 275ms | 73% |
| oid-compare | 30ms | 8% |
| parse | 0ms | 0% |
| build-allSymbols | 20ms | 5% |
| build-packages | 15ms | 4% |
| **total** | **379ms** | |

Warm index is dominated by cache-load (73%). No parsing or saving needed.

### Refs query (warm cache)

| Phase | Time | % |
|-------|------|---|
| git-ls-files | 40ms | 6% |
| cache-load | 330ms | 50% |
| oid-compare | 32ms | 5% |
| build-symbolsByName | 214ms | 33% |
| bloom-screen | 8ms | 1% |
| text-search | 3ms | 1% |
| **total** | **654ms** | |

For `refs Compiler`, bloom-screen + text-search add only 11ms on top of index loading. The bloom filter screens 17.7K files down to candidates in 8ms.

## Performance history (v1.1.0 → current)

| Metric | v1.1.0 | Current | Change |
|--------|--------|---------|--------|
| Cold index | 3.618s | 2.950s | **-18.5%** |
| Warm index | 1.360s | 419.1ms | **-69.2%** |
| `def` | 1.299s | 572.3ms | **-56.0%** |
| `search` | 1.353s | 483.8ms | **-64.2%** |
| `impl` | 1.292s | 386.2ms | **-70.1%** |
| `refs` | 1.316s | 639.2ms | **-51.4%** |
| `imports` | 1.341s | 640.2ms | **-52.3%** |
| `packages` | 1.300s | 391.2ms | **-69.9%** |

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
