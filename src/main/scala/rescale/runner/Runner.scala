/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Runner engine — generic test-runner adapter driven by
 * `.rescale/runners.yaml`.
 *
 * Run flow:
 *   1. If `mode-file` is configured, write mode key-value pairs to it
 *      (one `key=value` line per pair). The runner is expected to
 *      read + DELETE this file on entry; we never delete it from the
 *      wrapper because crash-during-exec would leak stale state.
 *   2. Resolve mode placeholders: `$1`, `$2`, ... map to positional
 *      args passed on the CLI after the mode name.
 *   3. exec(invoke.command, invoke.args, cwd=invoke.cwd ?? project-root)
 *   4. Parse output via OutputSpec.success.regex (if configured) and
 *      capture named groups into a Map[String, String].
 *   5. Return Outcome(ok, captures, exitCode, failureLines)
 */
package rescale.runner

import cats.effect.IO
import rescale.common.Proc

import java.io.{File, PrintWriter}
import scala.util.matching.Regex
import scala.util.matching.Regex.Match

object Runner {

  /** Streaming sink that scans each subprocess line as it arrives,
    * capturing the first success-regex match and keeping up to
    * `maxFailure` **distinct** lines that match any failure pattern.
    *
    * Memory footprint: O(maxFailure line lengths). Once the failure
    * buffer is full, later matching lines are dropped on the floor —
    * the in-process footprint never grows with subprocess output
    * length.
    *
    * Thread safety: stdout and stderr reader threads both land in
    * [[onLine]]. The whole sink is synchronized to keep the first-
    * match race and the dedup set consistent.
    */
  private final class OutcomeSink(
    successRegex:    Option[Regex],
    failurePatterns: List[String],
    maxFailure:      Int
  ) extends Proc.LineSink {
    private var firstMatch: Option[Match]    = None
    private val failures                     = new java.util.LinkedHashSet[String]()
    private val failuresEnabled              = failurePatterns.nonEmpty && maxFailure > 0

    def onOut(line: String): Unit = onLine(line)
    def onErr(line: String): Unit = onLine(line)

    private def onLine(line: String): Unit = this.synchronized {
      if (firstMatch.isEmpty) {
        successRegex.foreach { rx =>
          val m = rx.findFirstMatchIn(line)
          if (m.isDefined) firstMatch = m
        }
      }
      if (
        failuresEnabled &&
        failures.size < maxFailure &&
        failurePatterns.exists(p => line.contains(p))
      ) {
        failures.add(line): Unit // LinkedHashSet dedupes; add() is a no-op on duplicates
      }
    }

    def snapshot(): (Option[Match], List[String]) = this.synchronized {
      val list = {
        val out = new java.util.ArrayList[String](failures)
        import scala.jdk.CollectionConverters.*
        out.asScala.toList
      }
      (firstMatch, list)
    }
  }

  /** Result of running a runner: success flag, captured numeric/text
    * values from the success-regex, and the trimmed failure tail when
    * the runner failed.
    */
  final case class Outcome(
    ok:           Boolean,
    captures:     Map[String, String],
    exitCode:     Int,
    failureLines: List[String]
  )

  /** Run a configured runner with optional mode + positional args. */
  def run(
    runner: RunnersConfig.Runner,
    mode:   Option[String],
    posArgs: List[String],
    projectRoot: File
  ): IO[Outcome] = {
    val successRegex: Option[Regex] =
      runner.output.flatMap(_.success).flatMap { pat =>
        try Some(pat.regex.r)
        catch { case _: Throwable => None }
      }
    val capture: Map[String, Int] =
      runner.output.flatMap(_.success).map(_.capture).getOrElse(Map.empty)
    val failurePatterns: List[String] =
      runner.output.flatMap(_.failure).map(_.`keep-lines-matching`).getOrElse(Nil)
    val maxFailure: Int =
      runner.output.flatMap(_.failure).map(_.`max-lines`).getOrElse(0)

    val sink = new OutcomeSink(successRegex, failurePatterns, maxFailure)

    for {
      _      <- writeModeFile(runner, mode, posArgs, projectRoot)
      cwdFile = runner.invoke.cwd
                  .map(p => new File(resolveCwd(p, projectRoot)))
                  .getOrElse(projectRoot)
      exit   <- Proc.runStreamed(
                  runner.invoke.command,
                  runner.invoke.args.getOrElse(Nil),
                  cwd = Some(cwdFile),
                  sink = sink
                )
      (firstMatch, failureLines) = sink.snapshot()
      captures = firstMatch match {
        case None    => Map.empty[String, String]
        case Some(m) =>
          capture.flatMap { case (name, group) =>
            if (group <= m.groupCount) Some(name -> m.group(group)) else None
          }
      }
    } yield Outcome(
      ok           = exit == 0,
      captures     = captures,
      exitCode     = exit,
      failureLines = if (exit == 0) Nil else failureLines
    )
  }

  /** Write the mode file with the resolved mode key-value pairs.
    * Replaces `$1`/`$2`/... in the values with positional args.
    *
    * Mode file is OVERWRITTEN unconditionally — never appended.
    * The runner contract is "read on entry, delete on entry, write
    * never": if a previous run crashed and left a stale file, we
    * blow it away here.
    */
  private def writeModeFile(
    runner:      RunnersConfig.Runner,
    mode:        Option[String],
    posArgs:     List[String],
    projectRoot: File
  ): IO[Unit] = {
    runner.`mode-file` match {
      case None => IO.unit
      case Some(mf) =>
        val abs    = resolveCwd(mf.path, projectRoot)
        val parent = new File(abs).getParentFile
        val pairs  = mode match {
          case None       => Map.empty[String, String]
          case Some(name) =>
            runner.modes.get(name) match {
              case None        => Map.empty[String, String]
              case Some(pairs) =>
                pairs.map { case (k, v) => k -> resolvePosArgs(v, posArgs) }
            }
        }
        IO.blocking {
          if (parent != null && !parent.exists()) parent.mkdirs(): Unit
          val w = new PrintWriter(new File(abs))
          try {
            w.println("# re-scale runner mode (key=value, one per line)")
            pairs.toList.sortBy(_._1).foreach { case (k, v) =>
              w.print(k)
              w.print('=')
              w.println(v)
            }
          } finally w.close()
        }
    }
  }

  /** Replace `$1`/`$2`/... placeholders in a value with positional args. */
  private def resolvePosArgs(value: String, posArgs: List[String]): String = {
    var out = value
    posArgs.zipWithIndex.foreach { case (arg, idx) =>
      out = out.replace(s"$$${idx + 1}", arg)
    }
    out
  }

  private def resolveCwd(path: String, projectRoot: File): String = {
    if (path.startsWith("/")) path
    else new File(projectRoot, path).getAbsolutePath
  }

}
