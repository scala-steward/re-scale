---
description: Show audit status for a package or the whole project — counts by status, identifies unaudited files
---

Show audit status. If `$ARGUMENTS` is provided, show status for that package; otherwise show overall stats.

## Procedure

### Overall (no arguments)

1. Query audit database:
   ```
   re-scale db audit stats
   ```

2. Show breakdown by status (pass / minor_issues / major_issues).

### Per-package (with arguments)

1. Query audit database for the package:
   ```
   re-scale db audit list --package $ARGUMENTS
   ```

2. Show each file's status, identify unaudited files in the package.

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db audit` commands.
