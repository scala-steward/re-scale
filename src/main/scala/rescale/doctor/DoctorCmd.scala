/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale doctor` — generic dev-environment bootstrap dispatcher.
 *
 * Reads `.rescale/doctor.yaml` from the project root, runs every step
 * (check + optional install), prints a summary, and exits with non-zero
 * if any step ended in Fail.
 *
 * Flags:
 *   --ci   Skip steps marked `interactive: true` (steps that prompt
 *          for input or open a sudo password prompt). The Fail count
 *          excludes Skipped steps so a CI run can still pass even when
 *          interactive steps were skipped.
 */
package rescale.doctor

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Term}

object DoctorCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil =>
        runAll(Cli.parse(Nil))
      case "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case rest =>
        runAll(Cli.parse(rest))
    }

  def runAll(args: Cli.Args): IO[ExitCode] = {
    val ciMode = args.hasFlag("ci")
    for {
      root <- Paths.projectRoot
      cfgFile = Paths.doctorConfig(root)
      cfg <- DoctorConfig.load(cfgFile) match {
               case Right(c)  => IO.pure(c)
               case Left(err) =>
                 IO(Term.err(s"Failed to load $cfgFile: $err")) *>
                   IO.raiseError(new RuntimeException(err))
             }
      result <-
        if (cfg.steps.isEmpty)
          IO(Term.warn(s"No doctor config found at $cfgFile (or empty steps:)")).as(ExitCode.Success)
        else
          for {
            _      <- IO {
                        println("╔══════════════════════════════════════════════════╗")
                        println("║  re-scale doctor                                ║")
                        println("╚══════════════════════════════════════════════════╝")
                        println()
                      }
            (rs, allOk) <- Doctor.run(cfg, ciMode)
            _      <- IO(printSummary(rs, ciMode))
          } yield if (allOk) ExitCode.Success else ExitCode.Error
    } yield result
  }

  private def printSummary(results: List[Doctor.StepResult], ciMode: Boolean): Unit = {
    println()
    println("─" * 52)
    val ok      = results.count(_.isInstanceOf[Doctor.StepResult.Ok])
    val skipped = results.count(_.isInstanceOf[Doctor.StepResult.Skipped])
    val failed  = results.count(_.isInstanceOf[Doctor.StepResult.Fail])
    println(s"Summary: $ok ok, $skipped skipped, $failed failed (of ${results.size})")
    if (failed > 0) {
      println()
      println("Failed steps:")
      results.foreach {
        case Doctor.StepResult.Fail(id, name, reason, hint) =>
          println(s"  ✗ $id ($name): $reason")
          hint.foreach(h => println(s"      hint: $h"))
        case _ => ()
      }
    }
    if (ciMode && skipped > 0) {
      println()
      println(s"  (--ci skipped $skipped interactive step(s); not counted as failures)")
    }
  }

  private val usage: String =
    """Usage: re-scale doctor [--ci]
      |
      |Reads .rescale/doctor.yaml from the project root and runs every
      |step. Each step has a check command + an optional install command;
      |if the check fails, the install runs (unless --ci was passed and
      |the step is marked interactive: true).
      |
      |Exits non-zero if any step ended in Fail. Skipped steps don't
      |count as failures.""".stripMargin
}
