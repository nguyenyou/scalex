---
name: benchmark
description: Run scalex performance benchmarks using hyperfine against the scala3 repo. Use this skill whenever the user asks to benchmark scalex, measure performance, profile index/query times, compare before/after performance of a change, or mentions "benchmark", "perf", "how fast", "timing", "hyperfine". Also use after implementing performance improvements to verify the gains.
---

## Overview

This skill runs reproducible performance benchmarks for scalex using [hyperfine](https://github.com/sharkdp/hyperfine) against the Scala 3 compiler repo (~17.7K files, ~203K symbols). It measures:

- **Cold index**: no cache, full parse of all files
- **Warm index**: fully cached, OID comparison only
- **Query commands**: each command with warm cache (includes index deserialization)

## Prerequisites

- `hyperfine` installed (`brew install hyperfine`)
- Native scalex binary built (`./build-native.sh`)
- The scala3 repo is cloned automatically on first run

## Running benchmarks

Use the bundled script. From the scalex project root:

```bash
.claude/skills/benchmark/scripts/bench.sh
```

### Options

```bash
# Full suite (default)
./scripts/bench.sh

# Only cold index
./scripts/bench.sh cold

# Only warm index
./scripts/bench.sh warm

# Only query benchmarks
./scripts/bench.sh query

# Custom scalex binary path
SCALEX_BIN=./target/scalex ./scripts/bench.sh

# Custom number of runs
BENCH_RUNS=10 ./scripts/bench.sh

# Export JSON results (for comparison)
BENCH_EXPORT=benchmark-results/before.json ./scripts/bench.sh
```

### Comparing before/after

When benchmarking a performance change:

1. Build the native binary from main: `./build-native.sh`
2. Run: `BENCH_EXPORT=benchmark-results/before.json .claude/skills/benchmark/scripts/bench.sh`
3. Apply changes, rebuild: `./build-native.sh`
4. Run: `BENCH_EXPORT=benchmark-results/after.json .claude/skills/benchmark/scripts/bench.sh`
5. Compare the JSON files or use `hyperfine`'s `--export-markdown` for side-by-side

### Interpreting results

From prior benchmarks on Apple M3 Max:

| Metric | Typical range | What dominates |
|---|---|---|
| Cold index | 3-5s | Scalameta parsing (CPU-bound, parallel) |
| Warm index | 0.8-1.0s | OID comparison + index save |
| Query (any) | 1.2-1.5s | Index deserialization from disk |

If warm index is slow, check if `IndexPersistence.save` is running unnecessarily.
If all queries have similar times, the bottleneck is deserialization, not query logic.

## Updating BENCHMARK.md

After running benchmarks, update `docs/BENCHMARK.md` with the new numbers if they've changed significantly. Include the hardware, date, and both project sizes.
