/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.fileinfo

import munit.CatsEffectSuite
import rescale.common.Tsv
import rescale.common.Tsv.Table

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class SyncCheckSpec extends CatsEffectSuite {

  private def tmpDir(): File = {
    val dir = NIOFiles.createTempDirectory("synccheck-").toFile
    dir.deleteOnExit()
    dir
  }

  private def writeFile(dir: File, relPath: String, content: String): File = {
    val f = new File(dir, relPath)
    f.getParentFile.mkdirs()
    f.deleteOnExit()
    val w = new PrintWriter(f)
    try w.write(content)
    finally w.close()
    f
  }

  private def writeMigration(dir: File, rows: List[Map[String, String]]): File = {
    val dataDir = new File(dir, ".rescale/data")
    dataDir.mkdirs()
    val f = new File(dataDir, "migration.tsv")
    f.deleteOnExit()
    val headers = List("source_lib", "source_path", "ssg_path", "status",
      "module", "last_updated", "notes", "source_sync_commit", "last_sync_date")
    val table = Table(headers, rows, List("# re-scale Migration Database"))
    Tsv.write(f, table).unsafeRunSync()
    f
  }

  test("no mismatches when file and DB agree") {
    val dir = tmpDir()
    new File(dir, ".rescale").mkdir()
    writeFile(dir, "src/Foo.scala",
      """/*
        | * Copyright 2026
        | * original-src: lib/foo.dart
        | * upstream-commit: abc123
        | * status: ported
        | */
        |package x
        |""".stripMargin
    )
    writeMigration(dir, List(Map(
      "source_path" -> "lib/foo.dart",
      "ssg_path" -> "src/Foo.scala",
      "status" -> "ported",
      "source_sync_commit" -> "abc123"
    )))

    val files = fs2.Stream.emit(fs2.io.file.Path.fromNioPath(new File(dir, "src/Foo.scala").toPath))
    SyncCheck.check(dir, files, None, mr = true).map { code =>
      assertEquals(code.code, 0)
    }
  }

  test("detects commit mismatch") {
    val dir = tmpDir()
    new File(dir, ".rescale").mkdir()
    writeFile(dir, "src/Bar.scala",
      """/*
        | * Copyright 2026
        | * original-src: lib/bar.dart
        | * upstream-commit: new456
        | */
        |package x
        |""".stripMargin
    )
    writeMigration(dir, List(Map(
      "source_path" -> "lib/bar.dart",
      "ssg_path" -> "src/Bar.scala",
      "status" -> "ported",
      "source_sync_commit" -> "old123"
    )))

    val files = fs2.Stream.emit(fs2.io.file.Path.fromNioPath(new File(dir, "src/Bar.scala").toPath))
    SyncCheck.check(dir, files, None, mr = true).map { code =>
      assertEquals(code.code, 1)
    }
  }
}
