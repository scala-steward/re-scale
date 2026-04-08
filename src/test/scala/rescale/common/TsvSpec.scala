/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests the atomic-locked TSV reader/writer. Covers:
 *   - header parsing with comment support
 *   - round-trip of simple rows
 *   - CSV-aware tab splitter with quoted cells
 *   - modify acquires + releases lock on success
 *   - parallel modify serializes cleanly (no torn rows, no phantom rows)
 *   - strict rejection of data before header
 */
package rescale.common

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import rescale.common.Tsv.Table

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class TsvSpec extends CatsEffectSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("rescale-tsv-").toFile
    d.deleteOnExit()
    d
  }

  private def writeRaw(f: File, content: String): File = {
    val w = new PrintWriter(f)
    try w.write(content)
    finally w.close()
    f.deleteOnExit()
    f
  }

  // -- splitFields -----------------------------------------------------

  test("splitFields splits on tabs") {
    assertEquals(Tsv.splitFields("a\tb\tc"), List("a", "b", "c"))
  }

  test("splitFields handles empty cells") {
    assertEquals(Tsv.splitFields("a\t\tc"), List("a", "", "c"))
  }

  test("splitFields respects quoted cells with embedded tabs") {
    assertEquals(
      Tsv.splitFields("a\t\"b\twith\ttabs\"\tc"),
      List("a", "b\twith\ttabs", "c")
    )
  }

  test("splitFields handles escaped quotes") {
    assertEquals(
      Tsv.splitFields("a\t\"contains \"\"quotes\"\"\"\tc"),
      List("a", "contains \"quotes\"", "c")
    )
  }

  // -- read ------------------------------------------------------------

  test("read parses a well-formed TSV") {
    val f = writeRaw(
      new File(tempDir(), "good.tsv"),
      """# comment1
        |# comment2
        |# id	name	status
        |1	alice	active
        |2	bob	inactive
        |""".stripMargin
    )
    Tsv.read(f).map { t =>
      assertEquals(t.headers, List("id", "name", "status"))
      assertEquals(t.rows.size, 2)
      assertEquals(t.rows(0), Map("id" -> "1", "name" -> "alice", "status" -> "active"))
      assertEquals(t.rows(1), Map("id" -> "2", "name" -> "bob", "status" -> "inactive"))
      // The first two commentN lines are kept; the header is NOT counted as a comment.
      assert(t.comments.contains("# comment1"))
      assert(t.comments.contains("# comment2"))
    }
  }

  test("read throws on data before header") {
    val f = writeRaw(
      new File(tempDir(), "bad.tsv"),
      """1	alice	active
        |# id	name	status
        |""".stripMargin
    )
    Tsv.read(f).attempt.map {
      case Left(t) => assert(t.getMessage.contains("data row before header"))
      case Right(_) => fail("expected read to fail")
    }
  }

  test("read handles missing trailing newline") {
    val f = writeRaw(
      new File(tempDir(), "no-newline.tsv"),
      "# id\tname\n1\talice"
    )
    Tsv.read(f).map { t =>
      assertEquals(t.rows.size, 1)
      assertEquals(t.rows(0), Map("id" -> "1", "name" -> "alice"))
    }
  }

  // -- write -----------------------------------------------------------

  test("write + read round-trips") {
    val f = new File(tempDir(), "rt.tsv")
    val table = Table(
      headers  = List("id", "name"),
      rows     = List(Map("id" -> "1", "name" -> "alice"), Map("id" -> "2", "name" -> "bob")),
      comments = List("# fixture")
    )
    (Tsv.write(f, table) *> Tsv.read(f)).map { roundTripped =>
      assertEquals(roundTripped.headers, table.headers)
      assertEquals(roundTripped.rows, table.rows)
      assert(roundTripped.comments.contains("# fixture"))
    }
  }

  test("write quotes cells containing tabs") {
    val f = new File(tempDir(), "quoted.tsv")
    val table = Table(
      headers = List("id", "notes"),
      rows    = List(Map("id" -> "1", "notes" -> "has\ttab"))
    )
    (Tsv.write(f, table) *> Tsv.read(f)).map { rt =>
      assertEquals(rt.rows.head.get("notes"), Some("has\ttab"))
    }
  }

  // -- modify ----------------------------------------------------------

  test("modify creates a new file when missing") {
    val f = new File(tempDir(), "new.tsv")
    val updated = Tsv.modify(f) { t =>
      // Fresh table has empty headers/rows — install a schema on first write.
      val hs = List("id", "name")
      val r  = Map("id" -> "1", "name" -> "alice")
      Table(hs, List(r))
    }
    updated.flatMap { t =>
      Tsv.read(f).map { roundTripped =>
        assertEquals(roundTripped.headers, List("id", "name"))
        assertEquals(roundTripped.rows.head, Map("id" -> "1", "name" -> "alice"))
      }
    }
  }

  test("modify appends a row to an existing table") {
    val f = writeRaw(
      new File(tempDir(), "existing.tsv"),
      """# id	name
        |1	alice
        |""".stripMargin
    )
    val io = Tsv.modify(f)(t => t.addRow(Map("id" -> "2", "name" -> "bob"))) *>
      Tsv.read(f)
    io.map { t =>
      assertEquals(t.rows.size, 2)
      assertEquals(t.rows(1), Map("id" -> "2", "name" -> "bob"))
    }
  }

  test("parallel modify writers serialize without torn rows") {
    val f = writeRaw(
      new File(tempDir(), "race.tsv"),
      "# id\tnotes\n"
    )
    // 20 concurrent writers each adding a row. If the lock is broken
    // we'd see rows < 20, a phantom row, or a torn line.
    val writers = (1 to 20).toList.parTraverse { i =>
      Tsv.modify(f)(t => t.addRow(Map("id" -> i.toString, "notes" -> s"writer-$i")))
    }
    (writers *> Tsv.read(f)).map { t =>
      assertEquals(t.rows.size, 20, "expected exactly 20 rows after 20 parallel writers")
      val ids = t.rows.flatMap(_.get("id")).toSet
      assertEquals(ids, (1 to 20).map(_.toString).toSet, "expected all 20 ids present")
    }
  }
}
