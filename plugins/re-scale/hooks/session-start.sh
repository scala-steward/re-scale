#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────
# SessionStart hook: ensure re-scale CLI is on PATH
#
# If re-scale is already on PATH, does nothing.
# Otherwise: clones the repo (or finds an existing clone),
# triggers a build if the binary is missing, and exports
# the bin/ directory onto PATH for this session.
# ─────────────────────────────────────────────────────────────

RESCALE_VERSION="0.1.5"

# Where to find / put the re-scale source tree.
# Users can override with RESCALE_HOME env var.
RESCALE_HOME="${RESCALE_HOME:-$HOME/.local/share/re-scale/$RESCALE_VERSION}"

# Already available at the right version?
if command -v re-scale >/dev/null 2>&1; then
  FOUND_VERSION=$(re-scale --version 2>/dev/null | awk '{print $2}' || true)
  if [ "$FOUND_VERSION" = "$RESCALE_VERSION" ]; then
    exit 0
  fi
  # Wrong version on PATH — fall through to prepend the correct one
fi

# Clone if needed
if [ ! -f "$RESCALE_HOME/build.sbt" ]; then
  echo "[re-scale] First-time setup: cloning re-scale $RESCALE_VERSION..." >&2
  git clone --depth 1 --branch "$RESCALE_VERSION" https://github.com/kubuszok/re-scale.git "$RESCALE_HOME" >&2 2>&1 || {
    echo "[re-scale] WARNING: git clone failed — re-scale CLI will not be available." >&2
    exit 0
  }
fi

# Build if binary is missing
BINARY="$RESCALE_HOME/.build/re-scale-bin"
if [ ! -f "$BINARY" ]; then
  echo "[re-scale] Building Scala Native binary (first time only — takes ~30s)..." >&2
  if command -v sbt >/dev/null 2>&1; then
    (cd "$RESCALE_HOME" && sbt install) >&2 2>&1 || {
      echo "[re-scale] WARNING: sbt install failed — re-scale CLI will not be available." >&2
      exit 0
    }
  else
    echo "[re-scale] WARNING: sbt not on PATH — re-scale CLI will not be available." >&2
    echo "[re-scale] Install sbt (brew install sbt) and restart the session." >&2
    exit 0
  fi
fi

# Add bin/ to PATH for this session
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export PATH=\"$RESCALE_HOME/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi
