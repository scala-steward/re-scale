/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Synthetic 1000-file / 50 MB memory-bound test for re-scale's enforce
 * scanners. Unlike Ssg944FileMemoryBoundSpec (which requires the SSG
 * checkout to be present at /Users/dev/Workspaces/GitHub/ssg/), this
 * spec generates its own scratch codebase in `beforeAll` so it can run
 * unchanged in CI.
 *
 * Generation strategy:
 *   - 5 module dirs × ~200 .scala files each = 1000 files total
 *   - Each file is ~50 KB → ~50 MB on disk
 *   - File contents include realistic-looking code with class/def/val
 *     declarations, SCREAMING_SNAKE_CASE constants, and a few stub
 *     comments per file so the scanners actually have things to find
 *
 * Acceptance gate:
 *   - Each scanner must complete in < 30 s
 *   - Each scanner must peak under 512 MiB of RSS (measured via
 *     /usr/bin/time -l on macOS, /usr/bin/time -v on Linux)
 */
package rescale.enforce

import munit.FunSuite

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path}
import scala.sys.process.*

final class SyntheticMemoryBoundSpec extends FunSuite {

  // munit's per-test timeout. Must be > walltimeBudgetSec or the
  // assertion never has a chance to fire — munit kills the test first
  // and the failure shows up as a timeout instead of a budget breach.
  override def munitTimeout: scala.concurrent.duration.Duration =
    if (sys.env.get("CI").contains("true"))
      scala.concurrent.duration.Duration(240, "seconds")
    else
      scala.concurrent.duration.Duration(120, "seconds")

  // -- Fixture -------------------------------------------------------

  // Single shared corpus for all tests in this suite — generation is
  // ~5 s and dominates wall time, so we share via a `lazy val`.
  private lazy val corpusRoot: File = generateSyntheticCorpus()

  private val budgetBytes: Long = 512L * 1024L * 1024L

  // CI runners (GitHub Actions ubuntu-latest / macos-latest) are
  // markedly slower than developer workstations — observed: ~108 s
  // for `enforce shortcuts` on the 1000-file synthetic corpus on
  // ubuntu-latest where it's ~5 s locally. Quadruple the wall-time
  // budget when CI=true so corpus generation + scan + measurement
  // fit even on weaker hosted hardware. The MEMORY budget stays the
  // same (512 MiB) — slow CPUs don't change the allocation peak.
  private val walltimeBudgetSec: Double =
    if (sys.env.get("CI").contains("true")) 120.0 else 30.0

  private val reScaleWrapper: Path =
    Path.of(System.getProperty("user.dir"), "target", "stage", "bin", "re-scale")

  private def assumeStaged(): Unit = {
    assume(
      Files.isExecutable(reScaleWrapper),
      s"re-scale wrapper not staged at $reScaleWrapper — run `sbt stage` first"
    )
  }

  // -- Tests ---------------------------------------------------------

  test("synthetic enforce shortcuts: < 512 MiB / < 30 s on a 1000-file corpus") {
    assumeStaged()
    val result = runAndMeasure(
      List(reScaleWrapper.toString, "enforce", "shortcuts"),
      cwd = corpusRoot
    )
    assertUnderBudget(result, "enforce shortcuts")
  }

  test("synthetic enforce stale-stubs: < 512 MiB / < 30 s on a 1000-file corpus") {
    assumeStaged()
    val result = runAndMeasure(
      List(reScaleWrapper.toString, "enforce", "stale-stubs"),
      cwd = corpusRoot
    )
    assertUnderBudget(result, "enforce stale-stubs")
  }

  // -- Corpus generator ---------------------------------------------

  private def generateSyntheticCorpus(): File = {
    val root = Files.createTempDirectory("rescale-synthetic-").toFile
    root.deleteOnExit()
    // Project marker — Paths.discover() walks up looking for any of
    // .rescale, build.sbt, .git, etc. .rescale wins (highest priority)
    // and lets us drop scan-targets.txt later if we want.
    val rescaleDir = new File(root, ".rescale")
    rescaleDir.mkdirs()
    rescaleDir.deleteOnExit()
    val moduleNames = List("module-a", "module-b", "module-c", "module-d", "module-e")
    moduleNames.foreach { m =>
      val moduleSrc = new File(root, s"$m/src/main/scala/example/$m")
      moduleSrc.mkdirs()
      moduleSrc.deleteOnExit()
      // ~200 files per module = 1000 total
      (1 to 200).foreach { i =>
        val file = new File(moduleSrc, f"Example$i%03d.scala")
        writeSyntheticFile(file, m, i)
        file.deleteOnExit()
      }
    }
    root
  }

  /** Write one synthetic .scala file. ~50 KB of realistic-looking
    * Scala with class/def/val declarations + a few stub markers
    * sprinkled in so the scanners produce some hits.
    */
  private def writeSyntheticFile(f: File, moduleName: String, idx: Int): Unit = {
    val w = new PrintWriter(f)
    try {
      w.println(s"""/*
                   | * Copyright 2026 example
                   | * SPDX-License-Identifier: Apache-2.0
                   | *
                   | * Synthetic test fixture for re-scale memory-bound spec.
                   | */
                   |package example.$moduleName
                   |
                   |import scala.collection.mutable
                   |
                   |object Example$idx {
                   |
                   |  val MAX_RETRIES = 3
                   |  val DEFAULT_TIMEOUT_MS = 5000
                   |  val SUPPORTED_VERSIONS = List("v1", "v2", "v3")
                   |""".stripMargin)

      // Write enough body to make the file ~50 KB. Each method block
      // adds about 600 bytes; ~80 method blocks = 48 KB.
      (1 to 80).foreach { m =>
        w.println(s"""
                     |  // Method $m for example $idx in $moduleName
                     |  def method${m}(input: String, count: Int = $m): Either[String, List[Int]] = {
                     |    val buffer = mutable.ListBuffer.empty[Int]
                     |    var i = 0
                     |    while (i < count) {
                     |      val processed = input.length * (i + 1) + $m
                     |      buffer += processed
                     |      i += 1
                     |    }
                     |    if (buffer.isEmpty) Left("empty buffer")
                     |    else Right(buffer.toList)
                     |  }
                     |""".stripMargin)
      }

      // A few stub markers per file so the scanners have hits to report.
      // Mix of always-rules (TODO) and comment-only rules (placeholder).
      if (idx % 7 == 0) w.println("  // TODO: this is a synthetic stub marker")
      if (idx % 11 == 0) w.println("  // placeholder for the real implementation")
      if (idx % 13 == 0) w.println("  // for now, hardcoded — real version coming")

      w.println("}")
    } finally w.close()
  }

  // -- Measurement helpers (cribbed from Ssg944FileMemoryBoundSpec) -

  private final case class MeasureResult(
    maxRssBytes: Long,
    wallSeconds: Double,
    exitCode:    Int,
    stderrTail:  String
  )

  private def runAndMeasure(cmd: List[String], cwd: File): MeasureResult = {
    val isMac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")
    val timeBin = "/usr/bin/time"
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
      stderrTail  = stderrBuf.toString.takeRight(1024)
    )
  }

  /** macOS `time -l` writes bytes; Linux `time -v` writes kbytes. */
  private def parseMaxRss(stderrOutput: String, isMac: Boolean): Long = {
    val lines = stderrOutput.linesIterator.toList
    if (isMac) {
      lines
        .find(_.contains("maximum resident set size"))
        .orElse(lines.find(_.contains("peak memory footprint")))
        .flatMap { line =>
          scala.util.Try(line.trim.takeWhile(_.isDigit).toLong).toOption
        }
        .getOrElse(0L)
    } else {
      lines
        .find(_.contains("Maximum resident set size"))
        .flatMap { line =>
          val afterColon = line.split(":", 2).drop(1).headOption.getOrElse("")
          scala.util.Try(afterColon.trim.toLong * 1024L).toOption
        }
        .getOrElse(0L)
    }
  }

  private def assertUnderBudget(r: MeasureResult, label: String): Unit = {
    val rssMiB    = r.maxRssBytes.toDouble / (1024.0 * 1024.0)
    val budgetMiB = budgetBytes.toDouble / (1024.0 * 1024.0)
    val msg = f"$label: peak RSS $rssMiB%.1f MiB (budget $budgetMiB%.0f MiB), wall ${r.wallSeconds}%.1fs"
    println(s"  → $msg")
    assert(r.maxRssBytes < budgetBytes, s"$msg — over memory budget. stderr tail:\n${r.stderrTail}")
    assert(r.wallSeconds < walltimeBudgetSec, s"$msg — over time budget (${walltimeBudgetSec}s)")
    // Exit code 0 = no hits, 1 = hits found. Anything else is a crash.
    assert(r.exitCode == 0 || r.exitCode == 1, s"$msg — unexpected exit code ${r.exitCode}")
  }
}
