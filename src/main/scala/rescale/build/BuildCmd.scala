/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale build` — sbt --client wrapper for compile/fmt/publish-local.
 *
 * Why `--client`: bare `sbt` reloads the build per invocation, which
 * pegs CPU and silently hangs if `build.sbt` has an error. The sbt
 * client mode reuses a long-running server, so successive `compile`
 * runs take 0.3 s instead of 30 s.
 *
 * Module resolution: by default, all modules under the project root
 * (discovered via Paths.moduleNames). Override with `--module M`.
 * Cross-platform compilation suffixes (`JS`/`Native`) are appended
 * when `--js` / `--native` is set.
 */
package rescale.build

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import rescale.common.{Cli, Paths, Proc, Term}

import java.io.File

object BuildCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "compile" :: rest       => compile(Cli.parse(rest))
      case "compile-fmt" :: rest   => compileFmt(Cli.parse(rest))
      case "fmt" :: _              => fmt()
      case "publish-local" :: rest => publishLocal(Cli.parse(rest))
      case "kill-sbt" :: _         => killSbt()
      case other :: _ =>
        IO(Term.err(s"Unknown build command: $other")).as(ExitCode.Error)
    }

  // -- compile -------------------------------------------------------

  def compile(args: Cli.Args): IO[ExitCode] = {
    for {
      root    <- Paths.projectRoot
      targets  = resolveTargets(root, args)
      result  <- runSbtCommands(root, targets.map(t => s"$t/compile"), args.hasFlag("errors-only"))
    } yield result
  }

  def compileFmt(args: Cli.Args): IO[ExitCode] =
    fmt().flatMap {
      case ExitCode.Success => compile(args)
      case other            => IO.pure(other)
    }

  def fmt(): IO[ExitCode] =
    for {
      root <- Paths.projectRoot
      _    <- IO(Term.info("Formatting..."))
      exit <- Proc.exec("sbt", List("--client", "scalafmtAll"), cwd = Some(root))
      result <-
        if (exit == 0) IO(Term.ok("Format complete")).as(ExitCode.Success)
        else IO(Term.err("Format failed")).as(ExitCode(exit))
    } yield result

  def publishLocal(args: Cli.Args): IO[ExitCode] = {
    for {
      root    <- Paths.projectRoot
      targets  = resolveTargets(root, args)
      result  <- runSbtCommands(root, targets.map(t => s"$t/publishLocal"), errorsOnly = false)
    } yield result
  }

  def killSbt(): IO[ExitCode] = {
    for {
      root <- Paths.projectRoot
      _    <- IO(Term.info("Killing sbt server..."))
      exit <- Proc.exec("sbt", List("--client", "shutdown"), cwd = Some(root))
      _    <- if (exit == 0) IO(Term.ok("sbt server stopped"))
              else IO(Term.warn("sbt server may not have been running"))
    } yield ExitCode.Success
  }

  // -- helpers ------------------------------------------------------

  /** Run a sequence of sbt commands; abort on first failure.
    * `errorsOnly` filters output to lines containing "[error]" only.
    */
  private def runSbtCommands(root: File, cmds: List[String], errorsOnly: Boolean): IO[ExitCode] = {
    cmds.foldLeftM(ExitCode.Success) { (acc, cmd) =>
      acc match {
        case ExitCode.Success =>
          IO(Term.info(s"sbt --client $cmd")) *> runOne(root, cmd, errorsOnly)
        case fail =>
          IO.pure(fail)
      }
    }
  }

  private def runOne(root: File, cmd: String, errorsOnly: Boolean): IO[ExitCode] = {
    if (!errorsOnly) {
      Proc.exec("sbt", List("--client", cmd), cwd = Some(root)).map(ExitCode.apply)
    } else {
      // Streaming `[error]` filter — print each matching line as it
      // arrives. sbt compile can emit megabytes of log on a cold build;
      // buffering the full transcript just to grep for `[error]`
      // earned us one 48-GB incident already. Don't do it again.
      val sink: Proc.LineSink = new Proc.LineSink {
        def onOut(line: String): Unit =
          if (line.contains("[error]")) println(line)
        def onErr(line: String): Unit =
          if (line.contains("[error]")) println(line)
      }
      Proc.runStreamed("sbt", List("--client", cmd), cwd = Some(root), sink = sink)
        .map(ExitCode(_))
    }
  }

  /** Build the list of sbt targets from the args. Modules come from
    * `--module M` (single) or Paths.moduleNames (all). Platform
    * suffixes are appended for `--js` / `--native`. `--all` is the
    * same as no platform flag (uses the default base axis, typically
    * the JVM).
    */
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

  private val usage: String =
    """Usage: re-scale build <command>
      |
      |Commands:
      |  compile [--module M] [--jvm] [--js] [--native] [--all] [--errors-only]
      |  compile-fmt   Run scalafmt then compile
      |  fmt           Run scalafmtAll
      |  publish-local [--module M] [--jvm] [--js] [--native] [--all]
      |  kill-sbt      Shut down the sbt server""".stripMargin
}
