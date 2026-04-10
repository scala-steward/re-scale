---
description: Code style rules for Scala 3 porting projects — headers, formatting, conventions
---

# Code style

- **License header**: Apache-2.0, with Migration notes block for audited files
- **Braces required** (`-no-indent`): `{}` for all trait/class/enum/method defs
- **Split packages**: `package ssg` / `package md` / `package core` (never flat)
- **No `return`**: use `boundary`/`break`
- **No `null`**: use `Nullable[A]`
- **No `scala.Enumeration`**: use Scala 3 `enum`, preferably `extends java.lang.Enum`
- **Case classes must be `final`**
- **No Java-style getters/setters**: no-logic `getX()`/`setX(v)` → public `var x`
- **Preserve all original comments** from the source
- **Fix bugs, don't work around them**: when a test reveals a pre-existing bug, fix it

## Formatting

Scalafmt config is `.scalafmt.conf` at project root. Run:
```
re-scale build fmt
```
