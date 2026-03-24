#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/sdbex}"

echo "Building scalex-semanticdb assembly JAR..."
scala-cli package --assembly \
  "$SCRIPT_DIR/scalex-semanticdb/src/" \
  -o "$OUT" \
  --force \
  --preamble=true

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
