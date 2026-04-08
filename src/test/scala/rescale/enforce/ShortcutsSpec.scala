/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the Shortcuts pattern scanner. Uses inline synthetic
 * fixtures so the tests are hermetic and the assertions can pin exact
 * hit counts.
 */
package rescale.enforce

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class ShortcutsSpec extends CatsEffectSuite {

  private def writeFile(name: String, content: String): File = {
    val tmp = NIOFiles.createTempFile("shortcuts-", "-" + name).toFile
    tmp.deleteOnExit()
    val w = new PrintWriter(tmp)
    try w.write(content)
    finally w.close()
    tmp
  }

  // -- Always rules ---------------------------------------------------

  test("flags TODO/FIXME/HACK/XXX") {
    val f = writeFile("a.scala",
      """package x
        |class A {
        |  def foo: Int = 1 // TODO
        |  def bar: Int = 2 // FIXME
        |  def baz: Int = 3 // HACK
        |  def qux: Int = 4 // XXX
        |}
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      val patterns = hits.map(_.pattern).toSet
      assert(patterns.contains("todo"))
      assert(patterns.contains("fixme"))
      assert(patterns.contains("hack"))
      assert(patterns.contains("xxx"))
    }
  }

  test("flags ??? scala-unimpl marker") {
    val f = writeFile("u.scala",
      """package x
        |object U { def thing: Int = ??? }
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      assert(hits.exists(_.pattern == "scala-unimpl"))
    }
  }

  test("flags UnsupportedOperationException") {
    val f = writeFile("u.scala",
      """package x
        |object U { def thing: Int = throw new UnsupportedOperationException("nope") }
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      assert(hits.exists(_.pattern == "unsupported-op"))
    }
  }

  test("flags Phase 1 anti-cheat null casts") {
    val f = writeFile("c.scala",
      """package x
        |object C {
        |  val a = null.asInstanceOf[String]
        |  val b: String = Nullable.empty.getOrElse(null)
        |  val c = list.headOption.getOrElse(null)
        |}
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      val patterns = hits.map(_.pattern).toSet
      assert(patterns.contains("null-cast"))
      assert(patterns.contains("nullable-null-fallback"))
      assert(patterns.contains("get-or-else-null"))
    }
  }

  test("flags throw-not-yet exception pattern") {
    val f = writeFile("ny.scala",
      """package x
        |object N {
        |  def call(): Int = throw new RuntimeException("not yet implemented")
        |}
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      assert(hits.exists(_.pattern == "throw-not-yet"))
    }
  }

  // -- CommentOnly rules ----------------------------------------------

  test("comment-only: 'simplified' in a comment counts; in code does not") {
    val f = writeFile("s.scala",
      """package x
        |object S {
        |  // simplified version of upstream
        |  val simplified = 42
        |}
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      val simplified = hits.filter(_.pattern == "simplified-comment")
      assertEquals(simplified.size, 1, s"hits = $hits")
    }
  }

  test("Phase 1 comment-only: 'for now', 'would be used here', 'handled below'") {
    val f = writeFile("c.scala",
      """package x
        |object C {
        |  // for now we punt on this
        |  // would be used here if available
        |  // handled below
        |  def stub(): Int = 0
        |}
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      val patterns = hits.map(_.pattern).toSet
      assert(patterns.contains("for-now-comment"))
      assert(patterns.contains("would-be-used"))
      assert(patterns.contains("handled-below"))
    }
  }

  test("Apache header is skipped (no false positives on TODO in license)") {
    val f = writeFile("h.scala",
      """/*
        | * Copyright 2026 acme
        | * TODO this would be a false positive without skip
        | */
        |package x
        |class H { def y: Int = 1 }
        |""".stripMargin
    )
    Shortcuts.scanFile(f).map { hits =>
      assert(hits.isEmpty, s"expected no hits inside header; got $hits")
    }
  }

  test("scanFile on a non-existent path returns empty") {
    Shortcuts.scanFile(new File("/tmp/does-not-exist-rescale-test.scala")).attempt.map {
      case Left(_)  => ()  // streamLines on a missing file errors — that's fine
      case Right(h) => assert(h.isEmpty)
    }
  }
}
