# Verification checklist

Post-conversion checklist for every ported file. Run these checks
**before** stamping a Covenant header. The checklist is ordered from
cheapest to most expensive — fail fast on the easy stuff.

---

## 1. Compile on all platforms

```bash
re-scale build compile --module <MODULE> --all
```

All three platforms (JVM, JS, Native) must compile cleanly with zero
warnings. The `--all` flag is shorthand for `--jvm --js --native`.

## 2. Run unit tests

```bash
re-scale test unit --module <MODULE> --all
```

Every existing test must pass. If the ported file changes behavior
that a test depends on, update the test — don't disable it.

## 3. Shortcut scan (zero hits required)

```bash
re-scale enforce shortcuts --file <PATH>
```

Must produce **0 hits**. If any hit is a false positive (e.g. a
legitimate use of `TODO` in a string literal), add a skip-policy entry
with `--reason` explaining why.

See the [triage table](gap-fix-task-template.md#triage-rules) for how
to resolve each pattern.

## 4. Method parity (strict compare)

```bash
re-scale enforce compare --port <SCALA_PATH> --source <ORIGINAL_PATH> --strict
```

Check for:
- **Missing methods**: must be ported from source or explicitly
  documented as intentionally omitted (with a comment in the file).
- **Short bodies**: method body token count is < 50% of the source's.
  Usually means the port is a stub.
- **Dropped constructor args**: parameters present in the source
  constructor but missing in the Scala class.

## 5. Stale-stub scan

```bash
re-scale enforce stale-stubs --src <MODULE>/src/main/scala
```

Cross-references "not yet ported" / "would be used" comments against
the definition index. Any hit where the identified definition IS
present means the comment is stale and should be removed.

## 6. Covenant verification (if updating an existing covenant)

```bash
re-scale enforce verify --file <PATH>
```

If the file already has a Covenant header, verify it still passes.
A failure means methods were removed since the baseline was set.

## 7. Stamp the covenant

```bash
re-scale enforce covenant-apply \
  --file <PATH> \
  --source <ORIGINAL_PATH> \
  --spec-pass <TEST_COUNT>
```

This writes the Covenant header with the current method set, LOC,
source reference, and today's date. The `--spec-pass` value is the
total test count at the time of stamping (e.g. `776` for ssg-sass).

After stamping, the CI gate (`enforce verify --all` +
`enforce shortcuts --covenanted`) will prevent regressions.

---

## Quick reference

| Step | Command | Pass criteria |
|------|---------|--------------|
| Compile | `re-scale build compile --module M --all` | Exit 0, no warnings |
| Tests | `re-scale test unit --module M --all` | All pass |
| Shortcuts | `re-scale enforce shortcuts --file F` | 0 hits |
| Compare | `re-scale enforce compare --port F --source S --strict` | No missing, no short-body |
| Stale stubs | `re-scale enforce stale-stubs --src DIR` | 0 stale |
| Verify | `re-scale enforce verify --file F` | pass |
| Covenant | `re-scale enforce covenant-apply --file F --source S` | created/updated |
