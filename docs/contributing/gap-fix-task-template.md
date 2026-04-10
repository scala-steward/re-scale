# Gap-fix task template

Standard template for driving a gap-fix wave across files that fail
enforcement. Copy the **Task prompt** section below and fill in the
`<placeholders>` for each file or batch.

---

## Task prompt

```
Fix enforcement failures in <FILE_PATH>.

Source reference: <ORIGINAL_SOURCE_PATH>

### Current enforcement output

<PASTE re-scale enforce shortcuts --file <FILE_PATH> OUTPUT HERE>
<PASTE re-scale enforce compare --port <FILE_PATH> --source <ORIGINAL_SOURCE_PATH> --strict OUTPUT HERE>

### What to do

1. Read the Scala file and its source reference (use Read tool on both).
2. For each shortcut hit:
   - If it's a TODO/FIXME with a real implementation gap: port the
     missing logic from the source reference.
   - If it's a "for now" / "simplified" / "placeholder" comment that
     describes completed work: remove or reword the comment.
   - If it's a null.asInstanceOf: replace with the Nullable[A] pattern
     (see docs/contributing/nullable-guide.md).
   - If it's a ??? or throw NotImplementedError: implement the method
     from the source reference.
3. For each missing method (from `compare --strict`):
   - Port it from the source reference following
     docs/contributing/conversion-rules-<LANG>.md.
4. For each short-body method:
   - Compare against the source and fill in the missing logic.
5. After fixing, verify:
   - `re-scale enforce shortcuts --file <FILE_PATH>` → 0 hits
   - `re-scale enforce compare --port <FILE_PATH> --source <ORIGINAL_SOURCE_PATH> --strict` → no missing, no short-body
   - `re-scale build compile --module <MODULE>` → compiles on all platforms
   - `re-scale test unit --module <MODULE>` → tests pass
6. When all checks pass, stamp the covenant:
   - `re-scale enforce covenant-apply --file <FILE_PATH> --source <ORIGINAL_SOURCE_PATH> [--spec-pass N]`
7. Commit with a message like:
   - `<MODULE>: gap-fix <FILE_NAME> — N shortcuts resolved, M methods ported`
```

---

## Batch workflow

For a wave of N files:

1. Generate the hit list:
   ```
   re-scale enforce shortcuts --machine-readable > /tmp/shortcuts.tsv
   ```
2. Sort by file, pick the first batch (e.g. 10 files from one package).
3. For each file in the batch, fill in the template above and execute.
4. After the batch:
   ```
   re-scale enforce shortcuts --covenanted   # should be 0 hits
   re-scale enforce verify --all             # all covenanted files pass
   ```
5. Commit the batch as one PR per package or one PR per wave.

---

## Triage rules

| Hit type | Action |
|----------|--------|
| `todo` / `fixme` / `hack` / `xxx` | Port the missing logic or remove if done |
| `for-now-comment` / `simplified-comment` | Reword or remove if the "for now" is no longer true |
| `null-cast` / `nullable-null-fallback` | Replace with `Nullable[A]` pattern |
| `stub-comment` / `placeholder-comment` | Implement or remove |
| `not-yet-comment` / `deferred-comment` | Port the deferred logic |
| `unsupported-op` / `not-implemented` / `scala-unimpl` | Implement from source |
| `catch-throwable` | Narrow the catch to specific exceptions |
| `flag-break-var` | Rewrite with `boundary`/`break` (see control-flow-guide.md) |
| Missing method (compare) | Port from source reference |
| Short body (compare) | Fill in from source reference |
| Dropped ctor arg (compare) | Add the parameter back |

---

## Skip-policy escape hatch

If a hit is intentional and cannot be fixed (e.g. a `null.asInstanceOf`
at a Java interop boundary that genuinely requires it):

```
re-scale enforce skip-policy add <FILE_PATH> shortcuts --reason "Java interop: <explanation>"
```

The skip-policy entry is checked into `.rescale/data/skip-policy.tsv`
and excludes the file from the shortcuts gate. Use sparingly — every
skip-policy entry is a debt marker.
