---
description: Show migration and audit progress — database stats, covenant coverage, enforcement summary
---

Show migration progress for this project.

## Procedure

1. Query migration database:
   ```
   re-scale db migration stats
   ```

2. Query audit database:
   ```
   re-scale db audit stats
   ```

3. Run a covenant coverage check:
   ```
   re-scale enforce verify --all --machine-readable
   ```
   Count pass vs fail (excluding "no covenant header" which just means not yet stamped).

4. Run a shortcuts summary:
   ```
   re-scale enforce shortcuts --machine-readable
   ```
   Count total hits and files affected.

5. Check file header metadata coverage:
   ```
   re-scale fileinfo --given . --when "covenant=full-port" --then "select covenant" --machine-readable
   ```
   Count the lines to get total covenanted files. Also check for files
   missing upstream commit tracking:
   ```
   re-scale fileinfo --given . --when "covenant=full-port && upstream-commit=" --then "select source-reference" --machine-readable
   ```

6. Report:
   - Migration: X files converted out of Y total
   - Audit: X pass, Y minor, Z major
   - Covenanted: N files with covenant headers, M verified passing
   - Shortcuts: N files still have hits (gap-fix candidates)
   - Upstream tracking: N covenanted files missing upstream-commit

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db` commands for DB operations, `re-scale fileinfo` for file header queries.
