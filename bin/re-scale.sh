#!/bin/bash
# re-scale wrapper — caps the Scala Native Immix heap so a runaway scanner
# can never bring down macOS.
#
# Why this exists: Scala Native's Immix collector mmaps heap regions
# opportunistically and has NO default upper bound. A code path with
# unintended O(N²) allocation (or just a slow GC trigger threshold)
# will keep growing until the OS pages out and the system becomes
# unresponsive. We've hit this twice in the legacy ssg-dev — once with
# the GC marker death spiral and once with eager full-file reads in
# Methods/StaleStubs.
#
# SCALANATIVE_MAX_HEAP_SIZE forces the runtime to throw OutOfMemoryError
# when it tries to grow past the cap, instead of growing indefinitely.
# 1 GiB is generous for any single re-scale invocation — the largest
# observed legitimate footprint is ~600 MB (enforce stale-stubs across
# all four SSG modules). Anything more is a bug caught by
# Ssg944FileMemoryBoundSpec.
#
# SCALANATIVE_MIN_HEAP_SIZE keeps the initial allocation small so a
# `db audit set` (which writes one row and exits) doesn't pre-reserve
# more than it needs.
#
# Override at the call site if you ever need a bigger heap:
#   SCALANATIVE_MAX_HEAP_SIZE=4G re-scale <command>

: "${SCALANATIVE_MIN_HEAP_SIZE:=16M}"
: "${SCALANATIVE_MAX_HEAP_SIZE:=1G}"
export SCALANATIVE_MIN_HEAP_SIZE
export SCALANATIVE_MAX_HEAP_SIZE

BIN="$(dirname "$0")/re-scale-bin"

# Opt-in memory trace: when RESCALE_TRACE_MEMORY=1, wrap the binary
# invocation under /usr/bin/time so every run emits peak RSS + wall
# time on stderr. Used to narrow down runaway allocations without
# instrumenting the binary itself. macOS uses -l, Linux uses -v.
# Both formats are greppable and include `maximum resident set size`.
if [ -n "${RESCALE_TRACE_MEMORY:-}" ] && [ -x /usr/bin/time ]; then
  case "$(uname -s)" in
    Darwin) /usr/bin/time -l "$BIN" "$@" ;;
    Linux)  /usr/bin/time -v "$BIN" "$@" ;;
    *)      "$BIN" "$@" ;;
  esac
else
  "$BIN" "$@"
fi
rc=$?

# On any non-zero exit — including SIGKILL (137), SIGTERM (143), and
# proper error exits — echo the full invocation to stderr so a
# post-mortem of the terminal scrollback identifies the exact
# subcommand that failed. Essential when a hung process had to be
# killed externally and you otherwise have no record of what ran.
if [ "$rc" -ne 0 ]; then
  printf 're-scale: exit=%s command=re-scale' "$rc" 1>&2
  for a in "$@"; do
    # quote each arg so multi-line commit messages reassemble correctly
    printf ' %q' "$a" 1>&2
  done
  printf '\n' 1>&2
fi

exit $rc
