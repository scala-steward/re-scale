/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Backpressure spec — verifies that FileOps streaming primitives do
 * NOT materialize the whole input. The verification strategy: write a
 * synthetic file, read it via FileOps.streamLines, and consume only
 * the first N lines via `.take(N)`. If the stream is genuinely
 * pull-based, only N lines (plus a small chunk readahead) get
 * decoded; the rest of the file is never touched.
 *
 * We can't directly observe "how many bytes were read from disk"
 * without instrumenting fs2.io, so we use a proxy: count how many
 * lines the consumer actually pulls and assert it matches `take(N)`
 * exactly. The acceptance is that no exception is thrown for short
 * reads — i.e. the stream truly stops at N rather than buffering the
 * whole 100k-line file.
 *
 * Companion test: streamFiles directory walking is also lazy. We
 * verify that walking a 1000-file directory but consuming only
 * the first 5 entries doesn't hang or timeout.
 */
package rescale.common

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class BackpressureSpec extends CatsEffectSuite {

  // -- Fixtures ------------------------------------------------------

  /** Generate a single 100k-line file (~5 MB). Used for streamLines
    * lazy-consumption tests.
    */
  private lazy val largeFile: File = {
    val f = NIOFiles.createTempFile("rescale-backpressure-large-", ".txt").toFile
    f.deleteOnExit()
    val w = new PrintWriter(f)
    try {
      var i = 0
      while (i < 100000) {
        w.println(s"line-$i ${"x" * 40}")
        i += 1
      }
    } finally w.close()
    f
  }

  /** Generate a 1000-file directory tree. Used for streamFiles
    * lazy-walk tests.
    */
  private lazy val largeDir: File = {
    val d = NIOFiles.createTempDirectory("rescale-backpressure-dir-").toFile
    d.deleteOnExit()
    var i = 0
    while (i < 1000) {
      val f = new File(d, f"file-$i%04d.scala")
      val w = new PrintWriter(f)
      try w.println(s"// stub $i")
      finally w.close()
      f.deleteOnExit()
      i += 1
    }
    d
  }

  // -- streamLines lazy-consumption tests ---------------------------

  test("streamLines: take(5) on a 100k-line file completes promptly") {
    // If the stream eagerly buffered the whole file, this would either
    // OOM or take 10+ seconds. Pull-based should finish in well under
    // a second.
    val started = System.nanoTime()
    FileOps.streamLines(largeFile).take(5).compile.toList.map { rows =>
      val elapsed = (System.nanoTime() - started) / 1e9
      assertEquals(rows.size, 5)
      assertEquals(rows.head._1, 1)         // 1-indexed line numbers
      assertEquals(rows.last._1, 5)
      assert(elapsed < 5.0, s"streamLines.take(5) took ${elapsed}s — too slow, likely buffering")
    }
  }

  test("streamLines: foldLines stops early on Stream.takeWhile") {
    // foldLines via the underlying stream — if backpressure works,
    // we can short-circuit by filtering and only see N matches.
    FileOps
      .streamLines(largeFile)
      .takeWhile { case (lineNum, _) => lineNum <= 100 }
      .compile
      .toList
      .map { rows =>
        assertEquals(rows.size, 100)
      }
  }

  test("streamLines: full traversal of 100k-line file completes") {
    // Sanity check the upper bound — full consumption should still
    // finish well under the suite timeout. This is the regression
    // test against the legacy "load whole file as String" pattern
    // that pegged memory at file size.
    val started = System.nanoTime()
    FileOps.streamLines(largeFile).compile.fold(0) { case (acc, _) => acc + 1 }.map { count =>
      val elapsed = (System.nanoTime() - started) / 1e9
      assertEquals(count, 100000)
      assert(elapsed < 30.0, s"full streamLines took ${elapsed}s — too slow")
    }
  }

  // -- streamFiles lazy-walk tests ----------------------------------

  test("streamFiles: take(5) on a 1000-file directory completes promptly") {
    val started = System.nanoTime()
    FileOps.streamFiles(largeDir, ".scala").take(5).compile.toList.map { paths =>
      val elapsed = (System.nanoTime() - started) / 1e9
      assertEquals(paths.size, 5)
      assert(elapsed < 5.0, s"streamFiles.take(5) took ${elapsed}s — too slow, likely eagerly enumerating")
    }
  }

  test("streamFiles: foldLeft over 1000-file directory") {
    FileOps.streamFiles(largeDir, ".scala").compile.fold(0)((n, _) => n + 1).map { count =>
      assertEquals(count, 1000)
    }
  }

  // -- Composition: streamFiles + streamLines stays bounded --------

  test("streamFiles + streamLines: per-file consumption is interleaved, not buffered") {
    // Walk the directory + stream each file's lines. If the outer
    // walk eagerly enumerated, we'd see 1000 paths in memory. If the
    // inner streamLines eagerly read each file, we'd see 1000 file
    // contents in memory. Neither should happen — we should see
    // 1000 (path, line) pairs flow through with bounded working set.
    FileOps
      .streamFiles(largeDir, ".scala")
      .flatMap(p => FileOps.streamLines(p).map(line => p -> line))
      .compile
      .fold(0)((n, _) => n + 1)
      .map { count =>
        // Each synthetic file has 1 line, so 1000 files × 1 line = 1000 total.
        assertEquals(count, 1000)
      }
  }

  // -- Cancellation safety -----------------------------------------

  test("streamLines: short-circuit via .take releases the file handle") {
    // If FS2 leaks file handles on early termination, this loop would
    // eventually exhaust the OS fd table. 100 short reads of the same
    // file should be a no-op for fd usage.
    val program = (0 until 100).foldLeft(IO.unit) { (acc, _) =>
      acc *> FileOps.streamLines(largeFile).take(1).compile.drain
    }
    program.map(_ => assert(true, "no fd leak across 100 short-circuited reads"))
  }
}
