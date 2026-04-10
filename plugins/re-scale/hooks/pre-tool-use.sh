#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# PreToolUse hook: validate tool calls via re-scale hook
#
# Delegates to `re-scale hook` which reads the PreToolUse JSON
# from stdin, evaluates the rule set, and emits a decision.
#
# If re-scale isn't on PATH (session-start didn't run or failed),
# passes through silently — never blocks the user.
# ─────────────────────────────────────────────────────────────

if command -v re-scale >/dev/null 2>&1; then
  exec re-scale hook
fi

# re-scale not available — pass through
exit 0
