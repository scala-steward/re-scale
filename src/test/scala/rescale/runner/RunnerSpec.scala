/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the Runner engine. Uses real subprocesses (echo, false)
 * to exercise success/failure parse paths and the mode-file dance.
 */
package rescale.runner

import munit.CatsEffectSuite
import rescale.runner.RunnersConfig.{Failure, Invoke, ModeFile, OutputSpec, Pattern, Runner as R}

import java.io.File
import java.nio.file.Files as NIOFiles

final class RunnerSpec extends CatsEffectSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("rescale-runner-").toFile
    d.deleteOnExit()
    d
  }

  test("simple echo runner: ok + capture from regex") {
    val runner = R(
      invoke = Invoke(command = "echo", args = Some(List("Total=42 Passing=37"))),
      output = Some(OutputSpec(success = Some(Pattern(
        regex   = """Total=(\d+)\s+Passing=(\d+)""",
        capture = Map("total" -> 1, "passing" -> 2)
      ))))
    )
    Runner.run(runner, mode = None, posArgs = Nil, projectRoot = tempDir()).map { o =>
      assert(o.ok, s"expected ok; outcome=$o")
      assertEquals(o.captures("total"), "42")
      assertEquals(o.captures("passing"), "37")
    }
  }

  test("failed runner: failure tail filter keeps matching lines") {
    val runner = R(
      invoke = Invoke(
        command = "sh",
        args = Some(List("-c", "echo regression-1 1>&2; echo unrelated 1>&2; exit 1"))
      ),
      output = Some(OutputSpec(failure = Some(Failure(
        `keep-lines-matching` = List("regression"),
        `max-lines`           = 5
      ))))
    )
    Runner.run(runner, mode = None, posArgs = Nil, projectRoot = tempDir()).map { o =>
      assert(!o.ok, s"expected failure; outcome=$o")
      assertEquals(o.failureLines.size, 1)
      assert(o.failureLines.head.contains("regression"))
    }
  }

  test("mode file is written with key-value pairs from selected mode") {
    val root = tempDir()
    val modePath = "subdir/mode.tsv"
    val runner = R(
      invoke = Invoke(command = "true"),
      `mode-file` = Some(ModeFile(path = modePath)),
      modes = Map(
        "regression" -> Map.empty,
        "strict"     -> Map("strict" -> "1"),
        "subdir"     -> Map("subdir" -> "$1")
      )
    )
    Runner.run(runner, mode = Some("strict"), posArgs = Nil, projectRoot = root).map { _ =>
      val written = new File(root, modePath)
      assert(written.exists(), s"expected mode file at $written")
      val content = scala.io.Source.fromFile(written).mkString
      assert(content.contains("strict=1"), s"content was: $content")
    }
  }

  test("mode file: positional args replace $1/$2 placeholders") {
    val root = tempDir()
    val runner = R(
      invoke = Invoke(command = "true"),
      `mode-file` = Some(ModeFile(path = "mode.tsv")),
      modes = Map("subdir" -> Map("subdir" -> "$1", "depth" -> "$2"))
    )
    Runner.run(runner, mode = Some("subdir"), posArgs = List("spec/css/units", "3"), projectRoot = root).map { _ =>
      val content = scala.io.Source.fromFile(new File(root, "mode.tsv")).mkString
      assert(content.contains("subdir=spec/css/units"), s"content was: $content")
      assert(content.contains("depth=3"), s"content was: $content")
    }
  }

  test("huge output: failure tail stays bounded, no full-transcript buffering") {
    // Regression test for the sass-spec 48 GB incident. Before the
    // streaming rewrite, Runner.run buffered every subprocess line
    // into Proc.run's StringBuilder and then did
    // `combined.linesIterator.toList.filter(...).distinct.take(max)`,
    // so a subprocess that printed hundreds of thousands of lines
    // retained all of them in memory.
    //
    // This test emits 100_000 lines of filler + 3 distinct "FAIL"
    // lines (plus repeats of one of them) via a shell loop. The
    // correctness invariant ("failure tail is bounded to max-lines
    // distinct entries regardless of total output length") is what
    // we assert here. The actual RSS ceiling is enforced by the
    // wrapper's SCALANATIVE_MAX_HEAP_SIZE=1G cap + the existing
    // Ssg944FileMemoryBoundSpec — this test is the functional gate
    // that catches `combined = result.stdout ++ result.stderr`-style
    // regressions at the unit level.
    val script =
      """for i in $(seq 1 100000); do
        |  echo "filler line $i noise noise noise"
        |done
        |echo "FAIL: alpha"
        |echo "FAIL: alpha"
        |echo "FAIL: beta"
        |echo "FAIL: gamma"
        |echo "FAIL: alpha"
        |exit 1
        |""".stripMargin
    val runner = R(
      invoke = Invoke(command = "sh", args = Some(List("-c", script))),
      output = Some(OutputSpec(failure = Some(Failure(
        `keep-lines-matching` = List("FAIL"),
        `max-lines`           = 5
      ))))
    )
    Runner.run(runner, mode = None, posArgs = Nil, projectRoot = tempDir()).map { o =>
      assert(!o.ok, s"expected failure; outcome=$o")
      // Dedup rule is "first N distinct", so 4 alpha emissions collapse
      // to 1. Expected tail: alpha, beta, gamma.
      assertEquals(o.failureLines.size, 3, s"failure tail was: ${o.failureLines}")
      assert(o.failureLines.exists(_.contains("alpha")))
      assert(o.failureLines.exists(_.contains("beta")))
      assert(o.failureLines.exists(_.contains("gamma")))
    }
  }

  test("mode file is overwritten on each run (no stale state)") {
    val root = tempDir()
    val runner = R(
      invoke = Invoke(command = "true"),
      `mode-file` = Some(ModeFile(path = "mode.tsv")),
      modes = Map("a" -> Map("a" -> "1"), "b" -> Map("b" -> "2"))
    )
    for {
      _  <- Runner.run(runner, mode = Some("a"), posArgs = Nil, projectRoot = root)
      _  <- Runner.run(runner, mode = Some("b"), posArgs = Nil, projectRoot = root)
      _  =  {
        val content = scala.io.Source.fromFile(new File(root, "mode.tsv")).mkString
        assert(content.contains("b=2"), s"content was: $content")
        assert(!content.contains("a=1"), s"stale a=1 leaked: $content")
      }
    } yield ()
  }
}
