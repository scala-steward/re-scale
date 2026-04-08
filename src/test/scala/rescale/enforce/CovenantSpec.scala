/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the Covenant header parser. Both Covenant-source-reference
 * (worktree) and Covenant-dart-reference (main repo's sass-port branch)
 * must round-trip into the same `sourceReference` field.
 */
package rescale.enforce

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class CovenantSpec extends CatsEffectSuite {

  private def writeFile(name: String, content: String): File = {
    val tmp = NIOFiles.createTempFile("covenant-", "-" + name).toFile
    tmp.deleteOnExit()
    val w = new PrintWriter(tmp)
    try w.write(content)
    finally w.close()
    tmp
  }

  test("parses a full-port covenant header (source-reference field)") {
    val f = writeFile("a.scala",
      """/*
        | * Copyright 2026 acme
        | *
        | * Covenant: full-port
        | * Covenant-baseline-spec-pass: 4157
        | * Covenant-baseline-loc: 1153
        | * Covenant-baseline-methods: foo,bar,baz
        | * Covenant-source-reference: lib/src/foo.dart
        | * Covenant-verified: 2026-04-08
        | */
        |package x
        |""".stripMargin
    )
    Covenant.parse(f).map { hdr =>
      assert(hdr.isDefined, "expected covenant to parse")
      val h = hdr.get
      assertEquals(h.covenant, "full-port")
      assertEquals(h.baselineSpecPass, 4157)
      assertEquals(h.baselineLoc, 1153)
      assertEquals(h.baselineMethods, Set("foo", "bar", "baz"))
      assertEquals(h.sourceReference, "lib/src/foo.dart")
      assertEquals(h.verified, "2026-04-08")
    }
  }

  test("backward-compat: parses Covenant-dart-reference into sourceReference") {
    val f = writeFile("a.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-dart-reference: lib/src/old.dart
        | */
        |""".stripMargin
    )
    Covenant.parse(f).map { hdr =>
      assert(hdr.isDefined)
      assertEquals(hdr.get.sourceReference, "lib/src/old.dart")
    }
  }

  test("returns None when no Covenant: line is present") {
    val f = writeFile("a.scala",
      """/*
        | * Copyright 2026 acme
        | * (no covenant header at all)
        | */
        |package x
        |""".stripMargin
    )
    Covenant.parse(f).map { hdr =>
      assertEquals(hdr, None)
    }
  }

  test("verify on a non-covenanted file returns Left(no covenant header)") {
    val f = writeFile("a.scala",
      """package x
        |class Y { def z: Int = 1 }
        |""".stripMargin
    )
    Covenant.verify(f).map {
      case Left(reason) => assert(reason.contains("no covenant"))
      case Right(_)     => fail("expected Left")
    }
  }

  test("verify on a partial-port covenant returns Right (only full-port enforced)") {
    val f = writeFile("a.scala",
      """/*
        | * Covenant: partial-port
        | * Covenant-baseline-methods: foo
        | */
        |object Y { def z: Int = 1 }
        |""".stripMargin
    )
    Covenant.verify(f).map {
      case Right(()) => ()
      case Left(r)   => fail(s"expected Right; got Left($r)")
    }
  }

  test("verify on a full-port file with no missing methods passes") {
    val f = writeFile("a.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-baseline-methods: alpha,beta
        | */
        |object Y {
        |  def alpha: Int = 1
        |  def beta: Int = 2
        |}
        |""".stripMargin
    )
    Covenant.verify(f).map {
      case Right(()) => ()
      case Left(r)   => fail(s"expected Right; got Left($r)")
    }
  }

  test("verify on a full-port file with a removed method fails") {
    val f = writeFile("a.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-baseline-methods: alpha,beta,gamma
        | */
        |object Y {
        |  def alpha: Int = 1
        |  def beta: Int = 2
        |}
        |""".stripMargin
    )
    Covenant.verify(f).map {
      case Left(reason) => assert(reason.contains("gamma"))
      case Right(_)     => fail("expected Left for removed method")
    }
  }
}
