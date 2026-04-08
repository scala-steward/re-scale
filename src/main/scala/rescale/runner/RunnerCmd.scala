/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale runner` — generic test-runner adapter dispatcher.
 *
 * Subcommands:
 *
 *   re-scale runner list
 *     Print every runner defined in .rescale/runners.yaml
 *
 *   re-scale runner <name> [--mode MODE] [POS_ARGS...]
 *     Run the named runner. The optional --mode selects a mode block
 *     from runners.yaml; positional args after the mode become $1/$2/...
 *     placeholders inside the mode key-value pairs.
 *
 * Exit code: 0 on success (Outcome.ok = true), 1 on failure.
 */
package rescale.runner

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Term}

object RunnerCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "list" :: _ =>
        listRunners()
      case name :: rest =>
        runOne(name, Cli.parse(rest))
    }

  // -- list ---------------------------------------------------------

  private def listRunners(): IO[ExitCode] =
    for {
      root <- Paths.projectRoot
      cfgFile = Paths.runnersConfig(root)
      cfg <- loadConfig(cfgFile)
      _ <- IO {
             if (cfg.runners.isEmpty) {
               println(s"(no runners defined in $cfgFile)")
             } else {
               println(s"Runners defined in $cfgFile:\n")
               cfg.runners.toList.sortBy(_._1).foreach { case (name, r) =>
                 println(s"  $name")
                 r.description.foreach(d => println(s"    $d"))
                 if (r.modes.nonEmpty) {
                   println(s"    modes: ${r.modes.keys.toList.sorted.mkString(", ")}")
                 }
                 println()
               }
             }
           }
    } yield ExitCode.Success

  // -- run one ------------------------------------------------------

  private def runOne(name: String, args: Cli.Args): IO[ExitCode] =
    for {
      root    <- Paths.projectRoot
      cfgFile  = Paths.runnersConfig(root)
      cfg     <- loadConfig(cfgFile)
      result  <- cfg.runners.get(name) match {
                   case None =>
                     IO(Term.err(s"Unknown runner: '$name' (defined: ${cfg.runners.keys.toList.sorted.mkString(", ")})"))
                       .as(ExitCode.Error)
                   case Some(runner) =>
                     val mode    = args.flag("mode")
                     val posArgs = args.positional
                     for {
                       _ <- IO(Term.info(s"Running '$name'${mode.map(m => s" [mode=$m]").getOrElse("")}..."))
                       outcome <- Runner.run(runner, mode, posArgs, root)
                       _ <- IO(printOutcome(name, outcome))
                     } yield if (outcome.ok) ExitCode.Success else ExitCode.Error
                 }
    } yield result

  private def printOutcome(name: String, outcome: Runner.Outcome): Unit = {
    if (outcome.ok) {
      Term.ok(s"$name: PASS")
    } else {
      Term.err(s"$name: FAIL (exit ${outcome.exitCode})")
    }
    if (outcome.captures.nonEmpty) {
      println()
      println("Captures:")
      outcome.captures.toList.sortBy(_._1).foreach { case (k, v) =>
        println(f"  $k%-20s $v")
      }
    }
    if (outcome.failureLines.nonEmpty) {
      println()
      println("Failure tail:")
      outcome.failureLines.foreach(l => println(s"  $l"))
    }
  }

  // -- helpers ------------------------------------------------------

  private def loadConfig(file: java.io.File): IO[RunnersConfig] =
    RunnersConfig.load(file) match {
      case Right(cfg) => IO.pure(cfg)
      case Left(err) =>
        IO(Term.err(s"Failed to load $file: $err")) *>
          IO.raiseError(new RuntimeException(err))
    }

  private val usage: String =
    """Usage: re-scale runner <command>
      |
      |Commands:
      |  list                              Print every runner defined in
      |                                    .rescale/runners.yaml
      |
      |  <name> [--mode MODE] [POS_ARGS...]
      |                                    Run the named runner. --mode
      |                                    selects a mode block; positional
      |                                    args become $1/$2/... placeholders
      |                                    inside the mode key-value pairs.""".stripMargin
}
