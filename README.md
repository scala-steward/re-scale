# re-scale

Scala Native porting toolkit + Claude plugin. Replaces `ssg-dev` / `sge-dev`
with a single installable, tested, memory-bounded CLI shared across
projects.

## Status

**Alpha.** Scaffolding landed; phases 1–11 in progress per
[the plan](../../.claude/plans/sunny-wiggling-phoenix.md).

## Build

```bash
sbt test                 # run unit + memory-bound tests
sbt nativeLink           # produce Scala Native binary
sbt stage                # copy binary + wrapper into target/stage/bin/
```

## Run

Always invoke via the wrapper — it sets `SCALANATIVE_MAX_HEAP_SIZE` to
cap the Immix GC at 1 GiB. Directly running the raw binary is unsafe
and will eventually OOM macOS.

```bash
./target/stage/bin/re-scale --help
```

## Install globally

After `sbt publishLocal`:

```bash
./scripts/install.sh     # installs to ~/Library/Application Support/Coursier/bin/re-scale
```

## Architecture

- `rescale.common.*` — Cli, Paths, Term, Tsv, FileOps (FS2 streaming primitives)
- `rescale.hook.*` — configurable PreToolUse hook (cats-parse bash grammar + scala-yaml rules)
- `rescale.db.*` — migration / issues / audit / merge (atomic TSV locking)
- `rescale.enforce.*` — Covenant, Shortcuts, Methods, StaleStubs, SkipPolicy
- `rescale.build.*`, `rescale.test.*`, `rescale.git.*`, `rescale.proc.*`, `rescale.quality.*`, `rescale.compare.*` — tool subcommands

## Heap cap

Every scanner command must stay under the wrapper's `SCALANATIVE_MAX_HEAP_SIZE`
cap. The headline test is `Ssg944FileMemoryBoundSpec`: scan all 944 Scala
files in the SSG codebase and assert peak RSS < 512 MB.
