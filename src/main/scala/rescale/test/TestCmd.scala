/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale test` — sbt --client wrapper for unit tests + cross-platform
 * verification.
 *
 * Subcommands:
 *   unit [--module M] [--jvm] [--js] [--native] [--all] [--only SUITE]
 *     Runs `<module>/test` (or `<module>/testOnly *SUITE*` if --only)
 *     for each selected platform.
 *
 *   verify
 *     Compiles every module on every platform (JVM/JS/Native). The
 *     module list comes from `Paths.moduleNames`. This is the
 *     "did I break anything cross-platform" gate the legacy ssg-dev
 *     ran before merging.
 */
package rescale.test

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import rescale.common.{Cli, Paths, Proc, Term}

import java.io.File

object TestCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "unit" :: rest   => unit(Cli.parse(rest))
      case "verify" :: _    => verify()
      case other :: _ =>
        IO(Term.err(s"Unknown test command: $other")).as(ExitCode.Error)
    }

  // -- unit ---------------------------------------------------------

  def unit(args: Cli.Args): IO[ExitCode] = {
    for {
      root    <- Paths.projectRoot
      targets  = resolveTargets(root, args)
      only     = args.flag("only")
      cmds     = targets.map { t =>
                   only match {
                     case Some(suite) => s"$t/testOnly *$suite*"
                     case None        => s"$t/test"
                   }
                 }
      result  <- runSequential(root, cmds)
    } yield result
  }

  // -- verify -------------------------------------------------------

  def verify(): IO[ExitCode] = {
    for {
      root <- Paths.projectRoot
      _    <- IO(Term.info("Verifying all modules on all platforms..."))
      modules = Paths.moduleNames(root)
      cmds = for {
        m      <- modules
        suffix <- List("", "JS", "Native")
        scope  <- List("compile", "Test/compile")
      } yield s"$m$suffix/$scope"
      result <- runSequential(root, cmds)
    } yield result
  }

  // -- helpers ------------------------------------------------------

  private def resolveTargets(root: File, args: Cli.Args): List[String] = {
    val modules: List[String] =
      args.flag("module") match {
        case Some(m) => List(m)
        case None    => Paths.moduleNames(root)
      }
    val jvm    = args.hasFlag("jvm")
    val js     = args.hasFlag("js")
    val native = args.hasFlag("native")
    val all    = args.hasFlag("all") || (!jvm && !js && !native)

    if (all) modules
    else
      modules.flatMap { m =>
        val out = scala.collection.mutable.ListBuffer.empty[String]
        if (jvm) out += m
        if (js) out += s"${m}JS"
        if (native) out += s"${m}Native"
        out.toList
      }
  }

  private def runSequential(root: File, cmds: List[String]): IO[ExitCode] = {
    cmds.foldLeftM(ExitCode.Success) { (acc, cmd) =>
      acc match {
        case ExitCode.Success =>
          IO(Term.info(s"sbt --client $cmd")) *>
            Proc.exec("sbt", List("--client", cmd), cwd = Some(root)).map(ExitCode.apply)
        case fail =>
          IO.pure(fail)
      }
    }
  }

  private val usage: String =
    """Usage: re-scale test <command>
      |
      |Commands:
      |  unit [--module M] [--jvm] [--js] [--native] [--all] [--only SUITE]
      |  verify          Compile all modules on all platforms""".stripMargin
}
