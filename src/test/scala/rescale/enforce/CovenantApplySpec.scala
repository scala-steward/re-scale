/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.enforce

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class CovenantApplySpec extends CatsEffectSuite {

  private def tempFile(content: String): File = {
    val f = NIOFiles.createTempFile("covenant-apply-", ".scala").toFile
    f.deleteOnExit()
    val w = new PrintWriter(f)
    try w.print(content)
    finally w.close()
    f
  }

  private val sampleScala: String =
    """/*
      | * Copyright (c) 2026 Example
      | * SPDX-License-Identifier: Apache-2.0
      | */
      |package example
      |
      |object Foo {
      |  def alpha: Int = 1
      |  def beta(x: String): String = x
      |  val gamma: Boolean = true
      |}
      |""".stripMargin

  test("covenant-apply creates header in a file without one") {
    val f = tempFile(sampleScala)
    CovenantApply.apply(f, "src/foo.java", specPass = Some(42), force = true).map { r =>
      assertEquals(r.action, "created")
      assert(r.methods.contains("alpha"))
      assert(r.methods.contains("beta"))
      // Verify the file now has Covenant fields
      val content = scala.io.Source.fromFile(f).mkString
      assert(content.contains("Covenant: full-port"), s"missing Covenant field in:\n$content")
      assert(content.contains("Covenant-baseline-methods:"), s"missing methods in:\n$content")
      assert(content.contains("Covenant-source-reference: src/foo.java"), s"wrong source ref in:\n$content")
      assert(content.contains("Covenant-baseline-spec-pass: 42"), s"wrong spec-pass in:\n$content")
      assert(content.contains("Covenant-baseline-loc:"), s"missing loc in:\n$content")
    }
  }

  test("covenant-apply updates existing header") {
    val withCovenant =
      """/*
        | * Copyright (c) 2026 Example
        | * SPDX-License-Identifier: Apache-2.0
        | *
        | * Covenant: full-port
        | * Covenant-baseline-spec-pass: 10
        | * Covenant-baseline-loc: 5
        | * Covenant-baseline-methods: old1,old2
        | * Covenant-source-reference: old/path.dart
        | * Covenant-verified: 2025-01-01
        | */
        |package example
        |
        |object Foo {
        |  def newMethod: Int = 1
        |}
        |""".stripMargin
    val f = tempFile(withCovenant)
    CovenantApply.apply(f, "new/path.dart", specPass = Some(99), force = true).map { r =>
      assertEquals(r.action, "updated")
      val content = scala.io.Source.fromFile(f).mkString
      assert(content.contains("Covenant-source-reference: new/path.dart"))
      assert(content.contains("Covenant-baseline-spec-pass: 99"))
      assert(content.contains("newMethod"))
      // Old values should be gone
      assert(!content.contains("old1,old2"))
      assert(!content.contains("old/path.dart"))
      assert(!content.contains("2025-01-01"))
    }
  }

  test("covenant-apply dry-run does not modify file") {
    val f = tempFile(sampleScala)
    val before = scala.io.Source.fromFile(f).mkString
    CovenantApply.apply(f, "src/foo.java", dryRun = true, force = true).map { r =>
      assertEquals(r.action, "dry-run")
      val after = scala.io.Source.fromFile(f).mkString
      assertEquals(after, before, "dry-run should not modify the file")
    }
  }

  test("covenant-apply refuses file with shortcut hits (without --force)") {
    val withTodo =
      """/*
        | * Copyright (c) 2026 Example
        | */
        |package example
        |
        |object Foo {
        |  // TODO: implement properly
        |  def stub: Int = 0
        |}
        |""".stripMargin
    val f = tempFile(withTodo)
    CovenantApply.apply(f, "src/foo.java").map { r =>
      assert(r.action.startsWith("refused"), s"expected refusal, got: ${r.action}")
    }
  }

  test("covenant-apply with --force stamps file despite shortcut hits") {
    val withTodo =
      """/*
        | * Copyright (c) 2026 Example
        | */
        |package example
        |
        |object Foo {
        |  // TODO: implement properly
        |  def stub: Int = 0
        |}
        |""".stripMargin
    val f = tempFile(withTodo)
    CovenantApply.apply(f, "src/foo.java", force = true).map { r =>
      assertEquals(r.action, "created")
      val content = scala.io.Source.fromFile(f).mkString
      assert(content.contains("Covenant: full-port"))
    }
  }

  test("created covenant can be parsed back by Covenant.parse") {
    val f = tempFile(sampleScala)
    for {
      _      <- CovenantApply.apply(f, "src/foo.java", specPass = Some(100), force = true)
      parsed <- Covenant.parse(f)
    } yield {
      assert(parsed.isDefined, "Covenant.parse should find the header")
      val h = parsed.get
      assertEquals(h.covenant, "full-port")
      assertEquals(h.baselineSpecPass, 100)
      assertEquals(h.sourceReference, "src/foo.java")
      assert(h.baselineMethods.contains("alpha"))
      assert(h.baselineMethods.contains("beta"))
    }
  }

  test("created covenant passes Covenant.verify") {
    val f = tempFile(sampleScala)
    for {
      _      <- CovenantApply.apply(f, "src/foo.java", force = true)
      result <- Covenant.verify(f)
    } yield {
      assert(result.isRight, s"verify failed: $result")
    }
  }
}
