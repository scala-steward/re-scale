---
description: Post-conversion verification checklist using re-scale enforce
---

# Verification checklist

Run these checks after every conversion, in order (cheapest first):

1. **Compile**: `re-scale build compile --module <M> --all`
2. **Tests**: `re-scale test unit --module <M> --all`
3. **Shortcuts**: `re-scale enforce shortcuts --file <path>` → 0 hits
4. **Compare**: `re-scale enforce compare --port <scala> --source <original> --strict`
5. **Stale stubs**: `re-scale enforce stale-stubs --src <dir>`
6. **Covenant verify**: `re-scale enforce verify --file <path>`
7. **Stamp**: `re-scale enforce covenant-apply --file <path> --source <ref> [--spec-pass N]`

See `docs/contributing/verification-checklist.md` for full details.
