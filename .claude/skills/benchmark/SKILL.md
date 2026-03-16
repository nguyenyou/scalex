---
name: benchmark
description: "Run scalex performance benchmarks, profiling, and timing analysis. Use this skill whenever the user asks to benchmark scalex, measure performance, profile index/query times, compare before/after performance of a change, investigate bottlenecks, or mentions \"benchmark\", \"perf\", \"how fast\", \"timing\", \"hyperfine\", \"profile\", \"flame graph\", \"profiling\", \"--timings\", \"slow\", \"bottleneck\", \"regression\". Also use proactively after implementing performance improvements to verify gains. Covers 5 layers: built-in --timings, hyperfine benchmarks, async-profiler flame graphs, JFR recording, and microbenchmarks."
---

## Overview

Scalex has a multi-layered profiling and benchmarking system. Pick the right layer for the situation:

| Layer | Tool | When to use | Works in native? |
|-------|------|-------------|-------------------|
| 1. `--timings` | Built-in flag | Quick phase breakdown, first look at any perf question | Yes |
| 2. hyperfine | `bench.sh` | Reproducible before/after comparison with statistics | Yes |
| 3. async-profiler | `profiling/profile.sh` | Deep CPU/alloc/lock flame graphs to find hotspots | JVM only |
| 4. JFR | `profiling/scalex.jfc` | GC pressure, file I/O patterns, thread utilization | JVM only |
| 5. Microbenchmarks | `src/bench.scala` | Isolate per-function cost with warmup + statistics | JVM only |

## Decision guide

**"Where is time spent?"** → Start with `--timings` (Layer 1)

**"Is this change faster?"** → Use hyperfine before/after (Layer 2), optionally with `bench-compare.sh`

**"Why is parsing slow?"** → async-profiler CPU flame graph (Layer 3)

**"Why are allocations high?"** → async-profiler alloc or JFR ObjectAllocationSample (Layer 3/4)

**"Is there GC pressure?"** → JFR (Layer 4)

**"How fast is extractSymbols on one file?"** → Microbenchmark (Layer 5)

---

## Layer 1: `--timings` flag

The fastest way to see where time goes. Works in both JVM and native image. Prints to stderr.

```bash
# Cold index phase breakdown
rm -rf benchmark/scala3/.scalex
./scalex index benchmark/scala3 --timings

# Warm index
./scalex index benchmark/scala3 --timings

# Query with bloom/text-search breakdown
./scalex refs benchmark/scala3 Compiler --timings

# JVM mode
scala-cli run src/ -- index benchmark/scala3 --timings
```

### Phases reported

**Index phases:** `git-ls-files`, `cache-load`, `oid-compare`, `parse`, `index-build`, `cache-save`

**Query phases** (refs/imports/coverage): `bloom-screen`, `text-search`

### Reading the output

```
Timings:
  git-ls-files          12.3 ms  ( 1%)
  cache-load            45.2 ms  ( 5%)
  oid-compare            3.1 ms  ( 0%)
  parse                782.0 ms  (80%)
  index-build           89.4 ms  ( 9%)
  cache-save            42.1 ms  ( 4%)
  total                974.1 ms
```

- **parse > 70%**: Scalameta parsing dominates — look at parallelism, parser options, or reducing parsed file count
- **cache-load > 20%**: Index deserialization — check bloom skip, file size, buffering
- **cache-save > 10%**: Index serialization — check if save is running unnecessarily (when `parsedCount == 0`)
- **bloom-screen > text-search**: Bloom filter is slow — check expected element count, FPP
- **text-search >> bloom-screen**: Text scanning dominates — check candidate count (bloom too permissive?)

---

## Layer 2: hyperfine benchmarks (`bench.sh`)

Reproducible, statistical benchmarks using [hyperfine](https://github.com/sharkdp/hyperfine) against the Scala 3 compiler repo (~17.7K files).

### Prerequisites

- `hyperfine` installed (`brew install hyperfine`)
- Native scalex binary built (`./build-native.sh`)
- The scala3 repo is cloned automatically on first run

### Running

```bash
# Full suite (cold + warm + query + diverse + timings)
.claude/skills/benchmark/scripts/bench.sh

# Individual modes
.claude/skills/benchmark/scripts/bench.sh cold
.claude/skills/benchmark/scripts/bench.sh warm
.claude/skills/benchmark/scripts/bench.sh query
.claude/skills/benchmark/scripts/bench.sh diverse    # miss, heavy refs, fuzzy, grep, hierarchy
.claude/skills/benchmark/scripts/bench.sh timings    # --timings output for cold/warm/refs

# Custom runs/binary
BENCH_RUNS=10 SCALEX_BIN=./target/scalex .claude/skills/benchmark/scripts/bench.sh
```

### Before/after comparison

```bash
# 1. Benchmark current state
BENCH_EXPORT=benchmark/results/before.json .claude/skills/benchmark/scripts/bench.sh

# 2. Make changes, rebuild
./build-native.sh

# 3. Benchmark new state
BENCH_EXPORT=benchmark/results/after.json .claude/skills/benchmark/scripts/bench.sh

# 4. Compare (flags >5% regressions)
.claude/skills/benchmark/scripts/bench-compare.sh benchmark/results/before.json benchmark/results/after.json
```

`bench-compare.sh` exits non-zero if any benchmark regressed >5%.

### Typical ranges (Apple M3 Max)

| Metric | Range | Bottleneck |
|--------|-------|------------|
| Cold index | 3-5s | Scalameta parsing (CPU-bound, parallel) |
| Warm index | 0.8-1.0s | OID compare + index load |
| Query (any) | 1.2-1.5s | Index deserialization from disk |
| refs (heavy symbol) | 2-4s | Text search across candidate files |

---

## Layer 3: async-profiler flame graphs

Reveals call-stack-level CPU hotspots. No code changes needed — JVM agent only.

### Prerequisites

```bash
brew install async-profiler
# Or set AP_HOME to your installation
```

### Running

```bash
# CPU flame graph of cold index
./profiling/profile.sh benchmark/scala3

# Wall-clock (includes I/O wait — useful for parallelStream bottlenecks)
./profiling/profile.sh benchmark/scala3 wall

# Allocation hotspots (where objects are created)
./profiling/profile.sh benchmark/scala3 alloc

# Lock contention (parallelStream synchronization)
./profiling/profile.sh benchmark/scala3 lock
```

Output: `profiling/profile-<event>.html` — open in browser for interactive flame graph.

### What to look for

- **CPU**: Wide bars in Scalameta parsing → specific parser methods. Wide bars in `parallelStream` infrastructure → overhead from small task granularity.
- **Alloc**: Hot allocation sites → potential for object reuse or structural changes.
- **Lock**: Contention in `ConcurrentLinkedQueue.add()` → batch results instead of per-item add.
- **Wall**: I/O wait in `Files.readAllLines` or `Files.readString` → potential for memory-mapped I/O.

---

## Layer 4: JFR (Java Flight Recorder)

Built into JDK 21. Near-zero overhead. Best for GC, file I/O, and thread analysis.

### Running

```bash
# Record with custom config
scala-cli run src/ \
  --java-opt "-XX:StartFlightRecording=filename=profiling/scalex.jfr,settings=profiling/scalex.jfc,duration=60s" \
  -- index benchmark/scala3

# Quick summary
jfr summary profiling/scalex.jfr

# Specific events
jfr print --events jdk.ObjectAllocationSample profiling/scalex.jfr | head -100
jfr print --events jdk.GarbageCollection profiling/scalex.jfr
jfr print --events jdk.FileRead profiling/scalex.jfr | head -50
jfr print --events jdk.ThreadPark profiling/scalex.jfr | head -50

# GUI analysis
open profiling/scalex.jfr  # Opens in JDK Mission Control
```

### Custom config

`profiling/scalex.jfc` is tuned for scalex — it enables allocation sampling, GC events, file I/O (>1ms threshold), and thread parking/monitor events.

---

## Layer 5: Microbenchmarks (`src/bench.scala`)

Isolate per-function costs with warmup and statistical measurement.

### Running

```bash
# Specific benchmark
scala-cli run src/bench.scala src/*.scala -- extract-single benchmark/scala3
scala-cli run src/bench.scala src/*.scala -- bloom-build benchmark/scala3
scala-cli run src/bench.scala src/*.scala -- persistence-load benchmark/scala3
scala-cli run src/bench.scala src/*.scala -- search benchmark/scala3
scala-cli run src/bench.scala src/*.scala -- refs benchmark/scala3

# All benchmarks
scala-cli run src/bench.scala src/*.scala -- all benchmark/scala3

# Custom warmup/iterations
scala-cli run src/bench.scala src/*.scala -- extract-single benchmark/scala3 --warmup 3 --iterations 10
```

### Available benchmarks

| Benchmark | What it measures |
|-----------|-----------------|
| `extract-single` | `extractSymbols` on the largest file |
| `extract-batch` | `extractSymbols` on 100 files (sequential AND parallel) |
| `bloom-build` | `buildBloomFilterFromSource` on a large source |
| `persistence-load` | `IndexPersistence.load` with and without bloom deserialization |
| `search` | `WorkspaceIndex.search("Compiler")` warm |
| `refs` | `findReferences("Phase")` warm |
| `index-cold` | Full cold index including map building |

Reports: mean, median, p99, stddev, min, max per benchmark.

---

## Native image profiling

async-profiler and JFR don't work with GraalVM native images. Options:

```bash
# macOS Instruments (Time Profiler)
xcrun xctrace record --template "Time Profiler" --launch -- ./scalex index scala3

# --timings always works
./scalex index scala3 --timings
```

---

## Performance budget (from CLAUDE.md)

When evaluating changes:

- **Index times**: Accept <5% regression
- **Index size**: 0% growth for non-index features, <10% if schema changes
- **Query latency**: No regression
- **Feature gate**: "Is this better than grep, or does it introduce a worst case?"

The `bench-compare.sh` script automates the regression check against these thresholds.

## Typical workflow for a performance change

1. `--timings` to identify which phase to optimize
2. `BENCH_EXPORT=before.json bench.sh` to capture baseline
3. If deeper analysis needed: async-profiler flame graph or JFR
4. Make the change
5. `--timings` to verify phase improvement
6. `BENCH_EXPORT=after.json bench.sh` to capture new numbers
7. `bench-compare.sh before.json after.json` to check for regressions
8. Microbenchmarks if isolating a specific function
