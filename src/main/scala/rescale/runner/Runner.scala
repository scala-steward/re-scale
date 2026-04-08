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

object Runner {

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
    for {
      _      <- writeModeFile(runner, mode, posArgs, projectRoot)
      cwdFile = runner.invoke.cwd
                  .map(p => new File(resolveCwd(p, projectRoot)))
                  .getOrElse(projectRoot)
      result <- Proc.run(
                  runner.invoke.command,
                  runner.invoke.args.getOrElse(Nil),
                  cwd = Some(cwdFile)
                )
    } yield parseOutcome(runner, result)
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

  /** Build the Outcome record from the captured Proc.Result. */
  private def parseOutcome(runner: RunnersConfig.Runner, result: Proc.Result): Outcome = {
    val combined = result.stdout + "\n" + result.stderr

    val captures: Map[String, String] = runner.output.flatMap(_.success) match {
      case None => Map.empty
      case Some(pattern) =>
        try {
          pattern.regex.r.findFirstMatchIn(combined) match {
            case None    => Map.empty
            case Some(m) =>
              pattern.capture.flatMap { case (name, group) =>
                if (group <= m.groupCount) Some(name -> m.group(group))
                else None
              }
          }
        } catch {
          case _: Throwable => Map.empty
        }
    }

    val failureLines: List[String] =
      if (result.ok) Nil
      else runner.output.flatMap(_.failure) match {
        case None => Nil
        case Some(spec) =>
          val patterns = spec.`keep-lines-matching`
          val keep = combined.linesIterator.toList.filter { line =>
            patterns.exists(p => line.contains(p))
          }
          keep.distinct.take(spec.`max-lines`)
      }

    Outcome(
      ok           = result.ok,
      captures     = captures,
      exitCode     = result.exitCode,
      failureLines = failureLines
    )
  }
}
