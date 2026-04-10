#!/bin/bash
#
# Baseline comparison for CI enforcement gates.
#
# Usage:
#   scripts/baseline-diff.sh [--update]
#
# Without --update: compares the current `re-scale enforce shortcuts
# --machine-readable` output against a stored baseline in
# `.rescale/data/shortcuts-baseline.tsv`. Exits 0 if no new hits were
# introduced (regressions from the baseline are OK — we count
# IMPROVEMENTS, not regressions, as the success metric). Exits 1 if
# new shortcuts appeared that weren't in the baseline.
#
# With --update: overwrites the baseline with the current output.
# Use after a gap-fix wave to lock in the new lower count.
#
# The diff is intentionally one-directional: a file that HAD shortcuts
# and was fixed → removed from the current scan → that's an
# improvement, not a regression. Only NEW hits (files/lines that
# weren't in the baseline) fail the gate.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Find the project root for the re-scale data directory
PROJECT_ROOT="$(cd "$(pwd)" && while [ ! -d .rescale ] && [ "$PWD" != "/" ]; do cd ..; done; pwd)"
BASELINE="$PROJECT_ROOT/.rescale/data/shortcuts-baseline.tsv"

update=false
if [ "${1:-}" = "--update" ]; then
  update=true
fi

# Generate current scan
CURRENT="$(mktemp)"
trap 'rm -f "$CURRENT"' EXIT

re-scale enforce shortcuts --machine-readable > "$CURRENT" 2>/dev/null

if [ "$update" = true ]; then
  cp "$CURRENT" "$BASELINE"
  lines=$(wc -l < "$CURRENT")
  echo "Baseline updated: $BASELINE ($lines lines)"
  exit 0
fi

# Compare against baseline
if [ ! -f "$BASELINE" ]; then
  echo "No baseline found at $BASELINE"
  echo "Run: scripts/baseline-diff.sh --update"
  exit 1
fi

# Find lines in CURRENT that are NOT in BASELINE (new regressions)
# Skip the header line (starts with #)
NEW_HITS="$(mktemp)"
trap 'rm -f "$CURRENT" "$NEW_HITS"' EXIT

# Sort both for comm(1)
sort "$BASELINE" > "${BASELINE}.sorted"
sort "$CURRENT" > "${CURRENT}.sorted"

# Lines in current but not in baseline = new regressions
comm -23 "${CURRENT}.sorted" "${BASELINE}.sorted" > "$NEW_HITS"
rm -f "${BASELINE}.sorted" "${CURRENT}.sorted"

# Filter out header lines
NEW_COUNT=0
while IFS= read -r line; do
  case "$line" in
    '#'*) continue ;;
    '')   continue ;;
    *)    NEW_COUNT=$((NEW_COUNT + 1)) ;;
  esac
done < "$NEW_HITS"

BASELINE_COUNT=$(grep -cv '^#' "$BASELINE" 2>/dev/null || echo 0)
CURRENT_COUNT=$(grep -cv '^#' "$CURRENT" 2>/dev/null || echo 0)

echo "Baseline: $BASELINE_COUNT hits"
echo "Current:  $CURRENT_COUNT hits"
echo "New regressions: $NEW_COUNT"

if [ "$NEW_COUNT" -gt 0 ]; then
  echo ""
  echo "New shortcut hits not in baseline:"
  while IFS= read -r line; do
    case "$line" in
      '#'*|'') continue ;;
      *) echo "  $line" ;;
    esac
  done < "$NEW_HITS"
  exit 1
fi

IMPROVED=$((BASELINE_COUNT - CURRENT_COUNT))
if [ "$IMPROVED" -gt 0 ]; then
  echo "Improved by $IMPROVED hits since baseline — consider updating baseline"
fi
exit 0
