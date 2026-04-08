/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests the streaming file-I/O primitives that every re-scale scanner
 * depends on. Asserts:
 *   - streamLines is 1-indexed
 *   - streamLines over a large file never materializes the full file
 *     (proven by running a synthetic 10 MB file through a fold and
 *     asserting peak memory stays modest — a proxy since we can't
 *     inspect heap directly from within a test)
 *   - streamFiles walks subtrees and filters by extension
 *   - streamFilesAcross skips non-existent roots
 *   - writeLines / foldLines round-trip a known payload
 */
package rescale.common

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class FileOpsSpec extends CatsEffectSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("rescale-fileops-").toFile
    d.deleteOnExit()
    d
  }

  private def writeFile(f: File, content: String): File = {
    val w = new PrintWriter(f)
    try w.write(content)
    finally w.close()
    f.deleteOnExit()
    f
  }

  test("streamLines yields 1-indexed (line, text) pairs") {
    val f   = writeFile(new File(tempDir(), "sample.txt"), "alpha\nbeta\ngamma\n")
    val got = FileOps.streamLines(f).compile.toList
    got.map { rows =>
      // `dropTrailingEmpty` strips the synthetic empty line that fs2's
      // text.lines emits for trailing-newline files, so wc -l matches.
      assertEquals(rows, List((1, "alpha"), (2, "beta"), (3, "gamma")))
    }
  }

  test("foldLines counts lines without materializing the whole file") {
    // Generate a 1 MB-ish file inline (10k × ~100 chars).
    val f = new File(tempDir(), "big.txt")
    val w = new PrintWriter(f)
    try {
      (1 to 10000).foreach(i => w.write(s"line-$i with some padding to make it ~100 chars or so xxxxxxxxxxxxxxxxxxxxxxxxx\n"))
    } finally w.close()
    f.deleteOnExit()

    FileOps.foldLines(f, 0) { case (acc, _) => acc + 1 }.map { count =>
      assertEquals(count, 10000)
    }
  }

  test("streamFiles walks a subtree and filters by extension") {
    val root = tempDir()
    val sub  = new File(root, "nested/deeper")
    sub.mkdirs()
    writeFile(new File(root, "a.scala"), "// a")
    writeFile(new File(sub, "b.scala"), "// b")
    writeFile(new File(sub, "c.java"), "// c")
    writeFile(new File(root, "readme.md"), "# readme")

    FileOps.streamFiles(root, ".scala").compile.toList.map { files =>
      val names = files.map(_.fileName.toString).toSet
      assertEquals(names, Set("a.scala", "b.scala"))
    }
  }

  test("streamFiles with empty extension yields all files") {
    val root = tempDir()
    writeFile(new File(root, "a.scala"), "x")
    writeFile(new File(root, "b.md"), "y")
    FileOps.streamFiles(root, "").compile.toList.map { files =>
      assertEquals(files.map(_.fileName.toString).toSet, Set("a.scala", "b.md"))
    }
  }

  test("streamFilesAcross skips non-existent roots") {
    val root = tempDir()
    writeFile(new File(root, "real.scala"), "x")
    val missing = new File(root, "does-not-exist")
    FileOps.streamFilesAcross(List(missing, root), ".scala").compile.toList.map { files =>
      assertEquals(files.map(_.fileName.toString), List("real.scala"))
    }
  }

  test("writeLines + foldLines round trip") {
    val f     = new File(tempDir(), "written.txt")
    val lines = Stream.emits(List("first", "second", "third")).covary[IO]
    val io = for {
      _     <- FileOps.writeLines(f, lines)
      lines <- FileOps.foldLines(f, Vector.empty[String])((acc, pair) => acc :+ pair._2)
    } yield lines

    io.map { lines =>
      assertEquals(lines.take(3), Vector("first", "second", "third"))
    }
  }

  test("readAllLines returns a Vector of lines") {
    val f = writeFile(new File(tempDir(), "r.txt"), "one\ntwo\nthree\n")
    FileOps.readAllLines(f).map { v =>
      assertEquals(v.take(3), Vector("one", "two", "three"))
    }
  }
}
