---
description: boundary/break patterns replacing return/break/continue in Scala 3
---

# Control flow guide

Scala 3 with `-no-indent` and the project's `no return` rule requires
`scala.util.boundary` / `break` for early exit patterns.

## Replacing return

```scala
import scala.util.boundary, boundary.break

def find(xs: List[Int]): Int = boundary {
  for (x <- xs) {
    if (x > 10) break(x)
  }
  -1 // default
}
```

## Replacing break/continue in loops

```scala
boundary {
  for (x <- xs) {
    if (shouldSkip(x)) break() // acts like continue in the innermost boundary
    process(x)
  }
}
```

## Nested boundaries

Use `boundary.Label` to disambiguate:
```scala
boundary[Int] { outer ?=>
  for (xs <- xss) {
    boundary[Unit] { inner ?=>
      for (x <- xs) {
        if (done(x)) break(x)(using outer)
        if (skip(x)) break(())(using inner)
      }
    }
  }
  -1
}
```

The shortcuts scanner flags `var done/continue/stop` as `flag-break-var`.
