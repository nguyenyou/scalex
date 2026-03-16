#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/scalex}"

echo "Building Scalex native image..."
scala-cli package --native-image \
  "$SCRIPT_DIR/src/" \
  -o "$OUT" \
  --force \
  -- --no-fallback \
  --initialize-at-run-time=com.google.common.hash.Striped64,com.google.common.hash.LongAdder,com.google.common.hash.BloomFilter,com.google.common.hash.BloomFilterStrategies \
  -march=native

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
echo ""
echo "Install: cp $OUT /usr/local/bin/scalex"
