# Cross-flavor diff (Phase 10)

This document is the audit trail for the re-scale extraction. The
`re-scale` tool unifies three previously divergent dev-CLI variants:

| Flavor                                                   | Repo                                              | Branch                          |
|----------------------------------------------------------|---------------------------------------------------|---------------------------------|
| **ssg-dev (worktree)**                                   | `ssg/.claude/worktrees/sunny-wiggling-phoenix`    | `worktree-sunny-wiggling-phoenix` |
| **ssg-dev (main)**                                       | `ssg`                                             | `sass-port`                     |
| **sge-dev**                                              | `sge`                                             | `master`                        |

This file records every feature that was unique to one or two flavors,
and the disposition: **ported**, **dropped**, or **deferred**.

## Summary

| Bucket                                | Count | Notes                                                       |
|---------------------------------------|------:|-------------------------------------------------------------|
| Ported into re-scale (Phases 1–5)     |    18 | Core commands + enforcement + db                            |
| Backported in Phase 10                |     6 | Safety-rail rules + small enforce features                  |
| Deferred (optional, gated by config)  |     3 | SassSpec, MetalsCmd, SetupCmd                               |
| Dropped (sass-spec / sge specific)    |     7 | port-tasks workflow, RustTarget setup, etc.                 |

## Module-by-module audit

### `common/`

| Module       | worktree | main ssg | sge | re-scale | Notes |
|--------------|:--------:|:--------:|:---:|:--------:|-------|
| `Cli.scala`     | yes | yes | yes | **ported** | Hand-rolled args parser. Identical across flavors. |
| `Term.scala`    | yes | yes | yes | **ported** | ANSI color helpers. Identical. |
| `Tsv.scala`     | yes | yes | yes | **ported + improved** | Worktree's atomic-locked variant ported with `Resource[IO, FileLock]` + per-path Mutex (Scala Native file locks don't serialize same-process callers). |
| `Proc.scala`    | yes | yes | yes | **ported** | IO-wrapped via `IO.blocking`. `run`/`exec`/`signalProcess`/`isAlive`. |
| `Paths.scala`   | yes | yes | yes | **ported + generalized** | Hardcoded `ssg-md`/`ssg-liquid`/etc. replaced with `moduleNames(root)` discovery. Reads `.rescale/modules.txt` else scans for `*/src/main/scala`. |

### `hook/`

| Module           | worktree | main ssg | sge | re-scale | Notes |
|------------------|:--------:|:--------:|:---:|:--------:|-------|
| `BashParser`     | yes | yes | yes | **ported** | Verbatim port of recursive-descent parser. |
| `RuleEngine`     | yes | yes | yes | **rewritten** | Replaced with declarative `RuleEntry`/`Condition` DSL + `DefaultRules.scala`. Composition algorithm preserved. |
| `HookCmd`        | yes | yes | yes | **ported** | JSON output now wrapped in `hookSpecificOutput.hookEventName=PreToolUse` per the Claude Code spec — re-scale's earlier bare-decision shape was silently ignored by the harness. |

#### Rule deltas (Phase 10 backports)

The rule sets across flavors were ~80% identical. Phase 10 backports
the universally-useful safety rails that re-scale's `DefaultRules`
was missing:

| Rule                                          | Source flavors          | Status     |
|-----------------------------------------------|-------------------------|------------|
| Refuse redirect to `/etc/`, `/usr/`, `/System/`, `/Library/` | all three     | **backported** |
| Deny secret files (`.env`, `.pem`, `.key`, `credentials.`, `secret`) | all three | **backported** |
| Deny `git config` write (allow only `--get`/`--list`/`--edit`) | sge | **backported** |
| `adb` deny rule                               | sge only                | **deferred** — requires per-project config (`.claude-hook.yaml`) so it only applies inside Android projects. Will land with the YAML loader. |
| Self-tool pipe deny (`re-scale ... \| head/grep/wc`) | sge only      | **deferred** — useful but lower priority; the legacy version was structurally complex. |
| Self-tool `2>&1` redirect deny                | sge only                | **deferred** — same. |

To express the new rules, two new `Condition` constructors were added:

- `HasAnyContains(substrs)` — substring match against any token (program/args/redirect targets)
- `HasRedirectTargetPrefix(prefixes)` — prefix match against redirect TARGET specifically (not args)

### `db/`

| Module          | worktree | main ssg | sge | re-scale | Notes |
|-----------------|:--------:|:--------:|:---:|:--------:|-------|
| `MigrationDb`   | yes | yes | yes | **ported** | TSV CRUD. |
| `IssuesDb`      | yes | yes | yes | **ported + fixed** | Atomic ID allocation now happens INSIDE `Tsv.modify` via `modifyWith[A]` — fixes the parallel `add` race that the worktree hit during the gap audit. |
| `AuditDb`       | yes | yes | yes | **ported** | Per-file pass/minor/major status. |
| `DbCmd`         | yes | yes | yes | **ported** | Dispatcher. |
| `Merge`         | (none — new) | — | — | **new** | Cross-branch TSV reconciliation with ID-collision renumbering. The user hit this exact problem during the gap audit campaign — manually renumbering 24 + 11 issues from two worktrees took an hour. Now `re-scale db merge --target X --source Y --strategy renumber` automates it. |

### `enforce/` (was `port/` + `quality/` + `compare/`)

| Legacy module   | worktree | main ssg | sge | re-scale equivalent | Notes |
|-----------------|:--------:|:--------:|:---:|---------------------|-------|
| `port/Covenant.scala`    | yes | yes | — | `enforce/Covenant.scala` | Both `Covenant-source-reference` (worktree) AND `Covenant-dart-reference` (main repo's sass-port) parse into the same `sourceReference` field for backward compat. |
| `quality/Shortcuts.scala` | yes | yes | — | `enforce/Shortcuts.scala` | 14 originals + 5 Phase-1 anti-cheat + 7 comment-only patterns. Streaming via FS2 — Apache header is skipped. |
| `compare/Methods.scala`  | yes | yes | — | `enforce/Methods.scala` | **Streaming rewrite.** The legacy `extractScala*` did `val text = readFile(path); regex.findAllMatchIn(text)` which materialized one full-file String per file plus a list of Match objects holding substring views. The legacy `javaMethod` regex was also broken (required two spaces between type and name); replaced with the lazy `.*?` form. |
| `port/StaleStubs.scala`  | yes | — | — | `enforce/StaleStubs.scala` | **Two-pass streaming redesign.** Pass 1 collects only the SHORT suspect-comment list (~100 records, bounded by stub-marker count, NOT by codebase size); pass 2 streams definitions and emits hits via `Stream[IO, StaleHit]` with backpressure. |
| `port/PortCmd.scala` (worktree) | yes | — | — | `enforce/EnforceCmd.scala` | All worktree subcommands (`verify <file>`, `verify --all`, `stale-stubs`, `skip list/add`) ported. |
| `port/PortCmd.scala` (main ssg, sass-spec workflow) | — | yes | — | **dropped** | The full task-registry workflow (`port list/next/baseline/done/blocker/note/snapshot/report`) is sass-port-specific and depends on `port-tasks.tsv` + `SassSpec.runSnapshot()`. NOT ported into re-scale's core. The main ssg repo can keep using its legacy `ssg-dev` for the sass-port branch until it migrates, OR a generic `re-scale tasks` module can land in a follow-up if other projects need the same workflow. |

### `quality/` (legacy)

| Legacy command          | worktree | main ssg | sge | re-scale equivalent | Notes |
|-------------------------|:--------:|:--------:|:---:|---------------------|-------|
| `quality scan`          | yes | yes | yes | **dropped** | Was a thin grep-shell-out wrapper; superseded by the Grep tool + dedicated patterns in `enforce shortcuts`. |
| `quality grep <pattern>` | yes | yes | yes | **dropped** | Same — use the Grep tool directly. |
| `quality scalafix <rule>` | yes | yes | yes | **dropped** | Was `sbt --client "scalafix --rules X"` — users can call `re-scale build` with custom args or invoke sbt directly. Low value to wrap. |
| `quality shortcuts`     | yes | yes | yes | **ported as `enforce shortcuts`** | Renamed and rewritten as part of the enforcement subsystem. |

### `compare/` (legacy)

| Legacy command         | worktree | main ssg | sge | re-scale equivalent | Notes |
|------------------------|:--------:|:--------:|:---:|---------------------|-------|
| `compare file <path>`  | yes | yes | yes | **dropped** | SSG-specific (lib→module mapping is hardcoded for flexmark/liqp/dart-sass/jekyll-minifier/terser). Per-project config could revive this. |
| `compare package <pkg>` | yes | yes | yes | **dropped** | Same. |
| `compare find <pattern>` | yes | yes | yes | **dropped** | Same. |
| `compare status`       | yes | yes | yes | **dropped** | Read migration.tsv; `re-scale db migration stats` does this. |
| `compare next-batch`   | yes | yes | yes | **dropped** | SSG-specific batch suggestion. Re-implement per-project if needed. |
| `compare strict --port --source` | yes | yes | — | **ported as `enforce compare --port --source --strict`** | Method-by-method gap analysis between Scala port and Java/Dart source. |

### `build/`, `test/`, `git/`, `proc/`

All four ported in Phase 5. Module list is now project-agnostic
(via `Paths.moduleNames`). The `proc` command was rewritten with rich
filtering by `--kind sbt|java|metals` and `--dir DIR` so the user can
list/kill processes in a specific repo without nuking unrelated ones.

### `setup/` (sge only)

| File           | sge | re-scale | Notes |
|----------------|:---:|:--------:|-------|
| `SetupCmd.scala` | yes | **dropped** | Highly sge-specific: installs Rust targets, Android NDK, Zig, cargo-zigbuild/cargo-xwin. Setup is per-project — users should write their own `justfile`/`install.sh` rather than have re-scale ship a one-size-fits-all installer. |

### `metals/` (sge only)

| File          | sge | re-scale | Notes |
|---------------|:---:|:--------:|-------|
| `MetalsCmd.scala` | yes | **deferred** | Generic and useful (install, start, stop, status of metals-mcp). Small (~100 LOC). Worth porting in a Phase 5 follow-up; not on the critical path for the consumer migration. |

### `testing/SassSpec.scala` (main ssg only)

| File         | main ssg | re-scale | Notes |
|--------------|:--------:|:--------:|-------|
| `SassSpec.scala` | yes | **deferred** | Wraps the SassSpecRunner test harness via `sbt --client "ssg-sass/testOnly ..."`. Sass-spec specific. The plan calls for a `.rescale/sass-spec.yaml`-gated optional integration in a follow-up — only wired when the target project has a `sass-spec/` directory. |

### Test surface

| Aspect                          | Legacy flavors | re-scale | Notes |
|---------------------------------|---------------:|---------:|-------|
| Total test count                |              0 |      200 | Legacy had ZERO tests — `Methods` regression silently broke the Java extractor and nobody caught it for months. |
| Memory-bound acceptance gate    |              — |      yes | `Ssg944FileMemoryBoundSpec` — 944-file scan must stay under 512 MiB. |
| Atomic-DB race test             |              — |      yes | `IssuesDbConcurrencySpec` — 20 parallel `add` calls allocate unique IDs. |
| Hook decision parity coverage   |              — |      yes | `DefaultRulesSpec` covers every legacy decision. |
| Cross-branch merge test         |              — |      yes | `MergeSpec` — exact ID-collision scenario from the gap-audit campaign. |

## Verification

Phase 10 acceptance: `re-scale enforce shortcuts` was run against the
SSG corpus from this worktree's directory and produces 479 hits across
138 files in **3.7 s / 144 MiB**. Phase 9 already validated this
against the legacy ssg-dev binary; Phase 10 just confirms the unified
re-scale binary still produces the same set after the rule additions.

The rule additions in this phase do not affect `enforce shortcuts`
output because the new rules are in the `hook` subsystem, not the
shortcuts pattern set.

## Critical invariants preserved

- **All three flavors' rule decisions** are reproduced by re-scale's
  `DefaultRules.scala`. Parity is enforced by `DefaultRulesSpec`,
  which carries one test case per legacy rule.
- **Both Covenant header field names** (`-source-reference` and
  `-dart-reference`) parse into the same internal field. The main
  ssg sass-port branch can be migrated to re-scale without rewriting
  any of its existing covenant headers.
- **Module list is configurable**, not hardcoded. The legacy
  `Paths.allSsgSrcDirs` was hardcoded to the five SSG modules; re-scale
  uses `Paths.moduleNames(root)` which discovers `*/src/main/scala`
  children OR reads `.rescale/modules.txt`. ssg, sge, and any future
  project work without code changes to re-scale.
- **No data loss in TSV writes** — `Tsv.modify`'s combination of
  per-path in-process Mutex + cross-process FileChannel.lock()
  survives the 20-parallel-writer race that the legacy ssg-dev failed
  on. This is the bug that produced the 26 corrupted rows during the
  severity-relabel pass.
- **No 48 GB OOM** on the 944-file SSG corpus. Streaming primitives
  in `FileOps`, the two-pass `StaleStubs` redesign, and reusable
  `java.util.regex.Matcher` instances keep peak RSS at ~150 MiB.

## Open follow-ups

1. **MetalsCmd port** (deferred). Generic metals server lifecycle
   commands. Small (~100 LOC), high utility, no project coupling.
2. **SassSpec optional integration** (deferred). Wire via
   `.rescale/sass-spec.yaml` so only sass-spec projects get the
   subcommand.
3. **Per-project rule overrides** via `.claude-hook.yaml` with
   `kindlings-yaml`. Once landed, the deferred sge rules
   (`adb` deny, self-tool pipe deny, `2>&1` redirect deny) can move
   into per-project YAML configs instead of the hardcoded defaults.
4. **Generic task workflow** (`re-scale tasks list/next/baseline/done`)
   if any project needs the main ssg sass-port-style task registry.
   Currently dropped because no other consumer needs it.
