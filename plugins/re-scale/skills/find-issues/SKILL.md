---
description: Find code quality issues across the codebase — shortcuts, stale stubs, open issues, convention violations
---

Find code quality issues in the project.

## Procedure

1. Run enforcement scans:
   ```
   re-scale enforce shortcuts
   re-scale enforce stale-stubs
   ```

2. Search for convention violations using the Grep tool:
   - `\breturn\b` in `*/src/main/scala/` — remaining `return` statements
   - `\bnull\b` in `*/src/main/scala/` — raw null usage
   - `TODO|FIXME` in `*/src/main/scala/` — outstanding work markers

3. Check issues database:
   ```
   re-scale db issues list --status open
   ```

4. Summarize findings by severity and suggest fixes.

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db` commands.
**Do NOT use shell commands for search.** Use the Grep tool.
