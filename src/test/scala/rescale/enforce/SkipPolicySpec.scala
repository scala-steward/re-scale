/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the skip-policy CRUD + matcher.
 */
package rescale.enforce

import munit.CatsEffectSuite

import java.io.File
import java.nio.file.Files as NIOFiles

final class SkipPolicySpec extends CatsEffectSuite {

  private def tempFile(): File = {
    val f = NIOFiles.createTempFile("skippolicy-", ".tsv").toFile
    f.delete() // start fresh
    f.deleteOnExit()
    f
  }

  test("Entry.matches: file path equality") {
    val e = SkipPolicy.Entry("ssg-md/foo.scala", "shortcuts", "vendored", "2026-04-08", "dev")
    assert(e.matches("ssg-md/foo.scala", "shortcuts"))
    assert(!e.matches("ssg-md/bar.scala", "shortcuts"))
  }

  test("Entry.matches: directory entry covers descendants") {
    val e = SkipPolicy.Entry("ssg-sass/generated/", "*", "auto-generated", "2026-04-08", "dev")
    assert(e.matches("ssg-sass/generated/colors.scala", "shortcuts"))
    assert(e.matches("ssg-sass/generated/themes/dark.scala", "stale-stubs"))
    assert(!e.matches("ssg-sass/handwritten.scala", "shortcuts"))
  }

  test("Entry.matches: tool wildcard '*' matches any tool") {
    val e = SkipPolicy.Entry("a.scala", "*", "x", "2026-04-08", "dev")
    assert(e.matches("a.scala", "shortcuts"))
    assert(e.matches("a.scala", "stale-stubs"))
    assert(e.matches("a.scala", "verify"))
  }

  test("read returns Nil when the file does not exist") {
    val f = tempFile()
    SkipPolicy.read(f).map(entries => assertEquals(entries, Nil))
  }

  test("add then read round-trips") {
    val f = tempFile()
    val e = SkipPolicy.Entry("ssg-md/foo.scala", "shortcuts", "vendored", "2026-04-08", "dev")
    for {
      _       <- SkipPolicy.add(f, e)
      entries <- SkipPolicy.read(f)
    } yield {
      assertEquals(entries.size, 1)
      assertEquals(entries.head.path, "ssg-md/foo.scala")
      assertEquals(entries.head.tool, "shortcuts")
    }
  }

  test("filter drops covered hits and keeps uncovered ones") {
    val policy = List(
      SkipPolicy.Entry("ssg-sass/generated/", "*", "x", "2026-04-08", "dev")
    )
    val hits = List(
      ("ssg-sass/generated/a.scala", 1),
      ("ssg-sass/handwritten.scala", 2),
      ("ssg-md/x.scala", 3)
    )
    val kept = SkipPolicy.filter[(String, Int)](policy, "shortcuts", _._1, hits)
    val keptPaths = kept.map(_._1)
    assert(!keptPaths.contains("ssg-sass/generated/a.scala"))
    assert(keptPaths.contains("ssg-sass/handwritten.scala"))
    assert(keptPaths.contains("ssg-md/x.scala"))
  }
}
