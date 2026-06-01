#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$SCRIPT_DIR/scalex}"

echo "Building Scalex native image..."
"$SCRIPT_DIR/mill" --no-daemon nativeCopy --dest "$OUT"

echo ""
echo "Built: $OUT"
ls -lh "$OUT"
