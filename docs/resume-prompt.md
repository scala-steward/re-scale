# Resume prompt for paused ssg / sge sessions

Copy-paste the following message into any paused Claude Code session
running in `ssg` (sass-port branch) or `sge`. It explains the
ssg-dev → re-scale migration that landed while the session was
paused, and brings the agent back up to date.

---

## Tooling migration: `ssg-dev` / `sge-dev` → `re-scale`

While this session was paused, the project's developer CLI was
**replaced**. The legacy `ssg-dev` (or `sge-dev`) tool that previously
lived under `scripts/src/` is gone. It has been superseded by
**`re-scale`**, a new project-agnostic Scala Native binary that:

- Provides the same hook + db + build/test/git/proc surface
- Adds a `.rescale/` per-project YAML config layer
  (`claude-hooks.yaml`, `doctor.yaml`, `runners.yaml`)
- Is **not** committed to this repo — it lives in its own external
  repo and is installed once into `$HOME/bin/` so a single binary
  serves multiple projects

### What changed in this repo

Pull the latest commits and you'll see:

1. **`scripts/data/*.tsv` moved to `.rescale/data/*.tsv`.** The
   migration / issues / audit / skip-policy databases now live under
   `.rescale/data/`. `re-scale db <table>` reads exclusively from
   that location — the legacy `scripts/data/` path is no longer
   recognized.

2. **`scripts/src/` deleted.** The entire 20-30 file legacy Scala CLI
   source tree is gone. `scripts/bin/`, `scripts/ssg-dev` (or
   `scripts/sge-dev`), and `scripts/ssg-dev-bin` were untracked
   build artifacts and can be cleaned up via `git clean -fdx scripts/`
   if you want to be tidy.

3. **`.claude/hooks/pre-tool-use.sh` rewritten.** It now `exec`s
   `re-scale hook` directly. There is no longer a fallback that
   compiles `scripts/src/` from source — if `re-scale` isn't on
   `$PATH`, the hook fails loudly with an install hint.

4. **`CLAUDE.md` rewritten.** Every `ssg-dev` / `sge-dev` command
   reference is now `re-scale`. Some legacy subcommands (`quality
   scan/grep/scalafix`, `compare file/package/find/status/next-batch`,
   `port list/next/baseline/done`, `setup`, `metals`,
   `test android/browser`, `build extensions/release/...`) have no
   direct re-scale equivalent yet. They're documented in CLAUDE.md
   under "SGE-specific workflow notes" / "Sass-port workflow note"
   with their migration target (`re-scale doctor` reading
   `.rescale/doctor.yaml`, or `re-scale runner <name>` reading
   `.rescale/runners.yaml`).

### What you need to do to unblock this session

**Step 1: Install `re-scale` if it isn't already.**

```bash
git clone https://github.com/kubuszok/re-scale.git ~/Workspaces/re-scale
cd ~/Workspaces/re-scale
./scripts/install.sh
```

That builds the Scala Native binary and copies `re-scale` +
`re-scale-bin` into `$HOME/bin/`. Verify with:

```bash
re-scale --version
# → re-scale 0.1.0-SNAPSHOT
```

If `$HOME/bin` isn't on `$PATH`, the install script will warn you
and tell you what to add to your shell rc.

**Step 2: Re-read CLAUDE.md.** It now documents the `re-scale`
command surface, including the per-project YAML config files
(`.rescale/claude-hooks.yaml`, `.rescale/doctor.yaml`,
`.rescale/runners.yaml`) that this project may or may not have.

**Step 3: Rebuild your mental model of the available commands.**

| You used to do this | Now do this |
|---------------------|-------------|
| `ssg-dev build compile --module ssg-md` | `re-scale build compile --module ssg-md` |
| `sge-dev build compile --module sge` | `re-scale build compile --module sge` |
| `ssg-dev test unit --jvm` | `re-scale test unit --jvm` |
| `ssg-dev db audit list` | `re-scale db audit list` |
| `ssg-dev db audit set <file> <status>` | `re-scale db audit set <file> <status>` |
| `ssg-dev quality shortcuts --module ssg-md` | `re-scale enforce shortcuts --src ssg-md/src/main/scala` |
| `ssg-dev port verify <id>` | (legacy — see CLAUDE.md sass-port notes) |
| `sge-dev setup --ci` | `re-scale doctor --ci` (needs `.rescale/doctor.yaml`) |
| `sge-dev metals start` | `re-scale runner metals --mode start` (needs `.rescale/runners.yaml`) |
| `ssg-dev proc kill-sbt` | `re-scale proc kill --kind sbt --dir .` |
| `sge-dev git status` | `re-scale git status` |
| `ssg-dev git gh pr view 123` | `re-scale git gh pr view 123` |

The general pattern: **drop `ssg-dev` / `sge-dev`, type `re-scale`
instead.** A handful of commands moved between subsystems
(`quality shortcuts` → `enforce shortcuts`, `proc kill-sbt` →
`proc kill --kind sbt`) but the rest is a flat rename.

**Step 4: If you depended on a legacy command that has no re-scale
equivalent yet** (sass-port `port` workflow, sge `setup`, sge
`metals` lifecycle, sge `test android`/`test browser`, sge
`build release`, etc.) — STOP and ask the human before improvising.
The migration intentionally left these as gaps to be filled by
project-specific YAML configs (`.rescale/doctor.yaml` for setup,
`.rescale/runners.yaml` for test harnesses + release pipelines).
Writing that YAML is a deliberate decision the human will want to
make explicitly, not something to wedge in mid-task.

**Step 5: Re-read any skill files relevant to your task.** The skill
files in `.claude/skills/*/SKILL.md` are mostly $READ pointers that
delegate to `docs/contributing/*.md` + `docs/architecture/*.md` —
they didn't reference `ssg-dev` directly so they didn't need
rewriting. But the docstrings they delegate to may still mention
the old tool name in places; treat any `ssg-dev` / `sge-dev` reference
in the docs as a typo for `re-scale`.

### Background: why this happened

re-scale is the **third iteration** of the same project-local dev
CLI idea. `sge-dev` was the original; `ssg-dev` was bootstrapped from
it and diverged file-by-file. By the time three nearly-identical
forks existed (sge-dev, ssg-dev main, and a worktree variant), with
zero tests and no memory bound, it was time to factor out the
project-specific behavior and build one tested binary that every
consumer installs once.

The full rationale and architecture is in
<https://github.com/kubuszok/re-scale> — particularly the README
and `docs/cross-flavor-diff.md`.

### Resuming the actual work

Once `re-scale --version` succeeds and you've re-skimmed CLAUDE.md,
reply with "Tooling migration acknowledged, ready to continue
<task>" and we'll pick up where we left off.
