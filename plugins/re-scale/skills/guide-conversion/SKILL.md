---
description: Cross-language conversion rules for porting Java/Dart/Ruby to Scala 3
---

# Conversion guide

Load the appropriate language-specific rules based on the source language:

- **Java → Scala 3** (flexmark-java, liqp): Java getters → Scala vals/defs,
  checked exceptions → Either/Try, generics → type parameters, streams → FS2
- **Dart → Scala 3** (dart-sass): Dart null-safety → Nullable[A], extension
  methods → extension objects, cascade notation → builder pattern
- **Ruby → Scala 3** (jekyll-minifier): dynamic typing → ADT/sealed trait,
  monkey-patching → implicit class, blocks → lambdas

## Key principles

1. Port the LOGIC, not the syntax. Idiomatic Scala 3 over transliteration.
2. Use `Nullable[A]` for nullable values — never raw `null` except at Java interop.
3. Use `boundary`/`break` instead of `return`/`break`/`continue`.
4. All `case class` must be `final`.
5. No `scala.Enumeration` — use Scala 3 `enum`.
6. Preserve all original comments.
7. Fix bugs from the original when found during porting.

## Verification

After converting, run:
```
re-scale enforce shortcuts --file <path>    # must be 0 hits
re-scale enforce compare --port <scala> --source <original> --strict
re-scale build compile --module <mod> --all
re-scale test unit --module <mod>
```
