#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

SCALEX_BIN="${SCALEX_BIN:-$PROJECT_ROOT/scalex}"
SCALA3_DIR="$PROJECT_ROOT/scala3"
BENCH_RUNS="${BENCH_RUNS:-5}"
BENCH_EXPORT="${BENCH_EXPORT:-}"
MODE="${1:-all}"

# ── Validate prerequisites ──────────────────────────────────────────────────

if ! command -v hyperfine &>/dev/null; then
  echo "Error: hyperfine not found. Install with: brew install hyperfine"
  exit 1
fi

if [ ! -x "$SCALEX_BIN" ]; then
  echo "Error: scalex binary not found at $SCALEX_BIN"
  echo "Build it with: ./build-native.sh"
  exit 1
fi

# ── Clone scala3 repo if needed ─────────────────────────────────────────────

if [ ! -d "$SCALA3_DIR" ]; then
  echo "Cloning scala3 repo (depth=1)..."
  git clone --depth=1 https://github.com/scala/scala3.git "$SCALA3_DIR"
  echo ""
fi

FILE_COUNT=$(git -C "$SCALA3_DIR" ls-files '*.scala' | wc -l | tr -d ' ')
echo "scala3 repo: $FILE_COUNT .scala files"
echo "scalex binary: $SCALEX_BIN"
echo "runs per benchmark: $BENCH_RUNS"
echo ""

# ── Hyperfine export flags ──────────────────────────────────────────────────

EXPORT_FLAGS=()
if [ -n "$BENCH_EXPORT" ]; then
  mkdir -p "$(dirname "$BENCH_EXPORT")"
  EXPORT_FLAGS=(--export-json "$BENCH_EXPORT")
fi

# ── Index size ──────────────────────────────────────────────────────────────

report_index_size() {
  local idx="$SCALA3_DIR/.scalex/index.bin"
  if [ -f "$idx" ]; then
    local size
    size=$(stat -f%z "$idx" 2>/dev/null || stat -c%s "$idx" 2>/dev/null || echo "?")
    local size_mb
    size_mb=$(echo "scale=1; $size / 1048576" | bc 2>/dev/null || echo "?")
    echo "Index size: ${size_mb} MB ($size bytes)"
    echo ""
  fi
}

# ── Cold index ──────────────────────────────────────────────────────────────

run_cold() {
  echo "=== Cold Index (no cache) ==="
  echo ""
  hyperfine \
    --warmup 0 \
    --runs "$BENCH_RUNS" \
    --prepare "rm -rf $SCALA3_DIR/.scalex" \
    "$SCALEX_BIN index $SCALA3_DIR" \
    "${EXPORT_FLAGS[@]}"
  report_index_size
}

# ── Warm index ──────────────────────────────────────────────────────────────

run_warm() {
  echo "=== Warm Index (fully cached) ==="
  echo ""
  # Ensure cache exists
  "$SCALEX_BIN" index "$SCALA3_DIR" >/dev/null 2>&1
  hyperfine \
    --warmup 1 \
    --runs "$BENCH_RUNS" \
    "$SCALEX_BIN index $SCALA3_DIR" \
    "${EXPORT_FLAGS[@]}"
  echo ""
}

# ── Query benchmarks ────────────────────────────────────────────────────────

run_query() {
  echo "=== Query Performance (warm cache) ==="
  echo ""
  # Ensure cache exists
  "$SCALEX_BIN" index "$SCALA3_DIR" >/dev/null 2>&1
  hyperfine \
    --warmup 1 \
    --runs "$BENCH_RUNS" \
    --command-name "def" "$SCALEX_BIN def $SCALA3_DIR Compiler" \
    --command-name "search" "$SCALEX_BIN search $SCALA3_DIR Compiler" \
    --command-name "impl" "$SCALEX_BIN impl $SCALA3_DIR Compiler" \
    --command-name "refs" "$SCALEX_BIN refs $SCALA3_DIR Compiler" \
    --command-name "imports" "$SCALEX_BIN imports $SCALA3_DIR Compiler" \
    --command-name "packages" "$SCALEX_BIN packages $SCALA3_DIR" \
    "${EXPORT_FLAGS[@]}"
  echo ""
}

# ── Diverse query benchmarks ────────────────────────────────────────────────

run_query_diverse() {
  echo "=== Diverse Queries (warm cache) ==="
  echo ""
  "$SCALEX_BIN" index "$SCALA3_DIR" >/dev/null 2>&1
  hyperfine \
    --warmup 1 \
    --runs "$BENCH_RUNS" \
    --command-name "def-common"      "$SCALEX_BIN def $SCALA3_DIR Phase" \
    --command-name "def-miss"        "$SCALEX_BIN def $SCALA3_DIR NonExistentSymbolXyz123" \
    --command-name "refs-heavy"      "$SCALEX_BIN refs $SCALA3_DIR Type" \
    --command-name "refs-miss"       "$SCALEX_BIN refs $SCALA3_DIR NonExistentSymbolXyz123" \
    --command-name "search-fuzzy"    "$SCALEX_BIN search $SCALA3_DIR tpd" \
    --command-name "grep"            "$SCALEX_BIN grep $SCALA3_DIR 'override def' --count" \
    --command-name "hierarchy"       "$SCALEX_BIN hierarchy $SCALA3_DIR Phase" \
    --command-name "explain"         "$SCALEX_BIN explain $SCALA3_DIR Phase" \
    "${EXPORT_FLAGS[@]}"
  echo ""
}

# ── Timings breakdown ───────────────────────────────────────────────────────

run_timings() {
  echo "=== Phase Timings ==="
  echo ""
  echo "--- Cold index ---"
  rm -rf "$SCALA3_DIR/.scalex"
  "$SCALEX_BIN" index "$SCALA3_DIR" --timings 2>&1 | grep -E '^\s'
  echo ""
  echo "--- Warm index ---"
  "$SCALEX_BIN" index "$SCALA3_DIR" --timings 2>&1 | grep -E '^\s'
  echo ""
  echo "--- refs Compiler ---"
  "$SCALEX_BIN" refs "$SCALA3_DIR" Compiler --timings 2>&1 | grep -E '^\s'
  echo ""
}

# ── Run selected benchmarks ─────────────────────────────────────────────────

case "$MODE" in
  cold)    run_cold ;;
  warm)    run_warm ;;
  query)   run_query ;;
  diverse) run_query_diverse ;;
  timings) run_timings ;;
  all)
    run_cold
    run_warm
    run_query
    run_query_diverse
    run_timings
    report_index_size
    ;;
  *)
    echo "Usage: $0 [cold|warm|query|diverse|timings|all]"
    exit 1
    ;;
esac

echo "Done."
