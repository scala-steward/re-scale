/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Concurrency stress test for IssuesDb.add — 20 parallel adds against
 * an empty issues.tsv. Asserts:
 *   - exactly 20 rows produced (no torn writes)
 *   - all 20 IDs are unique (no atomic-allocation race)
 *   - IDs form the contiguous sequence ISS-001..ISS-020
 */
package rescale.db

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import rescale.common.Tsv
import rescale.common.Tsv.Table

import java.io.File
import java.nio.file.Files as NIOFiles

final class IssuesDbConcurrencySpec extends CatsEffectSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("rescale-issuesdb-").toFile
    d.deleteOnExit()
    d
  }

  test("20 parallel modifyWith calls allocate unique IDs") {
    val issuesFile = new File(tempDir(), "issues.tsv")
    val headers = List("id", "file_path", "description")

    // Initialize with an empty table containing only the header.
    val init = Tsv.write(
      issuesFile,
      Table(headers, Nil, List("# test issues"))
    )

    val addOne: Int => IO[String] = i =>
      Tsv.modifyWith(issuesFile) { tbl =>
        val nextNum = {
          val existing = tbl.rows.flatMap(_.get("id")).filter(_.startsWith("ISS-"))
          if (existing.isEmpty) 1
          else existing.map(_.stripPrefix("ISS-").toIntOption.getOrElse(0)).max + 1
        }
        val id = f"ISS-$nextNum%03d"
        val row = Map(
          "id"          -> id,
          "file_path"   -> s"path-$i.scala",
          "description" -> s"writer $i"
        )
        (tbl.addRow(row), id)
      }

    val concurrentAdds: IO[List[String]] = (1 to 20).toList.parTraverse(addOne)

    val io = for {
      _      <- init
      ids    <- concurrentAdds
      reread <- Tsv.read(issuesFile)
    } yield (ids, reread)

    io.map { case (ids, reread) =>
      assertEquals(ids.size, 20, "expected 20 IDs allocated")
      assertEquals(ids.toSet.size, 20, "all 20 IDs must be unique")
      assertEquals(reread.rows.size, 20, "expected 20 rows in the file after the race")
      val rowIds = reread.rows.flatMap(_.get("id")).toSet
      assertEquals(rowIds, ids.toSet, "row IDs in file must match returned IDs")
      // The sequence should be exactly ISS-001..ISS-020
      val expected = (1 to 20).map(i => f"ISS-$i%03d").toSet
      assertEquals(rowIds, expected, "IDs should be the contiguous ISS-001..ISS-020")
    }
  }
}
