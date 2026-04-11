---
description: Check and manage compiler linter flags — status, flags, unused, null analysis.
---

Check and manage compiler linter flags for the project.

Argument: `$ARGUMENTS` — one of: `status`, `flags`, `unused`, `null`

## Procedure

Based on the argument:

### `status`
Show which linter flags are currently enabled and their suppression status.

1. Read `build.sbt` with the Read tool and extract the `scalacOptions` section.
2. Show enabled flags, their purpose, and `-Wconf` suppressions.
3. Summarize: which flags produce errors, which are downgraded to info.

### `flags`
Show all available Scala 3 linter flags and their status.

1. Read `build.sbt` and extract current `scalacOptions`.
2. Display a table of all known Scala 3 linter flags, showing which are
   enabled, disabled, or suppressed.
3. For disabled flags, note why (e.g., "too noisy", "experimental",
   "breaks Java interop").

### `unused`
Run unused symbol analysis.

1. Compile the project:
   ```
   re-scale build compile
   ```

2. Use the Grep tool to search the compile output for `[E198]` patterns.
3. Count and categorize: unused imports, unused privates, unused locals,
   unused patvars.
4. Report the results grouped by category and file.

### `null`
Run null check analysis using the shortcut scanner.

1. Run the scanner:
   ```
   re-scale enforce shortcuts
   ```

2. Filter the output for `null-cast` hits to see violations by file.
3. For legitimate Java interop boundaries, suggest skip-policy entries:
   ```
   re-scale enforce skip-policy add <path> shortcuts --reason "Java interop boundary"
   ```

## Promoting Warnings to Errors

When a category of issues has been fully fixed (e.g., all unused imports
removed), the corresponding `-Wconf` suppression can be removed from
`build.sbt` to promote those warnings back to errors. This prevents
regressions.

## Important

**Do NOT use shell commands directly.** Use `re-scale` commands or
`sbt --client` only.
