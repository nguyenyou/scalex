#!/usr/bin/env bash
set -euo pipefail

# ── async-profiler flame graph for scalex ────────────────────────────────────
#
# Prerequisites:
#   brew install async-profiler   (macOS)
#   # or download from https://github.com/async-profiler/async-profiler
#
# Usage:
#   ./profiling/profile.sh <workspace> [event]
#
# Events: cpu (default), wall, alloc, lock
#
# Output: profiling/profile-<event>.html (interactive flame graph)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKSPACE="${1:-}"
EVENT="${2:-cpu}"

if [ -z "$WORKSPACE" ]; then
  echo "Usage: $0 <workspace> [cpu|wall|alloc|lock]"
  echo ""
  echo "Examples:"
  echo "  $0 benchmark/scala3          # CPU flame graph of cold index"
  echo "  $0 benchmark/scala3 wall     # Wall-clock (includes I/O wait)"
  echo "  $0 benchmark/scala3 alloc    # Allocation hotspots"
  echo "  $0 benchmark/scala3 lock     # Lock contention (parallelStream)"
  exit 1
fi

# ── Find async-profiler ──────────────────────────────────────────────────────

if [ -n "${AP_HOME:-}" ]; then
  AP_LIB="$AP_HOME/lib/libasyncProfiler.dylib"
elif [ -f /opt/homebrew/opt/async-profiler/lib/libasyncProfiler.dylib ]; then
  AP_LIB="/opt/homebrew/opt/async-profiler/lib/libasyncProfiler.dylib"
elif [ -f /usr/local/opt/async-profiler/lib/libasyncProfiler.dylib ]; then
  AP_LIB="/usr/local/opt/async-profiler/lib/libasyncProfiler.dylib"
elif [ -f /opt/homebrew/opt/async-profiler/lib/libasyncProfiler.so ]; then
  AP_LIB="/opt/homebrew/opt/async-profiler/lib/libasyncProfiler.so"
else
  echo "Error: async-profiler not found."
  echo "Set AP_HOME or install via: brew install async-profiler"
  exit 1
fi

OUTPUT="$SCRIPT_DIR/profile-${EVENT}.html"

echo "Profiling scalex index with event=$EVENT..."
echo "async-profiler: $AP_LIB"
echo "workspace: $WORKSPACE"
echo ""

# Delete cache to profile cold index
rm -rf "$WORKSPACE/.scalex"

scala-cli run "$PROJECT_ROOT/src/" \
  --java-opt "-agentpath:$AP_LIB=start,event=$EVENT,file=$OUTPUT" \
  -- index "$WORKSPACE"

echo ""
echo "Flame graph written to: $OUTPUT"
echo "Open in browser: open $OUTPUT"
