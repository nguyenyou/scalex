#!/usr/bin/env bash
# Validates SKILL.md frontmatter against known compatibility constraints.
# Checks from PR #92: https://github.com/nguyenyou/scalex/pull/92
#
# 1. description must be double-quoted YAML (unquoted colons break parsers)
# 2. description must be ≤ 1024 characters (GitHub Copilot CLI hard limit)
#
# Usage: ./scripts/check-skill-frontmatter.sh [path/to/SKILL.md]

set -euo pipefail

SKILL_FILE="${1:-plugins/scalex/skills/scalex/SKILL.md}"
MAX_DESC_LEN=1024

if [[ ! -f "$SKILL_FILE" ]]; then
  echo "FAIL: File not found: $SKILL_FILE"
  exit 1
fi

errors=0

# ── Check 1: Frontmatter exists ──────────────────────────────────────────────

if ! head -1 "$SKILL_FILE" | grep -q '^---$'; then
  echo "FAIL: No YAML frontmatter found (file must start with ---)"
  exit 1
fi

# Extract the frontmatter block (between first and second ---)
frontmatter=$(sed -n '2,/^---$/p' "$SKILL_FILE" | sed '$d')

# ── Check 2: description is double-quoted ─────────────────────────────────────

desc_line=$(echo "$frontmatter" | grep '^description:' || true)

if [[ -z "$desc_line" ]]; then
  echo "FAIL: No 'description' field found in frontmatter"
  errors=$((errors + 1))
else
  # Extract the value after "description: "
  desc_value="${desc_line#description: }"

  # Check it starts and ends with double quotes
  if [[ "$desc_value" == \"*\" ]]; then
    echo "PASS: description is double-quoted (valid YAML for all parsers)"
  else
    echo "FAIL: description is NOT double-quoted — colons in unquoted values"
    echo "      cause YAML parse errors in GitHub Copilot CLI and other tools."
    echo "      Fix: wrap the description value in double quotes."
    errors=$((errors + 1))
  fi

  # ── Check 3: description length ≤ 1024 chars ───────────────────────────────

  # Strip surrounding quotes to get the actual content length
  stripped="${desc_value#\"}"
  stripped="${stripped%\"}"
  char_count=${#stripped}

  if [[ $char_count -le $MAX_DESC_LEN ]]; then
    echo "PASS: description length is $char_count chars (limit: $MAX_DESC_LEN)"
  else
    echo "FAIL: description is $char_count chars — exceeds $MAX_DESC_LEN char limit"
    echo "      GitHub Copilot CLI enforces a hard 1024-character limit."
    echo "      Trim $((char_count - MAX_DESC_LEN)) characters to fix."
    errors=$((errors + 1))
  fi
fi

# ── Check 4: name field exists ────────────────────────────────────────────────

name_line=$(echo "$frontmatter" | grep '^name:' || true)

if [[ -z "$name_line" ]]; then
  echo "FAIL: No 'name' field found in frontmatter"
  errors=$((errors + 1))
else
  echo "PASS: name field present"
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
if [[ $errors -eq 0 ]]; then
  echo "All checks passed."
else
  echo "$errors check(s) failed."
  exit 1
fi
