# Scalex Profiling

Multi-layered profiling tools for identifying performance bottlenecks.

## Layer 1: `--timings` (built-in, works everywhere)

```bash
# Phase breakdown for cold index
rm -rf scala3/.scalex
scala-cli run src/ -- index scala3 --timings

# Phase breakdown for refs query
scala-cli run src/ -- refs scala3 Compiler --timings

# Native image
./scalex index scala3 --timings
```

Output (stderr):
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

## Layer 2: async-profiler (CPU/alloc/lock flame graphs)

```bash
# Install
brew install async-profiler

# CPU flame graph of cold index
./profiling/profile.sh ../scala3

# Wall-clock (includes I/O wait)
./profiling/profile.sh ../scala3 wall

# Allocation hotspots
./profiling/profile.sh ../scala3 alloc

# Lock contention
./profiling/profile.sh ../scala3 lock
```

## Layer 3: JFR (GC/IO/thread analysis)

```bash
# Record
scala-cli run src/ \
  --java-opt "-XX:StartFlightRecording=filename=profiling/scalex.jfr,settings=profiling/scalex.jfc,duration=60s" \
  -- index scala3

# Quick summary
jfr summary profiling/scalex.jfr

# Allocation samples
jfr print --events jdk.ObjectAllocationSample profiling/scalex.jfr | head -100

# GC events
jfr print --events jdk.GarbageCollection profiling/scalex.jfr

# File I/O
jfr print --events jdk.FileRead profiling/scalex.jfr | head -50

# GUI analysis (JDK Mission Control)
open profiling/scalex.jfr
```

## Layer 4: Microbenchmarks

```bash
# Run a specific benchmark
scala-cli run src/bench.scala src/*.scala -- extract-single ../scala3

# All benchmarks
scala-cli run src/bench.scala src/*.scala -- all ../scala3

# Custom iterations
scala-cli run src/bench.scala src/*.scala -- extract-batch ../scala3 --warmup 3 --iterations 10
```

## Native Image Profiling

async-profiler and JFR have limited native image support. Options:

```bash
# macOS Instruments (Time Profiler)
xcrun xctrace record --template "Time Profiler" --launch -- ./scalex index scala3

# --timings works in both JVM and native
./scalex index scala3 --timings
```
