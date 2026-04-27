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

4. Check for metadata gaps — files missing upstream commit tracking:
   ```
   re-scale fileinfo --given . --when "covenant=full-port && upstream-commit=" --then "select source-reference" --machine-readable
   ```

5. Check DB sync — migration.tsv out of date with file headers:
   ```
   re-scale fileinfo --given . --then "sync-check"
   ```

6. Summarize findings by severity and suggest fixes.

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db` commands.
**Do NOT use shell commands for search.** Use the Grep tool.
