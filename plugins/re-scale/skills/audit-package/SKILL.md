---
description: Audit all ported Scala files in a package against their original sources.
---

Audit all Scala files in the package `$ARGUMENTS` against their original sources.

Argument: `$ARGUMENTS` — a package path like `math`, `graphics/g2d`, `compress`,
or `/batch` to audit multiple packages in sequence.

## Batch mode

If `$ARGUMENTS` is `/batch`, prompt the user for a list of package paths (one per
line or comma-separated) and audit each package in sequence. Between packages,
write partial audit docs and commit progress so work isn't lost if the session
is interrupted.

## Procedure

1. **Identify paths**: Consult the project's CLAUDE.md for the Source Reference
   table to determine the original source path and the ported Scala path for
   this package.

2. **Enumerate files**: Use Glob to find all `.scala` files under the ported
   package path (non-recursive — only direct children). For cross-platform
   projects, also check platform-specific paths (e.g., `scalajvm/`, `scalajs/`,
   `scalanative/`).

3. **Check for existing audit doc**: Look for `docs/audit/<slug>.md` where the
   slug is the package path with `/` replaced by `-` (e.g., `compress` ->
   `compress`, `graphics/g3d` -> `graphics-g3d`).

4. **Audit each file**: For each Scala file, run the `/re-scale:audit-file`
   procedure:
   - Read the Scala file and its original source
   - Compare public API (methods, constants, enums, inner types)
   - Check conventions (no return, no null, split packages, braces, comments)
   - Check for TODOs and stub implementations
   - Add/update migration notes in the file header
   - Record the audit result via `re-scale db audit set`

   For packages with 30+ files, process in batches of 15 and write partial
   audit docs between batches.

5. **Write the per-package audit doc** at `docs/audit/<slug>.md`:
   ```markdown
   # Audit: <package>

   Audited: N/N files | Pass: P | Minor: M | Major: J
   Last updated: YYYY-MM-DD

   ---

   ### FileName.scala

   | Field | Value |
   |-------|-------|
   | Port path | `<path>` |
   | Original source(s) | `<original path>` |
   | Status | pass/minor_issues/major_issues |
   | Tested | Yes — `test path` / No — reason |

   **Completeness**: Summary of API coverage.
   **Issues**: List of issues or "None"
   ```

6. **Compile-verify**: Run `re-scale build compile` to ensure header edits
   didn't break anything.

7. **Commit**: Stage and commit:
   ```
   re-scale git stage <files>
   re-scale git commit --m "audit <pkg>: N files (P pass, M minor, J major)"
   ```

## Important

**Do NOT use shell commands directly.** Use `re-scale` commands and dedicated
tools (Read, Grep, Glob, Edit) only.

**Do NOT access `.rescale/data/` files directly.** Use `re-scale db` commands.
