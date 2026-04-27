/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.fileinfo

import munit.FunSuite
import rescale.fileinfo.Predicate.Expr

final class PredicateSpec extends FunSuite {

  // -- parse: simple equality -------------------------------------------

  test("parse: field=value") {
    val r = Predicate.parse("covenant=full-port")
    assertEquals(r, Right(Expr.Eq("covenant", "full-port")))
  }

  test("parse: field = value (spaces)") {
    val r = Predicate.parse("covenant = full-port")
    assertEquals(r, Right(Expr.Eq("covenant", "full-port")))
  }

  test("parse: field != value") {
    val r = Predicate.parse("status != ported")
    assertEquals(r, Right(Expr.Neq("status", "ported")))
  }

  test("parse: field!=value (no spaces)") {
    val r = Predicate.parse("status!=ported")
    assertEquals(r, Right(Expr.Neq("status", "ported")))
  }

  // -- parse: keyword operators -----------------------------------------

  test("parse: field contains value") {
    val r = Predicate.parse("authors contains alice")
    assertEquals(r, Right(Expr.Contains("authors", "alice")))
  }

  test("parse: field starts-with value") {
    val r = Predicate.parse("source-reference starts-with lib/src/")
    assertEquals(r, Right(Expr.StartsWith("source-reference", "lib/src/")))
  }

  test("parse: field ends-with value") {
    val r = Predicate.parse("source-reference ends-with .dart")
    assertEquals(r, Right(Expr.EndsWith("source-reference", ".dart")))
  }

  // -- parse: quoted values ---------------------------------------------

  test("parse: double-quoted value") {
    val r = Predicate.parse("""status = "in progress" """)
    assertEquals(r, Right(Expr.Eq("status", "in progress")))
  }

  test("parse: single-quoted value") {
    val r = Predicate.parse("status = 'in progress'")
    assertEquals(r, Right(Expr.Eq("status", "in progress")))
  }

  test("parse: empty quoted value") {
    val r = Predicate.parse("""original-src = "" """)
    assertEquals(r, Right(Expr.Eq("original-src", "")))
  }

  // -- parse: boolean operators -----------------------------------------

  test("parse: a=1 && b=2") {
    val r = Predicate.parse("a=1 && b=2")
    assertEquals(r, Right(Expr.And(Expr.Eq("a", "1"), Expr.Eq("b", "2"))))
  }

  test("parse: a=1 || b=2") {
    val r = Predicate.parse("a=1 || b=2")
    assertEquals(r, Right(Expr.Or(Expr.Eq("a", "1"), Expr.Eq("b", "2"))))
  }

  test("parse: && has higher precedence than ||") {
    val r = Predicate.parse("a=1 || b=2 && c=3")
    assertEquals(r, Right(
      Expr.Or(
        Expr.Eq("a", "1"),
        Expr.And(Expr.Eq("b", "2"), Expr.Eq("c", "3"))
      )
    ))
  }

  test("parse: negation") {
    val r = Predicate.parse("!status=ported")
    assertEquals(r, Right(Expr.Not(Expr.Eq("status", "ported"))))
  }

  test("parse: parentheses override precedence") {
    val r = Predicate.parse("(a=1 || b=2) && c=3")
    assertEquals(r, Right(
      Expr.And(
        Expr.Or(Expr.Eq("a", "1"), Expr.Eq("b", "2")),
        Expr.Eq("c", "3")
      )
    ))
  }

  test("parse: negation of parenthesized expr") {
    val r = Predicate.parse("!(a=1 && b=2)")
    assertEquals(r, Right(
      Expr.Not(Expr.And(Expr.Eq("a", "1"), Expr.Eq("b", "2")))
    ))
  }

  test("parse: three-way AND") {
    val r = Predicate.parse("a=1 && b=2 && c=3")
    assertEquals(r, Right(
      Expr.And(Expr.And(Expr.Eq("a", "1"), Expr.Eq("b", "2")), Expr.Eq("c", "3"))
    ))
  }

  // -- parse: errors ----------------------------------------------------

  test("parse: malformed input returns Left") {
    val r = Predicate.parse("&& oops")
    assert(r.isLeft, s"expected Left, got $r")
  }

  test("parse: empty input returns Left") {
    val r = Predicate.parse("")
    assert(r.isLeft)
  }

  // -- evaluate ---------------------------------------------------------

  private val props = Map(
    "covenant"         -> "full-port",
    "status"           -> "ported",
    "authors"          -> "alice,bob",
    "source-reference" -> "lib/src/foo.dart",
    "baseline-loc"     -> "1153"
  )

  test("evaluate: Eq match") {
    assert(Predicate.evaluate(Expr.Eq("covenant", "full-port"), props))
  }

  test("evaluate: Eq mismatch") {
    assert(!Predicate.evaluate(Expr.Eq("covenant", "partial"), props))
  }

  test("evaluate: Eq absent field") {
    assert(Predicate.evaluate(Expr.Eq("missing", ""), props))
  }

  test("evaluate: Neq") {
    assert(Predicate.evaluate(Expr.Neq("status", "pending"), props))
  }

  test("evaluate: Contains substring") {
    assert(Predicate.evaluate(Expr.Contains("source-reference", "src/foo"), props))
  }

  test("evaluate: Contains in comma-separated list") {
    assert(Predicate.evaluate(Expr.Contains("authors", "alice"), props))
    assert(Predicate.evaluate(Expr.Contains("authors", "bob"), props))
    assert(!Predicate.evaluate(Expr.Contains("authors", "charlie"), props))
  }

  test("evaluate: StartsWith") {
    assert(Predicate.evaluate(Expr.StartsWith("source-reference", "lib/src/"), props))
    assert(!Predicate.evaluate(Expr.StartsWith("source-reference", "src/"), props))
  }

  test("evaluate: EndsWith") {
    assert(Predicate.evaluate(Expr.EndsWith("source-reference", ".dart"), props))
    assert(!Predicate.evaluate(Expr.EndsWith("source-reference", ".scala"), props))
  }

  test("evaluate: And") {
    val expr = Expr.And(Expr.Eq("covenant", "full-port"), Expr.Eq("status", "ported"))
    assert(Predicate.evaluate(expr, props))
    val expr2 = Expr.And(Expr.Eq("covenant", "full-port"), Expr.Eq("status", "pending"))
    assert(!Predicate.evaluate(expr2, props))
  }

  test("evaluate: Or") {
    val expr = Expr.Or(Expr.Eq("status", "pending"), Expr.Eq("status", "ported"))
    assert(Predicate.evaluate(expr, props))
  }

  test("evaluate: Not") {
    assert(Predicate.evaluate(Expr.Not(Expr.Eq("status", "pending")), props))
    assert(!Predicate.evaluate(Expr.Not(Expr.Eq("status", "ported")), props))
  }

  // -- parse + evaluate round-trip --------------------------------------

  test("round-trip: complex predicate") {
    val input = "covenant=full-port && authors contains alice && !status=pending"
    val parsed = Predicate.parse(input)
    assert(parsed.isRight, s"parse failed: $parsed")
    assert(Predicate.evaluate(parsed.toOption.get, props))
  }
}
