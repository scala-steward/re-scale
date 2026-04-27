/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.fileinfo

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class FileHeaderSpec extends CatsEffectSuite {

  private def writeFile(name: String, content: String): File = {
    val tmp = NIOFiles.createTempFile("fileheader-", "-" + name).toFile
    tmp.deleteOnExit()
    val w = new PrintWriter(tmp)
    try w.write(content)
    finally w.close()
    tmp
  }

  test("parses Covenant fields with normalized keys") {
    val f = writeFile("a.scala",
      """/*
        | * Copyright 2026 acme
        | * SPDX-License-Identifier: Apache-2.0
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
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.properties("covenant"), "full-port")
      assertEquals(fp.properties("baseline-spec-pass"), "4157")
      assertEquals(fp.properties("baseline-loc"), "1153")
      assertEquals(fp.properties("baseline-methods"), "foo,bar,baz")
      assertEquals(fp.properties("source-reference"), "lib/src/foo.dart")
      assertEquals(fp.properties("verified"), "2026-04-08")
    }
  }

  test("skips Copyright and SPDX-License-Identifier") {
    val f = writeFile("b.scala",
      """/*
        | * Copyright: 2026 acme
        | * SPDX-License-Identifier: Apache-2.0
        | * Covenant: full-port
        | */
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assert(!fp.properties.contains("copyright"))
      assert(!fp.properties.contains("spdx-license-identifier"))
      assertEquals(fp.properties("covenant"), "full-port")
    }
  }

  test("parses plain key-value fields (no prefix)") {
    val f = writeFile("c.scala",
      """/*
        | * Copyright 2026 acme
        | *
        | * original-src: lib/src/environment.dart
        | * authors: alice,bob
        | * upstream-commit: a1b2c3d
        | * module: ssg-md
        | * status: ported
        | */
        |package x
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.properties("original-src"), "lib/src/environment.dart")
      assertEquals(fp.properties("authors"), "alice,bob")
      assertEquals(fp.properties("upstream-commit"), "a1b2c3d")
      assertEquals(fp.properties("module"), "ssg-md")
      assertEquals(fp.properties("status"), "ported")
    }
  }

  test("parses mixed Covenant and plain fields") {
    val f = writeFile("d.scala",
      """/*
        | * Copyright 2026 acme
        | *
        | * Covenant: full-port
        | * Covenant-baseline-loc: 500
        | * Covenant-source-reference: lib/src/bar.dart
        | * original-src: lib/src/bar.dart
        | * authors: charlie
        | * upstream-commit: deadbeef
        | */
        |package x
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.properties("covenant"), "full-port")
      assertEquals(fp.properties("baseline-loc"), "500")
      assertEquals(fp.properties("source-reference"), "lib/src/bar.dart")
      assertEquals(fp.properties("original-src"), "lib/src/bar.dart")
      assertEquals(fp.properties("authors"), "charlie")
      assertEquals(fp.properties("upstream-commit"), "deadbeef")
    }
  }

  test("backward-compat: Covenant-dart-reference normalizes to source-reference") {
    val f = writeFile("e.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-dart-reference: lib/src/old.dart
        | */
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.properties("source-reference"), "lib/src/old.dart")
      assertEquals(fp.rawKeys("source-reference"), "Covenant-dart-reference")
    }
  }

  test("rawKeys maps normalized key back to original form") {
    val f = writeFile("f.scala",
      """/*
        | * Covenant: full-port
        | * Covenant-baseline-loc: 100
        | * original-src: lib/foo.dart
        | */
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.rawKeys("covenant"), "Covenant")
      assertEquals(fp.rawKeys("baseline-loc"), "Covenant-baseline-loc")
      assertEquals(fp.rawKeys("original-src"), "original-src")
    }
  }

  test("returns empty properties for file without block comment") {
    val f = writeFile("g.scala",
      """package x
        |class Y { def z: Int = 1 }
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assert(fp.properties.isEmpty)
    }
  }

  test("returns empty properties for file with no key-value lines in comment") {
    val f = writeFile("h.scala",
      """/*
        | * This is just a regular comment.
        | * No key-value pairs here.
        | */
        |package x
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assert(fp.properties.isEmpty)
    }
  }

  test("handles line-comment style headers") {
    val f = writeFile("i.scala",
      """// Covenant: full-port
        |// original-src: lib/foo.dart
        |package x
        |""".stripMargin
    )
    FileHeader.parse(f).map { fp =>
      assertEquals(fp.properties("covenant"), "full-port")
      assertEquals(fp.properties("original-src"), "lib/foo.dart")
    }
  }
}
