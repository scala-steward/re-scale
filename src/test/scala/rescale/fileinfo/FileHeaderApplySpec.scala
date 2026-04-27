/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.fileinfo

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class FileHeaderApplySpec extends CatsEffectSuite {

  private def writeFile(name: String, content: String): File = {
    val tmp = NIOFiles.createTempFile("fhapply-", "-" + name).toFile
    tmp.deleteOnExit()
    val w = new PrintWriter(tmp)
    try w.write(content)
    finally w.close()
    tmp
  }

  private def readBack(file: File): String =
    new String(NIOFiles.readAllBytes(file.toPath))

  test("creates new key-value lines when none exist") {
    val f = writeFile("a.scala",
      """/*
        | * Copyright 2026 acme
        | * SPDX-License-Identifier: Apache-2.0
        | */
        |package x
        |""".stripMargin
    )
    FileHeaderApply.setProperties(f, Map("original-src" -> "lib/foo.dart", "status" -> "ported")).flatMap { r =>
      assertEquals(r.action, "created")
      FileHeader.parse(f).map { fp =>
        assertEquals(fp.properties("original-src"), "lib/foo.dart")
        assertEquals(fp.properties("status"), "ported")
      }
    }
  }

  test("updates existing key-value lines") {
    val f = writeFile("b.scala",
      """/*
        | * Copyright 2026 acme
        | * original-src: lib/old.dart
        | * status: pending
        | */
        |package x
        |""".stripMargin
    )
    FileHeaderApply.setProperties(f, Map("status" -> "ported")).flatMap { r =>
      assertEquals(r.action, "updated")
      FileHeader.parse(f).map { fp =>
        assertEquals(fp.properties("status"), "ported")
        assertEquals(fp.properties("original-src"), "lib/old.dart")
      }
    }
  }

  test("preserves Covenant-* prefix when updating covenant fields") {
    val f = writeFile("c.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-baseline-loc: 100
        | */
        |package x
        |""".stripMargin
    )
    FileHeaderApply.setProperties(f, Map("baseline-loc" -> "200")).flatMap { r =>
      val content = readBack(f)
      assert(content.contains("Covenant-baseline-loc: 200"), s"Expected Covenant prefix preserved, got:\n$content")
      FileHeader.parse(f).map { fp =>
        assertEquals(fp.properties("baseline-loc"), "200")
        assertEquals(fp.properties("covenant"), "full-port")
      }
    }
  }

  test("coexists with existing Covenant header") {
    val f = writeFile("d.scala",
      """/*
        | * Copyright 2026 acme
        | *
        | * Covenant: full-port
        | * Covenant-source-reference: lib/src/foo.dart
        | */
        |package x
        |""".stripMargin
    )
    FileHeaderApply.setProperties(f, Map("upstream-commit" -> "abc123")).flatMap { _ =>
      FileHeader.parse(f).map { fp =>
        assertEquals(fp.properties("covenant"), "full-port")
        assertEquals(fp.properties("source-reference"), "lib/src/foo.dart")
        assertEquals(fp.properties("upstream-commit"), "abc123")
      }
    }
  }

  test("round-trip: write then parse back") {
    val f = writeFile("e.scala",
      """/*
        | * Copyright 2026 acme
        | */
        |package x
        |""".stripMargin
    )
    val updates = Map(
      "original-src" -> "lib/env.dart",
      "authors" -> "alice,bob",
      "status" -> "ported",
      "upstream-commit" -> "deadbeef"
    )
    FileHeaderApply.setProperties(f, updates).flatMap { _ =>
      FileHeader.parse(f).map { fp =>
        updates.foreach { case (k, v) =>
          assertEquals(fp.properties.getOrElse(k, ""), v, s"mismatch for key '$k'")
        }
      }
    }
  }

  test("throws on file without block comment") {
    val f = writeFile("f.scala",
      """package x
        |class Y
        |""".stripMargin
    )
    FileHeaderApply.setProperties(f, Map("status" -> "ported"))
      .attempt
      .map {
        case Left(e) => assert(e.getMessage.contains("No block comment"))
        case Right(_) => fail("expected exception")
      }
  }
}
