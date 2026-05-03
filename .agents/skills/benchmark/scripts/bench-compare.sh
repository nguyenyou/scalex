#!/usr/bin/env bash
set -euo pipefail

# ── Compare two hyperfine JSON exports and flag regressions ─────────────────
#
# Usage:
#   BENCH_EXPORT=before.json ./bench.sh all
#   # ... make changes ...
#   BENCH_EXPORT=after.json ./bench.sh all
#   ./bench-compare.sh before.json after.json
#
# Flags >5% regression with ⚠️, >10% with 🔴

BEFORE="${1:-}"
AFTER="${2:-}"
THRESHOLD="${3:-5}"

if [ -z "$BEFORE" ] || [ -z "$AFTER" ]; then
  echo "Usage: $0 <before.json> <after.json> [threshold_pct]"
  echo ""
  echo "Compare two hyperfine JSON exports and flag regressions."
  echo "Default threshold: 5%"
  exit 1
fi

if [ ! -f "$BEFORE" ]; then
  echo "Error: $BEFORE not found"
  exit 1
fi

if [ ! -f "$AFTER" ]; then
  echo "Error: $AFTER not found"
  exit 1
fi

if ! command -v jq &>/dev/null; then
  echo "Error: jq not found. Install with: brew install jq"
  exit 1
fi

echo "Comparing: $BEFORE → $AFTER (threshold: ${THRESHOLD}%)"
echo ""

HAS_REGRESSION=0

# Extract benchmark results and compare
BEFORE_COUNT=$(jq '.results | length' "$BEFORE")
AFTER_COUNT=$(jq '.results | length' "$AFTER")

printf "%-20s %12s %12s %10s %s\n" "Benchmark" "Before (ms)" "After (ms)" "Change" "Status"
printf "%-20s %12s %12s %10s %s\n" "---------" "-----------" "----------" "------" "------"

for i in $(seq 0 $((AFTER_COUNT - 1))); do
  NAME=$(jq -r ".results[$i].command" "$AFTER")
  AFTER_MEAN=$(jq ".results[$i].mean" "$AFTER")

  # Find matching benchmark in before file
  BEFORE_MEAN=$(jq -r --arg name "$NAME" '.results[] | select(.command == $name) | .mean' "$BEFORE" 2>/dev/null || echo "")

  if [ -z "$BEFORE_MEAN" ] || [ "$BEFORE_MEAN" = "null" ]; then
    printf "%-20s %12s %12.1f %10s %s\n" "$NAME" "N/A" "$(echo "$AFTER_MEAN * 1000" | bc)" "" "(new)"
    continue
  fi

  BEFORE_MS=$(echo "$BEFORE_MEAN * 1000" | bc)
  AFTER_MS=$(echo "$AFTER_MEAN * 1000" | bc)

  if [ "$(echo "$BEFORE_MEAN > 0" | bc)" = "1" ]; then
    CHANGE_PCT=$(echo "scale=1; ($AFTER_MEAN - $BEFORE_MEAN) / $BEFORE_MEAN * 100" | bc)
    CHANGE_ABS=$(echo "$CHANGE_PCT" | sed 's/^-//')

    STATUS=""
    if [ "$(echo "$CHANGE_PCT > 10" | bc)" = "1" ]; then
      STATUS="REGRESSION"
      HAS_REGRESSION=1
    elif [ "$(echo "$CHANGE_PCT > $THRESHOLD" | bc)" = "1" ]; then
      STATUS="warning"
      HAS_REGRESSION=1
    elif [ "$(echo "$CHANGE_PCT < -$THRESHOLD" | bc)" = "1" ]; then
      STATUS="faster"
    else
      STATUS="ok"
    fi

    printf "%-20s %12.1f %12.1f %+9.1f%% %s\n" "$NAME" "$BEFORE_MS" "$AFTER_MS" "$CHANGE_PCT" "$STATUS"
  fi
done

echo ""
if [ "$HAS_REGRESSION" = "1" ]; then
  echo "Result: REGRESSIONS DETECTED (>${THRESHOLD}%)"
  exit 1
else
  echo "Result: No regressions (all within ${THRESHOLD}%)"
  exit 0
fi
