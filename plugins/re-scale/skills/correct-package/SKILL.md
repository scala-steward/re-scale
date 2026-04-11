---
description: Re-audit and fix all issues in a package — 4-phase audit-fix-reaudit-commit cycle.
---

Re-audit and fix all issues in the package `$ARGUMENTS`.

Argument: `$ARGUMENTS` — a package path like `math`, `compress`, `html/renderer`

## Procedure

### Phase 1: Re-audit

1. **Run `/re-scale:audit-package $ARGUMENTS`** with the latest audit criteria.
   This will re-read every file, compare against original sources, and identify
   all issues.

2. **Collect the full issue list** from the audit results. Categorise by severity
   (major first, then minor).

### Phase 2: Fix all issues

3. **Fix each issue**, working through the list in priority order:

   - **Stubs / incomplete implementations**: Implement the missing logic by
     reading the original source and converting properly. Follow all conversion
     rules from the project's docs.
   - **Missing methods / fields**: Port from the original source.
   - **Convention violations**: Fix return->boundary/break, null->Nullable,
     copyright header, missing braces, flat packages, etc.
   - **Missing tests**:
     - If the original has a test: port it using the project's test framework.
     - If no test exists: create a basic test covering the public API.

4. **Compile after each batch of fixes**: Run
   `re-scale build compile --errors-only` to catch regressions.

5. **Run tests**: If tests were added or modified, run `re-scale test unit`
   to verify they pass.

6. **Covenant gate** — for every file touched:
   - `re-scale enforce shortcuts --file <path>` — must report no new hits
   - `re-scale enforce verify --file <path>` — must pass

### Phase 3: Re-audit

7. **Run `/re-scale:audit-package $ARGUMENTS` again** to verify all issues
   are resolved. New issues may have been introduced during fixes.

8. **If new issues remain**, fix them and re-audit (max 3 iterations).

### Phase 4: Commit

9. **Compile-verify**: `re-scale build compile`

10. **Commit**:
    ```
    re-scale git stage <files>
    re-scale git commit --m "correct <pkg>: fix N issues (M major, m minor)"
    ```

## Important

- **Do NOT use shell commands directly.** Use `re-scale` commands and dedicated
  tools only.
- **Do NOT remove comments** from the original source — only add/update
  migration notes.
- **Porting is binary — 100% or not done.** See `/guide-porting`.
