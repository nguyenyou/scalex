#!/bin/bash
# Snapshot integration tests for kodex.
# Runs every command against a real project index and compares output to golden files.
#
# Usage:
#   ./run.sh                     # run all tests, compare against snapshots
#   ./run.sh --update            # regenerate all snapshots
#   ./run.sh --update <name>     # regenerate one specific snapshot
#
# Environment:
#   KODEX      path to kodex binary     (default: ../../target/release/kodex)
#   PROJECT  path to Mill workspace root (required)
#   IDX        path to kodex.idx        (default: $PROJECT/.scalex/kodex.idx)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SNAP_DIR="$SCRIPT_DIR/snapshots"
KODEX="${KODEX:-$SCRIPT_DIR/../../target/release/kodex}"
if [ -z "${PROJECT:-}" ]; then
    echo -e "${RED}PROJECT not set${NC}"
    echo "Set PROJECT to your Mill workspace root. Example:"
    echo "  PROJECT=/path/to/your/project ./run.sh"
    exit 1
fi
IDX="${IDX:-$PROJECT/.scalex/kodex.idx}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Mode detection
UPDATE=false
UPDATE_TARGET=""
if [ "${1:-}" = "--update" ]; then
    UPDATE=true
    UPDATE_TARGET="${2:-}"
fi

# Verify binary exists
if [ ! -x "$KODEX" ]; then
    echo -e "${RED}kodex binary not found at $KODEX${NC}"
    echo "Run: cd kodex && cargo build --release"
    exit 1
fi

# Verify queries.sh exists
if [ ! -f "$SCRIPT_DIR/queries.sh" ]; then
    echo -e "${RED}queries.sh not found${NC}"
    echo "Copy queries.sh.example to queries.sh and fill in real symbol names."
    exit 1
fi

# Build index if missing
if [ ! -f "$IDX" ]; then
    echo -e "${CYAN}Index not found at $IDX. Building...${NC}"
    "$KODEX" index --root "$PROJECT"
    echo ""
fi

mkdir -p "$SNAP_DIR"

PASS=0
FAIL=0
NEW=0
SKIP=0

# run_test <test_name> <kodex_args...>
# Runs a kodex command, captures stdout, compares against snapshot.
run_test() {
    local name="$1"; shift

    # If --update with a specific target, skip non-matching tests
    if $UPDATE && [ -n "$UPDATE_TARGET" ] && [ "$name" != "$UPDATE_TARGET" ]; then
        SKIP=$((SKIP + 1))
        return
    fi

    local snap_file="$SNAP_DIR/${name}.stdout"

    # Capture stdout only (stderr has disambiguation messages, timings)
    local actual
    actual=$("$KODEX" "$@" --idx "$IDX" 2>/dev/null) || actual="[ERROR: exit code $?]"

    if $UPDATE; then
        printf '%s' "$actual" > "$snap_file"
        echo -e "${YELLOW}UPDATED${NC} $name"
        NEW=$((NEW + 1))
        return
    fi

    if [ ! -f "$snap_file" ]; then
        printf '%s' "$actual" > "$snap_file"
        echo -e "${YELLOW}NEW${NC}     $name (snapshot created)"
        NEW=$((NEW + 1))
        return
    fi

    local expected
    expected=$(cat "$snap_file")

    if [ "$actual" = "$expected" ]; then
        echo -e "${GREEN}PASS${NC}    $name"
        PASS=$((PASS + 1))
    else
        echo -e "${RED}FAIL${NC}    $name"
        diff --color=always <(echo "$expected") <(echo "$actual") | head -30
        echo "        (use --update $name to accept)"
        FAIL=$((FAIL + 1))
    fi
}

echo "kodex snapshot tests"
echo "  binary:  $KODEX"
echo "  project: $PROJECT"
echo "  index:   $IDX"
echo ""

# Source test case definitions
source "$SCRIPT_DIR/queries.sh"

# Summary
echo ""
echo "========================================"
echo -e "Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}, ${YELLOW}$NEW new${NC}, $SKIP skipped"
echo "========================================"
if [ $FAIL -gt 0 ]; then exit 1; fi
