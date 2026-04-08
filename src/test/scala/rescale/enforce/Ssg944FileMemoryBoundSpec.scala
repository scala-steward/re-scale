/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * ====================================================================
 * THE HEADLINE ACCEPTANCE GATE FOR THE re-scale REWRITE
 * ====================================================================
 *
 * This test is designed to BE RED from day 0 and stay red until the
 * FS2 streaming refactor in Phases 1 and 4 lands. Its failing state
 * IS the specification: any code that walks the SSG codebase must do
 * so in constant memory.
 *
 * Scenario: scan all 944 Scala files under /Users/dev/Workspaces/GitHub/ssg/
 * (ssg-md + ssg-liquid + ssg-minify + ssg-js + ssg-sass) using each of
 * the three scanner subcommands:
 *
 *   re-scale enforce shortcuts
 *   re-scale enforce stale-stubs
 *   re-scale enforce verify --all
 *
 * Per-command assertions:
 *   - Peak resident set size (RSS) < 512 MiB
 *   - Wall clock < 30 s
 *   - Exit code is clean (0 or a semantic 1 — not a crash)
 *
 * 512 MiB is the budget. Current streaming StaleStubs peaks at 574 MB on
 * the full corpus, so this test INTENTIONALLY fails against the
 * worktree's best-effort-streaming implementation; the FS2 refactor
 * must tighten it to under 512 MB as a hard ceiling.
 *
 * Measurement: `/usr/bin/time -l` on macOS captures
 * `maximum resident set size` in bytes. On Linux, `time -v` prints
 * `Maximum resident set size (kbytes)`. The harness picks the right
 * invocation based on `os.name`.
 *
 * **How to drive this test**: it runs the INSTALLED re-scale binary,
 * not the current JVM test runner. That means the rewrite must be
 * built + staged via `sbt stage` before this test runs. The test
 * locates the wrapper at `target/stage/bin/re-scale`. If the wrapper
 * isn't present, the test is ignored with a clear message.
 *
 * **Companion test**: `LegacySsgDevMemoryBoundSpec` runs the same
 * workload against the legacy ssg-dev binary at
 * /Users/dev/Workspaces/GitHub/ssg/scripts/bin/ssg-dev and asserts it
 * EXCEEDS the 512 MB budget. This pins the baseline — if legacy stops
 * failing, the measurement harness is broken.
 */
package rescale.enforce

import munit.FunSuite

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*
import scala.util.Try

final class Ssg944FileMemoryBoundSpec extends FunSuite {

  import Ssg944FileMemoryBoundSpec.*

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "seconds")

  // -- Fixture -----------------------------------------------------------

  private val ssgRoot: Path = Paths.get("/Users/dev/Workspaces/GitHub/ssg")

  private val reScaleWrapper: Path =
    Paths.get(System.getProperty("user.dir"), "target", "stage", "bin", "re-scale")

  private val budgetBytes: Long = 512L * 1024L * 1024L

  private val walltimeBudgetSec: Double = 30.0

  private def ssgFileCount: Option[Int] =
    if (!Files.isDirectory(ssgRoot)) None
    else {
      val modules = List("ssg-md", "ssg-liquid", "ssg-minify", "ssg-js", "ssg-sass")
      Some(modules.iterator.map { m =>
        val dir = ssgRoot.resolve(m).resolve("src/main/scala")
        if (Files.isDirectory(dir)) countScalaFiles(dir.toFile) else 0
      }.sum)
    }

  private def countScalaFiles(f: File): Int =
    if (!f.exists()) 0
    else if (f.isDirectory) Option(f.listFiles()).toList.flatten.map(countScalaFiles).sum
    else if (f.getName.endsWith(".scala")) 1
    else 0

  // -- Preconditions -----------------------------------------------------

  private def assumeEnvironment(): Unit = {
    assume(Files.isDirectory(ssgRoot), s"SSG checkout not found at $ssgRoot")
    assume(Files.isExecutable(reScaleWrapper), s"re-scale wrapper not staged at $reScaleWrapper — run `sbt stage` first")
    val count = ssgFileCount.getOrElse(0)
    assume(count >= 800, s"SSG corpus too small ($count files) — expected ~944")
  }

  // -- Tests -------------------------------------------------------------

  test("enforce shortcuts on 944 SSG files stays under 512 MiB and 30s") {
    assumeEnvironment()
    val result = runAndMeasure(List(reScaleWrapper.toString, "enforce", "shortcuts"), cwd = ssgRoot.toFile)
    assertUnderBudget(result, "enforce shortcuts")
  }

  test("enforce stale-stubs on 944 SSG files stays under 512 MiB and 30s") {
    assumeEnvironment()
    val result = runAndMeasure(List(reScaleWrapper.toString, "enforce", "stale-stubs"), cwd = ssgRoot.toFile)
    assertUnderBudget(result, "enforce stale-stubs")
  }

  test("enforce verify --all on 944 SSG files stays under 512 MiB and 30s") {
    assumeEnvironment()
    val result = runAndMeasure(List(reScaleWrapper.toString, "enforce", "verify", "--all"), cwd = ssgRoot.toFile)
    assertUnderBudget(result, "enforce verify --all")
  }

  // -- Helpers -----------------------------------------------------------

  private def assertUnderBudget(r: MeasureResult, label: String): Unit = {
    val rssMiB      = r.maxRssBytes.toDouble / (1024.0 * 1024.0)
    val budgetMiB   = budgetBytes.toDouble / (1024.0 * 1024.0)
    val msg = f"$label: peak RSS $rssMiB%.1f MiB (budget $budgetMiB%.0f MiB), wall ${r.wallSeconds}%.1fs"
    assert(r.maxRssBytes < budgetBytes, s"$msg — over memory budget")
    assert(r.wallSeconds < walltimeBudgetSec, s"$msg — over time budget (${walltimeBudgetSec}s)")
    // Exit code: 0 = no hits, 1 = hits found. Anything else is a crash.
    assert(r.exitCode == 0 || r.exitCode == 1, s"$msg — unexpected exit code ${r.exitCode}")
  }
}

object Ssg944FileMemoryBoundSpec {

  final case class MeasureResult(
    maxRssBytes: Long,
    wallSeconds: Double,
    exitCode:    Int,
    stdoutTail:  String,
    stderrTail:  String
  )

  /** Run a subprocess under /usr/bin/time -l (macOS) or /usr/bin/time -v
    * (Linux), capture its peak RSS, wall time, and exit code. Stdout and
    * stderr tails are kept for failure diagnostics.
    */
  def runAndMeasure(cmd: List[String], cwd: File): MeasureResult = {
    val isMac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")
    val timeBin = if (isMac) "/usr/bin/time" else "/usr/bin/time"
    val timeFlag = if (isMac) "-l" else "-v"

    val stdoutBuf = new StringBuilder
    val stderrBuf = new StringBuilder

    val started = System.nanoTime()
    val exit = Process(timeBin :: timeFlag :: cmd, cwd).!(
      ProcessLogger(
        line => { stdoutBuf.append(line); stdoutBuf.append('\n') },
        line => { stderrBuf.append(line); stderrBuf.append('\n') }
      )
    )
    val wallSeconds = (System.nanoTime() - started) / 1e9

    val maxRssBytes = parseMaxRss(stderrBuf.toString, isMac)
    MeasureResult(
      maxRssBytes = maxRssBytes,
      wallSeconds = wallSeconds,
      exitCode    = exit,
      stdoutTail  = stdoutBuf.toString.takeRight(2048),
      stderrTail  = stderrBuf.toString.takeRight(2048)
    )
  }

  /** Parse the peak RSS out of `/usr/bin/time` output.
    *
    * macOS `time -l` emits lines like:
    *     574358320  peak memory footprint
    *     577781760  maximum resident set size
    * where values are in bytes.
    *
    * Linux `time -v` emits:
    *     Maximum resident set size (kbytes): 123456
    * where the value is in KB.
    */
  private def parseMaxRss(stderrOutput: String, isMac: Boolean): Long = {
    val lines = stderrOutput.linesIterator.toList
    if (isMac) {
      lines
        .find(_.contains("maximum resident set size"))
        .orElse(lines.find(_.contains("peak memory footprint")))
        .flatMap { line =>
          val digits = line.trim.takeWhile(c => c.isDigit || c == ' ').trim
          Try(digits.filter(_.isDigit).toLong).toOption
        }
        .getOrElse(0L)
    } else {
      lines
        .find(_.contains("Maximum resident set size"))
        .flatMap { line =>
          val afterColon = line.split(":", 2).drop(1).headOption.getOrElse("")
          Try(afterColon.trim.toLong * 1024L).toOption // KB -> bytes
        }
        .getOrElse(0L)
    }
  }
}
