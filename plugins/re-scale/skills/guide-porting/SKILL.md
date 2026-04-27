---
description: Porting workflow — implement-audit loop, agents, and the 100% rule. Load when porting code from original sources to Scala 3.
---

Porting workflow guide. Use this when converting code from original sources
(Java, Dart, Ruby, JavaScript) to Scala 3, or when auditing ported code
for completeness.

## The 100% Rule

**Porting is binary — 100% or not done.** There is no such thing as
"diminishing returns" in porting. Every method, every branch, every edge
case in the original must be ported. The question is never "is this worth
the effort" — it is always "what remains to reach 100%". A file at 74%
coverage is not "mostly done" — it is incomplete. Do not rationalize
partial work as acceptable. Do not describe missing logic as "low priority"
or "diminishing returns". If the original has it, the port must have it.

The only valid reason to skip something is an **explicit user instruction**
to skip it.

## Agents

Two specialized agents handle the porting workflow:

| Agent | Role | Can edit code? |
|-------|------|---------------|
| `port-implementer` | Ports original code to Scala 3 | Yes |
| `port-auditor` | Compares port against original, finds gaps | No (read-only + issues DB) |

## The Implement-Audit Loop

All porting work MUST use the **implement -> audit -> fix -> re-audit** loop.
Never consider porting work "done" after a single implementation pass.

```
1. IMPLEMENTER: Port the file/module
   -> Compile, test, report metrics

2. AUDITOR: Compare original vs port
   -> Produce findings report with specific action items
   -> Create/reopen issues for each discrepancy

3. ORCHESTRATOR (you): Review auditor report
   -> If verdict is PASS: done, commit
   -> If verdict is FAIL: send findings to implementer

4. IMPLEMENTER: Address each finding (mandatory, not suggestions)
   -> Compile, test, report what changed

5. AUDITOR: Re-audit (verify findings were actually fixed)
   -> Repeat until PASS
```

## When to use this workflow

- Porting a new file from original source
- Filling gaps in a partially-ported file
- Any task where the deliverable is "make the Scala code match the original"

## When NOT to use this workflow

- Bug fixes in already-ported code
- Adding tests
- Build/config changes
- Documentation

## Checking progress

```
re-scale db issues list --package <pkg>    # Open issues per package
re-scale db issues stats                   # Overall issue counts
re-scale db audit list --package <pkg>     # Audit status per file
re-scale db audit stats                    # Overall audit summary
```

## Related skills

- `/guide-conversion` — Language-specific conversion rules
- `/guide-nullable` — Nullable[A] opaque type patterns
- `/guide-control-flow` — boundary/break patterns
- `/guide-code-style` — License headers, formatting
- `/guide-verification` — Post-conversion verification checklist
- `/file-metadata` — Query/update file header properties, track upstream commits
- `/audit-file` — Audit a single file
- `/audit-package` — Audit all files in a package
- `/correct-package` — Fix all issues in a package

## Important

**Do NOT use shell commands directly.** Use `re-scale` commands and dedicated
tools (Read, Grep, Glob, Edit) only. The PreToolUse hook enforces this.
