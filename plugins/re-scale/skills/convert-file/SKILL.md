---
description: Convert an original source file (Java/Dart/Ruby/JS) to idiomatic Scala 3.
---

Convert the original source file at `$ARGUMENTS` to idiomatic Scala 3.

## Procedure

1. **Read the source**: Open the original file at the path given by `$ARGUMENTS`
   using the Read tool. **NEVER fetch from GitHub** — always use local copies
   (submodules under `original-src/` or similar). Extract author tags from
   doc comments for the license header.

2. **Add license header**: The very first thing in the Scala file (before
   `package`) must be the license header per the project's code style guide.
   Include the original source path and authors.

3. **Check existing conversion**: Consult the project's CLAUDE.md Source
   Reference table for the path mapping (original -> Scala). Look for an
   existing Scala file at the mapped path. Check type-mappings docs for
   renamed/merged files.

4. **Load conversion guides**: Use the relevant guides for the source language:
   - `/guide-conversion` — Language-specific conversion rules
   - `/guide-nullable` — Nullable[A] patterns
   - `/guide-control-flow` — boundary/break patterns
   - `/guide-code-style` — Formatting, headers

5. **Execute the conversion**:
   - Port ALL methods, ALL branches, ALL edge cases
   - Follow the project's build rules (Scala version, flags, conventions)
   - Use `re-scale build compile` to compile and check errors/warnings

6. **Verify compilation**: `re-scale build compile` must show zero errors
   and zero warnings.

7. **Update tracking**: Run `re-scale db migration set <source_path> --status ai_converted`
   to update the file's migration status.

8. **Write initial audit entry**: Add a `Migration notes:` block to the file's
   header comment documenting renames, convention changes, and idiom compliance.
   Follow the format described in `/re-scale:audit-file`. Set `Audited:` to
   today's date.

9. **Stamp file metadata**: Set the file's header properties for tracking:
   ```
   re-scale fileinfo --given <path> --then "set original-src=<source_path>,status=ai_converted,authors=<authors>"
   ```

## Important

**Do NOT use shell commands directly.** Use `re-scale` commands and dedicated
tools only.

**Porting is binary — 100% or not done.** Every method, every branch, every
edge case in the original must be ported. See `/guide-porting` for the full
porting workflow.
