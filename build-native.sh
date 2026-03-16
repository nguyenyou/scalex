#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/scalex}"

# -march=native is only safe for local builds (not portable across machines)
MARCH_FLAG=()
if [ -z "${CI:-}" ]; then
  MARCH_FLAG=(-march=native)
fi

echo "Building Scalex native image..."
scala-cli package --native-image \
  "$SCRIPT_DIR/src/" \
  -o "$OUT" \
  --force \
  -- --no-fallback \
  --initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies \
  "${MARCH_FLAG[@]}"

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
echo ""
echo "Install: cp $OUT /usr/local/bin/scalex"
