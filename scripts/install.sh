#!/bin/bash
#
# re-scale local installer.
#
# Builds the Scala Native binary via `sbt stage` and copies the wrapper
# + binary pair into $HOME/bin/. After this script succeeds, you can
# invoke `re-scale` from anywhere on disk (assuming $HOME/bin is on
# your $PATH).
#
# Why a shell script and not `cs install`:
#   The Coursier publish-then-install pipeline requires Maven artifact
#   metadata + a publishLocal sbt config. For a single-binary CLI tool
#   that's overkill — copying the wrapper + binary directly into
#   $HOME/bin gets the user up and running in one step. We can add
#   `cs install` support in a follow-up once the tool stabilizes and
#   needs Maven Central publishing for CI.
#
# What gets installed:
#   $HOME/bin/re-scale       — the wrapper shell script that sets
#                              SCALANATIVE_MAX_HEAP_SIZE before exec'ing
#                              the binary. ALWAYS use this, never the
#                              binary directly.
#   $HOME/bin/re-scale-bin   — the Scala Native ELF/Mach-O binary the
#                              wrapper exec's. Renamed from sbt's
#                              default `re-scale` link target so the
#                              wrapper can sit in the same directory.
#
# Idempotent: re-running this script just rebuilds and overwrites the
# previous install.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${RE_SCALE_INSTALL_DIR:-$HOME/bin}"

cd "$REPO_ROOT"

echo "==> Building re-scale via sbt stage..."
if command -v sbt >/dev/null 2>&1; then
  sbt stage
else
  echo "ERROR: sbt is not on \$PATH. Install sbt and retry." >&2
  exit 1
fi

STAGED_WRAPPER="$REPO_ROOT/target/stage/bin/re-scale"
STAGED_BINARY="$REPO_ROOT/target/stage/bin/re-scale-bin"

if [ ! -x "$STAGED_WRAPPER" ] || [ ! -x "$STAGED_BINARY" ]; then
  echo "ERROR: sbt stage did not produce $STAGED_WRAPPER + $STAGED_BINARY" >&2
  echo "Run \`sbt stage\` manually and check the output." >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR"

echo "==> Installing to $INSTALL_DIR/"
cp "$STAGED_WRAPPER" "$INSTALL_DIR/re-scale"
cp "$STAGED_BINARY"  "$INSTALL_DIR/re-scale-bin"
chmod +x "$INSTALL_DIR/re-scale" "$INSTALL_DIR/re-scale-bin"

INSTALLED_VERSION="$("$INSTALL_DIR/re-scale" --version 2>&1 || echo 'unknown')"
echo "==> Installed: $INSTALLED_VERSION"
echo

# PATH check — warn if $INSTALL_DIR isn't on the user's PATH.
case ":$PATH:" in
  *":$INSTALL_DIR:"*)
    echo "    $INSTALL_DIR is on \$PATH — you can run \`re-scale\` from anywhere."
    ;;
  *)
    echo "    NOTE: $INSTALL_DIR is NOT on your \$PATH."
    echo "    Add this to your shell rc:"
    echo
    echo "      export PATH=\"$INSTALL_DIR:\$PATH\""
    ;;
esac
