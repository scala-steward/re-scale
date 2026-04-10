---
description: Nullable[A] opaque type patterns for null-safe Scala porting
---

# Nullable guide

Use `Nullable[A]` instead of raw `null`. The opaque type provides:
- `Nullable.empty[A]` instead of `null`
- `Nullable(value)` to wrap
- `.getOrElse(default)` for safe unwrapping
- `.map(f)` / `.flatMap(f)` for chaining
- `.orNull` only at Java interop boundaries (requires `@nowarn` + comment)

## Common patterns

```scala
// Java: String result = map.get(key);  // nullable
// Scala:
val result: Nullable[String] = Nullable(map.get(key))
result.getOrElse("default")

// Java: if (x != null) x.doSomething()
// Scala:
x.foreach(_.doSomething())
```

Never use `null.asInstanceOf[T]` — the shortcuts scanner flags it as `null-cast`.
