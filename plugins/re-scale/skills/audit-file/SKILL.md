---
description: Audit a ported Scala file against its original source — runs shortcuts, compare, stale-stubs, and records the result
---

Audit the file at `$ARGUMENTS` against its original source.

## Procedure

1. Read the file's header properties (without opening the full file):
   ```
   re-scale fileinfo --given <path> --then "select *"
   ```
   This shows covenant status, source-reference, authors, upstream-commit, etc.

2. Identify the original source file from the properties:
   - `source-reference` or `java-reference` — Covenant source path
   - `original-src` — plain source path
   - Or look in the `original-src/` submodule using the project's type-mapping convention

3. Read both files: the Scala file with the Read tool, the original from the
   local submodule (**never fetch from GitHub**).

4. Run enforcement checks:
   ```
   re-scale enforce shortcuts --file <path>
   re-scale enforce compare --port <path> --source <original> --strict
   re-scale enforce verify --file <path>
   ```

5. Check conventions:
   - License header with original source attribution
   - No `return`, no raw `null` (use `Nullable[A]`)
   - `final case class`, split packages, braces required
   - Uses `boundary`/`break` where the original has early returns

6. Check tests — does the original have tests? Are they ported?

7. Record the audit result:
   ```
   re-scale db audit set <file_path> --status <pass|minor_issues|major_issues> --tested <yes|no|partial> --notes "..."
   ```

8. If the file passes all checks, stamp the covenant:
   ```
   re-scale enforce covenant-apply --file <path> --source <original> [--spec-pass N]
   ```

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db` commands.
**Do NOT use shell commands for search/read.** Use Grep, Glob, Read, Edit tools.
