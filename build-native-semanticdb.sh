#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/scalex-sdb}"

# -march=native is only safe for local builds (not portable across machines)
MARCH_FLAG=()
if [ -z "${CI:-}" ]; then
  MARCH_FLAG=(-march=native)
fi

echo "Building scalex-semanticdb native image..."
scala-cli package --native-image \
  "$SCRIPT_DIR/scalex-semanticdb/src/" \
  -o "$OUT" \
  --force \
  -- --no-fallback \
  --exclude-config ".*jline.*" ".*" \
  ${MARCH_FLAG[@]+"${MARCH_FLAG[@]}"}

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
