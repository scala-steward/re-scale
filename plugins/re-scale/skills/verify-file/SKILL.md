---
description: Verify a ported Scala file — check conventions, compile, run tests, report status
---

Verify the file at `$ARGUMENTS` after conversion.

## Procedure

1. Read the file with the Read tool.

2. Quick checks:
   - License header present with source attribution?
   - No `return` keyword?
   - No raw `null` (should use `Nullable[A]`)?
   - `final case class` used for all case classes?
   - Split packages (not flat)?
   - Braces on all class/trait/object/def?

3. Enforcement checks:
   ```
   re-scale enforce shortcuts --file <path>
   re-scale enforce verify --file <path>
   ```

4. Compile check — determine module from path:
   ```
   re-scale build compile --module <module> --all
   ```

5. If tests exist, run them:
   ```
   re-scale test unit --module <module>
   ```

6. Report status: PASS / FAIL with specifics on what needs fixing.

## Important

**Do NOT use shell commands directly.** Use `re-scale` commands or dedicated tools (Grep, Glob, Read, Edit).
