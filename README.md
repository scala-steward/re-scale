# re-scale

[![CI](https://github.com/kubuszok/re-scale/actions/workflows/ci.yml/badge.svg)](https://github.com/kubuszok/re-scale/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A **project-agnostic Scala Native CLI** that gives any codebase the same
disciplined developer workflow without dragging in project-specific
behavior. Replaces three flavor-divergent dev-CLIs (`ssg-dev`, `sge-dev`,
and a worktree variant) with one installable, tested, memory-bounded
binary that learns project specifics from `.rescale/*.yaml` config
files instead of hard-coding them.

## Where this came from

re-scale is the **third iteration** of the same idea: a project-local
dev CLI that wraps a fast Claude Code hook + a persistent migration
state layer, written specifically to support large AI-assisted
language-to-language porting work.

The lineage:

1. **`sge-dev`** was the **original**. It lived inside
   [SGE](https://github.com/MateuszKubuszok/sge) — the **Scala Game
   Engine**, a cross-platform Scala Native game engine ported from
   libgdx + custom native components, targeting six desktop and three
   Android architectures simultaneously. sge-dev started as a handful
   of helper scripts and grew organically into ~2500 LOC of
   project-specific Scala.

2. **`ssg-dev`** was bootstrapped FROM sge-dev when the same author
   started [SSG](https://github.com/MateuszKubuszok/ssg) — the
   **Scala Static Site Generator** — porting five upstream libraries
   to Scala Native cross-platform: `flexmark-java` (Markdown, Java),
   `liqp` (Liquid, Java), `dart-sass` (SCSS, Dart), `jekyll-minifier`
   (HTML/JS/CSS minification, Ruby), and `terser` (JavaScript
   compiler, JS). At the time of the re-scale split, ssg carried
   ~944 .scala files across 5 modules and ~80 KLoC. ssg-dev started
   as a copy of sge-dev with the obviously-game-engine-specific bits
   replaced; from there the two forks diverged file-by-file as each
   project grew its own opinions, until they were nearly-identical-
   but-not-quite duplicates.

3. **`re-scale`** is the rewrite that finally factors out the
   project-specific behavior into `.rescale/*.yaml` config files.
   No more forks. The tool itself is generic; the YAML in any given
   consuming repo carries the SGE-specific or SSG-specific or
   $YOUR-NEXT-PROJECT-specific opinions.

All three iterations share the same operating constraint: **a single
human guiding multiple Claude Code sessions through hundreds of files
of language-to-language porting work**. Neither sge-dev nor ssg-dev
nor re-scale is a standard or widely-adopted approach — they were
invented to make this specific workflow tractable.

### Why a custom dev CLI?

Claude Code is excellent at code transformation, but the porting
workflow exposed two recurring failure modes that off-the-shelf
tooling didn't address:

#### 1. A fast hook layer that nudges Claude towards safer, less wasteful patterns

By default, Claude Code can shell out to anything the OS allows. In a
repo with hundreds of files and active in-flight work, that's a
liability:

- `rm -rf <some-path>` is usually correct, but the one time it isn't,
  it's catastrophic. A hard `deny` saves an hour of "did I just
  delete my work?" panic.
- Reaching for `grep` / `find` / `cat` / `sed` works, but the dedicated
  Claude Code tools (Grep, Glob, Read, Edit) are much faster for the
  user (no shell startup, no terminal scrollback flooding) AND let
  Claude reason about results structurally instead of reparsing
  wall-of-text output. Redirecting these to the right tool with a
  one-line `deny` reason teaches Claude to default to the better path.
- `sbt --client` talks to a persistent sbt server that keeps the JVM
  warm and avoids the multi-second startup penalty bare `sbt` pays
  on every invocation. Forcing the client form means a 200 ms
  `compile` instead of a 30 s one — and across a session of dozens
  of build invocations that compounds into hours of dead time.
  (`sbt --client` itself can hang if `build.sbt` has an error AND
  reload-on-change is enabled; the rule isn't "client is always
  hang-proof," it's "stop paying JVM startup over and over.")
- A handful of patterns (downloading a `.jar` to grep through, writing
  under `/etc`, reaching for `.env`) are so universally wrong they
  deserve a hard stop regardless of intent.

The sge-dev / ssg-dev hook layer was essentially a 288-LOC opinion
piece on "what should Claude Code never do without confirmation in
this project." It needed to be **fast** (every Bash invocation goes
through it) and **always reachable** (a stale binary or a missing
build is worse than no hook). re-scale's hook is a single ~1 ms
native binary that's pre-built and on `$PATH` — no per-call cold
start, no compile-on-first-use surprise.

#### 2. Persistent migration state that survives across Claude Code sessions

Claude Code has built-in `MEMORY.md` files for cross-session memory,
but the porting workflows hit three limits:

- **Memory is summarized, not authoritative.** The model can mis-recall,
  conflate, or quietly drop entries. For "which Java file maps to which
  Scala file, what's its audit verdict, when was it last reviewed,"
  the answer needs to be byte-exact, queryable, and never paraphrased.
- **Memory isn't queryable.** "Show me every minor-issues row in
  ssg-md, sorted by last-updated" isn't something you can ask the
  memory file. A real TSV can answer it in milliseconds.
- **Memory isn't shared between humans and the agent.** A teammate
  reviewing migration progress wants to read the same data the agent
  is reading. A TSV under `.rescale/data/` is git-versioned, diff-able,
  and reviewable in a PR. A `MEMORY.md` is per-user.

So both projects ended up with three TSV "tables" the dev CLI
managed, all stored under `.rescale/data/`:

- **`.rescale/data/migration.tsv`** — every source file in the upstream
  library, its ported state (`pending` / `in-progress` / `ported` /
  `skipped`), and the corresponding Scala file path.
- **`.rescale/data/audit.tsv`** — per-file audit verdict (`pass` /
  `minor_issues` / `major_issues`) with notes, used to gate "is this
  port actually faithful?" review.
- **`.rescale/data/issues.tsv`** — open per-file issues with severity,
  category, and status — the porting equivalent of a bug tracker
  scoped to one repo.

All three are append-mostly, atomically locked, and read by Claude
Code via `re-scale db <table> list/get/set`. The agent never directly
reads or writes the TSV files (which would risk corruption); every
mutation goes through a single locked entry point. State is durable,
queryable, and survives every kind of session boundary — context
limit, network drop, machine reboot.

### Why a rewrite?

sge-dev started as a handful of scala-cli scripts and accreted
features. ssg-dev was bootstrapped from sge-dev and then diverged
file-by-file as ssg grew its own opinions. By the time anyone
noticed, three nearly-identical-but-not-quite forks existed across
three repos (sge-dev, ssg-dev main, and a long-lived ssg-dev worktree
variant) with:

- **Zero tests.** Regressions silently accumulated for months.
- **No memory ceiling.** A scanner pass on a 944-file codebase
  consumed 48 GB of RAM and had to be killed manually before macOS
  paged out.
- **Hand-rolled hook rules** in 288 LOC of Scala that needed to be
  edited and rebuilt to add a single project-specific deny rule.
- **Hard-coded module lists** (`ssg-md`, `ssg-liquid`, ...) that made
  the tool impossible to use in any other project without forking.

re-scale is the rewrite. The same developer-experience surface — hook
gating, db CRUD, build/test/git wrappers, code-quality scanning — but
generic, tested, memory-bounded, and configured per-project via YAML.

## What it unlocks

### 1. One CLI, any project

Drop a `.rescale/` directory into any Scala project (or any project,
really — much of re-scale doesn't care about Scala). re-scale
auto-discovers source roots from `*/src/main/scala` or
`.rescale/scan-targets.txt`, so the same binary works in
single-module, multi-module sbt, mill, or non-sbt projects.

### 2. Rule-driven Claude Code hook

`re-scale hook` is a `PreToolUse` validator that gates every Bash tool
call against a configurable rule set. Defaults catch the universal
footguns (`rm -rf`, `git push --force`, secret-file access, system-dir
writes, suboptimal-tool patterns), and any project can override or
extend them via `.rescale/claude-hooks.yaml`:

```yaml
rules:
  - when:
      starts-with: [adb]
    action: deny
    reason: "Use 're-scale test android' instead of direct adb"

  - when:
      and:
        - program-in: [ls]
        - has-any: ["-la"]
    action: allow
    reason: "ls -la is fine in this project"
```

The condition DSL supports `starts-with`, `has-any`, `has-any-suffix`,
`has-any-contains`, `has-redirect-target-prefix`, `program-in`, plus
`and`/`or`/`not` composition. Per-project rules merge in front of the
defaults (first-match-wins).

### 3. Streaming code-quality enforcement

`re-scale enforce` is the anti-cheat workhorse. Five scanners share a
streaming FS2 file-I/O backend so a 1000-file scan stays under
**150 MiB** of RSS:

| Subcommand                | What it catches |
|---------------------------|-----------------|
| `enforce shortcuts`       | TODO/HACK/`???`/`UnsupportedOperationException` patterns + 12 anti-cheat marker variants (null casts, comment-only stubs, "for now" hedges, scalastyle:ignore returns, ...) |
| `enforce stale-stubs`     | Comments like `// Foo.BAR is not yet ported` where `Foo.BAR` is now actually defined elsewhere — the stub is stale and the file should be re-wired |
| `enforce verify`          | Re-runs the Covenant header check (method-set parity + zero shortcuts) on a single file or every covenanted file |
| `enforce skip-policy`     | TSV-backed allow list for legitimate exceptions (vendored code, generated files, etc.) |
| `enforce compare --strict` | Method-set + body-token-count + constructor-arity comparison between a Scala port and its source (`.java`/`.dart`) |

The headline acceptance gate (`Ssg944FileMemoryBoundSpec`) runs the
full scanner suite against the 944-file SSG codebase and asserts peak
RSS stays under 512 MiB. The legacy ssg-dev consumed 48 GB on the
same workload before the streaming rewrite.

### 4. Doctor: dev-environment bootstrap as data

`re-scale doctor` reads `.rescale/doctor.yaml` and walks every
declared check + optional install step. The engine is generic; the
sge project ships Rust + Android NDK + Zig steps, ssg ships sbt
checks, a Python project ships pip steps. re-scale never knows what
an NDK is.

```yaml
steps:
  - id: jdk
    name: "JDK 22+ (Panama FFM)"
    check:
      command: java
      args: [-version]
      success-when:
        stderr-matches: 'openjdk version "(2[2-9]|[3-9][0-9])\.'
    install:
      command: sdk
      args: [install, java, 25.0.2-zulu]
      interactive: true       # skipped by --ci
    hint: "Run: sdk install java 25.0.2-zulu"

  - id: rust-targets
    name: "Rust cross-compile targets"
    check:
      command: rustup
      args: [target, list, --installed]
      success-when:
        stdout-contains: aarch64-apple-darwin
    install:
      command: rustup
      args: [target, add, aarch64-apple-darwin, x86_64-apple-darwin]
```

`re-scale doctor --ci` skips interactive installs but still reports
their status — great for `actions/setup-*` style CI bootstrapping.

### 5. Test-runner adapters as data

`re-scale runner` reads `.rescale/runners.yaml` and dispatches
arbitrary test harnesses. The mode-file dance preserves the legacy
SassSpec correctness fix (write-before-exec, runner reads + deletes
on entry, never delete from the wrapper):

```yaml
runners:
  sass-spec:
    description: "dart-sass spec compatibility test harness"
    invoke:
      command: sbt
      args: [--client, "ssg-sass/testOnly ssg.sass.SassSpecRunner"]
    mode-file:
      path: ssg-sass/target/sass-spec-mode.tsv
      format: kv
    output:
      success:
        regex: 'sass-spec:\s+Total=(\d+)\s+Passing=(\d+)'
        capture:
          total: 1
          passing: 2
      failure:
        keep-lines-matching: ['sass-spec:', 'FAIL', 'regressions']
        max-lines: 10
    modes:
      regression: {}
      strict:    { strict: "1" }
      snapshot:  { snapshot: "1" }
      subdir:    { subdir: "$1" }   # $1 = first positional CLI arg
```

```bash
re-scale runner sass-spec --mode subdir spec/css/units
```

### 6. Atomic TSV databases with cross-branch merge

`re-scale db` operates on three append-mostly TSVs:

| Table       | Rows                                        |
|-------------|---------------------------------------------|
| `migration` | per-source-file porting status              |
| `issues`    | open issues with id / severity / status     |
| `audit`     | per-file audit verdict (pass / minor / major) |

All writes go through `Tsv.modify`, which combines a per-path
in-process `Mutex[IO]` with a cross-process `FileChannel.lock()`. The
combination survives 20 concurrent writers without torn rows or ID
collisions — verified by `IssuesDbConcurrencySpec`. (Scala Native's
`FileChannel.lock()` is process-level, so the in-process mutex is
load-bearing for in-JVM parallelism.)

`re-scale db merge --target a.tsv --source b.tsv` reconciles two TSVs
across branches with ID-collision renumbering — exactly the bug we
hit during a multi-worktree gap-audit campaign where two agents
independently allocated `ISS-002..025` and `ISS-002..012`.

### 7. Process discovery + targeted termination

`re-scale proc list` shows pid / %cpu / %mem / kind / cwd / command
for every sbt server, java process, and metals daemon on the machine.
`--kind` and `--dir` filters let you kill ONLY the sbt servers
running inside one specific worktree without nuking unrelated work
in other repos.

```bash
re-scale proc list --kind sbt
re-scale proc list --kind sbt --dir /Users/me/work/my-repo
re-scale proc kill --kind sbt --dir /Users/me/work/my-repo
re-scale proc kill --pid 12345
```

`--kind all` requires `--dir` to prevent fat-finger disasters.

## Memory invariant

re-scale ships with a **mandatory wrapper script** that sets
`SCALANATIVE_MAX_HEAP_SIZE=1G`. Direct invocation of the binary is
unsupported. Combined with the streaming-first architecture
(`FileOps.streamLines` for every per-file walk; the StaleStubs
two-pass design that bounds memory by suspect-comment count instead
of identifier count; reusable `java.util.regex.Matcher` instances
that avoid per-match allocation), the headline gate is:

> **Scanning 944 Scala files (5 modules, ~80 KLoC of Scala) must
> peak under 512 MiB of RSS in under 30 s.**

The legacy ssg-dev consumed 48 GB of RAM on the same workload before
the rewrite. The current re-scale binary peaks at ~150 MiB.

## Install

```bash
git clone https://github.com/kubuszok/re-scale.git
cd re-scale
./scripts/install.sh         # builds + installs to $HOME/bin/re-scale
```

The installer:

1. Runs `sbt stage` to produce a Scala Native binary
2. Copies the binary + wrapper into `$HOME/bin/`
3. Verifies `$HOME/bin` is on `$PATH` (warns if not)

After install, `re-scale --help` from any directory.

## Configure

Per-project, drop any subset of these into `.rescale/`:

| File                       | Purpose |
|----------------------------|---------|
| `.rescale/claude-hooks.yaml` | Per-project hook rule overrides (merged in front of defaults) |
| `.rescale/doctor.yaml`     | Dev-environment bootstrap steps for `re-scale doctor` |
| `.rescale/runners.yaml`    | Test-runner adapters for `re-scale runner <name>` |
| `.rescale/modules.txt`     | Module list (one per line) — overrides auto-discovery |
| `.rescale/scan-targets.txt` | Source roots for scanners — overrides auto-discovery |

Plus the auto-managed db tables, all under `.rescale/data/`:

| File                            | Purpose |
|---------------------------------|---------|
| `.rescale/data/migration.tsv`   | Migration tracking |
| `.rescale/data/issues.tsv`      | Issues database |
| `.rescale/data/audit.tsv`       | Per-file audit verdict |
| `.rescale/data/skip-policy.tsv` | Enforcement allow list |

Never edit these files by hand — every mutation goes through the
locked `re-scale db <table> set/add/resolve` entry points so concurrent
agent sessions don't tear rows. The directory is git-versioned and
diff-friendly: a PR review can show exactly which rows changed.

## Wire into Claude Code

Add the hook to your `.claude/hooks/pre-tool-use.sh`:

```bash
#!/bin/bash
set -euo pipefail

if command -v re-scale >/dev/null 2>&1; then
  exec re-scale hook
fi

# (optional) fall back to a vendored binary if re-scale isn't installed yet
exec "$(dirname "$0")/../../scripts/bin/dev-tool" hook
```

That's it. `re-scale hook` reads the PreToolUse JSON event from stdin,
evaluates the merged rule set, and emits a wrapped
`hookSpecificOutput` decision.

## Architecture

```
src/main/scala/rescale/
  Main.scala                 # IOApp dispatcher
  common/                    # Cli, Paths, Term, Tsv, FileOps, Proc
  hook/                      # BashParser, RuleEvaluator, DefaultRules,
                             # RuleConfig (YAML loader), HookCmd
  db/                        # MigrationDb, IssuesDb, AuditDb, Merge, DbCmd
  enforce/                   # Covenant, Shortcuts, Methods, StaleStubs,
                             # SkipPolicy, EnforceCmd
  build/                     # BuildCmd (sbt --client wrappers)
  test/                      # TestCmd
  git/                       # GitCmd (git + gh)
  proc/                      # ProcCmd (process discovery + filtering)
  doctor/                    # Doctor engine + DoctorCmd
  runner/                    # Runner engine + RunnerCmd
```

Built on:

- **Scala Native 0.5** for fast-startup native binaries
- **Cats Effect 3.7** for resource-safe IO
- **FS2 3.13** for streaming file I/O (the linchpin of the memory invariant)
- **kindlings-yaml-derivation** for YAML config schemas
- **kindlings-jsoniter-json** for future structured CLI output
- **cats-parse** (legacy) for the bash grammar
- **munit** + **munit-cats-effect** for tests

## Test status

| Suite                              | Tests | Notes |
|------------------------------------|------:|-------|
| `rescale.common.*`                 |    34 | Cli, FileOps, Paths, Tsv, PathsModules |
| `rescale.hook.*`                   |    93 | BashParser, RuleEvaluator, DefaultRules, HookCmd, RuleConfig |
| `rescale.db.*`                     |    23 | DbCmd, Merge, IssuesDbConcurrency |
| `rescale.enforce.*`                |    37 | Covenant, Shortcuts, Methods, StaleStubs, SkipPolicy + Ssg944 acceptance gate |
| `rescale.proc.*`                   |     8 | ProcCmd parsers (parsePsOutput, classifyCommand, parseKindFilter) |
| `rescale.doctor.*`                 |    12 | DoctorConfig schema + Doctor engine |
| `rescale.runner.*`                 |     9 | RunnersConfig schema + Runner engine |
| `rescale` (Version, Memory bound)  |     5 | sanity + memory acceptance |
| **Total**                          | **245** | **0 failed** |

## License

Apache-2.0. See [LICENSE](LICENSE).
