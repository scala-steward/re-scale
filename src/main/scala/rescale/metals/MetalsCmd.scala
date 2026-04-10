/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale metals` — Metals LSP server lifecycle management.
 *
 * Ported from sge-dev `scripts/src/metals/MetalsCmd.scala`.
 * Generic and project-agnostic: installs metals-mcp via Coursier,
 * starts/stops the background server, and checks its status via a
 * PID file under `.rescale/.metals-pid`.
 */
package rescale.metals

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Proc, Term}

import java.io.{File, PrintWriter}

object MetalsCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "install" :: _   => install()
      case "start" :: rest  => start(Cli.parse(rest))
      case "stop" :: _      => stop()
      case "status" :: _    => status()
      case other :: _ =>
        IO(Term.err(s"Unknown metals command: $other")).as(ExitCode.Error)
    }

  private def pidFile(root: File): File = new File(root, ".rescale/.metals-pid")
  private def logFile(root: File): File = new File(root, ".rescale/.metals.log")

  private def install(): IO[ExitCode] =
    Proc.exec("cs", List("install", "metals-mcp")).map(ExitCode(_))

  private def start(args: Cli.Args): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      val port = args.flagOrDefault("port", "7845")
      val pf   = pidFile(root)
      val lf   = logFile(root)

      // Check if already running
      for {
        alreadyRunning <- IO.blocking {
          if (pf.exists()) {
            val pid = scala.io.Source.fromFile(pf).mkString.trim.toLongOption
            pid.exists(p => {
              val check = scala.sys.process.Process(List("kill", "-0", p.toString)).!(
                scala.sys.process.ProcessLogger(_ => (), _ => ())
              )
              check == 0
            })
          } else false
        }
        result <- {
          if (alreadyRunning) {
            IO(Term.warn("Metals is already running")).as(ExitCode.Success)
          } else {
            // Start metals in background via nohup + shell
            val cmd = List(
              "metals-mcp",
              "--workspace", root.getAbsolutePath,
              "--port", port,
              "--client", "claude",
              "--default-bsp-to-build-tool"
            )
            IO.blocking {
              if (pf.exists()) pf.delete(): Unit
              val pb = new ProcessBuilder(
                "sh", "-c",
                s"nohup ${cmd.mkString(" ")} > ${lf.getAbsolutePath} 2>&1 & echo $$!"
              )
              pb.directory(root)
              val process = pb.start()
              val reader  = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream))
              val pidStr  = reader.readLine()
              reader.close()
              process.waitFor()
              pidStr
            }.flatMap { pidStr =>
              val pid = Option(pidStr).flatMap(_.trim.toLongOption)
              pid match {
                case Some(p) =>
                  IO.blocking {
                    val w = new PrintWriter(pf)
                    try w.print(p)
                    finally w.close()
                  } *> IO(Term.ok(s"Metals started (pid $p, log: ${lf.getAbsolutePath})")).as(ExitCode.Success)
                case None =>
                  IO(Term.err("Failed to start metals")).as(ExitCode.Error)
              }
            }
          }
        }
      } yield result
    }

  private def stop(): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      val pf = pidFile(root)
      IO.blocking {
        if (pf.exists()) {
          val pid = scala.io.Source.fromFile(pf).mkString.trim.toLongOption
          pid match {
            case Some(p) =>
              Term.info(s"Stopping metals (pid $p)...")
              Proc.signalProcess(p)
              pf.delete(): Unit
              Term.ok("Metals stopped")
            case None =>
              Term.err("Invalid PID file")
              pf.delete(): Unit
          }
        } else {
          Term.warn("No metals PID file found")
        }
      }.as(ExitCode.Success)
    }

  private def status(): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      val pf = pidFile(root)
      IO.blocking {
        if (pf.exists()) {
          val pid = scala.io.Source.fromFile(pf).mkString.trim.toLongOption
          pid match {
            case Some(p) =>
              val alive = scala.sys.process.Process(List("kill", "-0", p.toString)).!(
                scala.sys.process.ProcessLogger(_ => (), _ => ())
              ) == 0
              if (alive) println(s"Metals is running (pid $p)")
              else {
                println("Metals PID file exists but process is not running")
                pf.delete(): Unit
              }
            case None =>
              println("Invalid PID file")
              pf.delete(): Unit
          }
        } else {
          println("Metals is not running")
        }
      }.as(ExitCode.Success)
    }

  private val usage: String =
    """Usage: re-scale metals <command>
      |
      |Commands:
      |  install    Install metals-mcp server via Coursier
      |  start      Start metals server [--port N, default 7845]
      |  stop       Stop metals server
      |  status     Show server status""".stripMargin
}
