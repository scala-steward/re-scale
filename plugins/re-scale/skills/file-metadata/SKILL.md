---
description: Query, filter, and batch-update file header metadata — read properties without opening files, find files by property, batch-set values, check DB sync, track upstream commits
---

Query and manage file header metadata using `re-scale fileinfo`. Use this
instead of reading entire files when you only need to check a property like
covenant status, source reference, or upstream commit.

## Quick reference

```
re-scale fileinfo --given <path|dir|glob> [--when <predicate>] --then <action>
```

| Action | What it does |
|--------|-------------|
| `select *` | Print all header properties |
| `select field1,field2` | Print only named fields |
| `set key1=val1,key2=val2` | Write/update fields in file headers |
| `sync-check` | Compare migration.tsv against file headers |
| `track-commit` | Record upstream commit hash from git log |

## Properties available in file headers

Properties come from two sources in the leading block comment:

- **Covenant fields** (normalized without prefix): `covenant`, `baseline-spec-pass`,
  `baseline-loc`, `baseline-methods`, `source-reference`, `java-reference`,
  `dart-reference`, `verified`
- **Plain fields**: `original-src`, `authors`, `upstream-commit`, `module`,
  `status`, `tags`, or any custom `key: value` in the header comment
- **Migration notes** (also parsed): `renames`, `convention`, `idiom`, `audited`

## Predicates (--when)

| Operator | Example |
|----------|---------|
| `=` | `covenant=full-port` |
| `!=` | `status!=ported` |
| `contains` | `authors contains alice` |
| `starts-with` | `source-reference starts-with com/badlogic/` |
| `ends-with` | `source-reference ends-with .java` |
| `&&` | `covenant=full-port && baseline-loc starts-with 8` |
| `\|\|` | `status=ported \|\| status=done` |
| `!` | `!covenant=full-port` |
| `(...)` | `(status=ported \|\| status=done) && covenant=full-port` |

Quote values with spaces: `status="in progress"` or `status='in progress'`.

## Procedure — reading properties

To check a specific file's metadata without reading it:
```
re-scale fileinfo --given <path> --then "select *"
```

To check a single property:
```
re-scale fileinfo --given <path> --then "select covenant,source-reference"
```

## Procedure — finding files by property

Find all covenanted files in a directory tree:
```
re-scale fileinfo --given <dir> --when "covenant=full-port" --then "select covenant,verified" --machine-readable
```

Find files missing a property:
```
re-scale fileinfo --given <dir> --when "upstream-commit=" --then "select source-reference"
```

Find files by a specific author:
```
re-scale fileinfo --given <dir> --when "authors contains alice" --then "select *"
```

## Procedure — batch-updating properties

Set a property across matching files:
```
re-scale fileinfo --given <dir> --when "covenant=full-port && upstream-commit=" --then "set upstream-commit=pending" --dry-run
```

Remove `--dry-run` to apply. The `set` action inserts new key-value lines
into the block comment or updates existing ones. Covenant-prefixed fields
keep their `Covenant-` prefix on disk.

## Procedure — DB sync check

Verify migration.tsv matches what's in file headers:
```
re-scale fileinfo --given <dir> --then "sync-check"
```

Reports: files in DB but missing headers, files with headers but no DB row,
commit or status mismatches. Exit code 1 if any mismatch found.

## Procedure — tracking upstream commits

For files with `original-src` or `source-reference`, record the latest
commit hash of the original file:
```
re-scale fileinfo --given <dir> --when "original-src!=" --then "track-commit"
```

This runs `git log -1 --format=%H` for each original source, writes the
hash into `upstream-commit` in the file header, and auto-updates
`source_sync_commit` in migration.tsv.

Use `--dry-run` first to preview what would change.

## When to use fileinfo vs reading the file

| Situation | Use |
|-----------|-----|
| Need to know a file's covenant or source-reference | `fileinfo select` |
| Need to find which files are missing a property | `fileinfo --when` |
| Need to stamp metadata on many files at once | `fileinfo set` |
| Need the actual code or method implementations | Read tool |
| Need to verify enforcement gates | `re-scale enforce` |
| Need to update migration/issues/audit DB rows | `re-scale db` |

## Important

**Do NOT access .rescale/data/ files directly.** Use `re-scale db` commands for DB operations, `re-scale fileinfo` for file header operations.

**Do NOT use shell commands for file search.** Use `re-scale fileinfo --when` or Grep/Glob tools.
