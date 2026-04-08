/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Subprocess runner with stdout/stderr capture, IO-wrapped for CE
 * compatibility.
 *
 * Ported from ssg-dev `scripts/src/common/Proc.scala`. Most subcommands
 * (build, test, git, gh, sbt --client) shell out to other tools, so
 * this is the workhorse for almost everything in Phases 5+.
 *
 * The IO wrappers use `IO.blocking` because subprocess invocation IS
 * blocking — there's no async sys-process API on Scala Native. The
 * blocking pool semantically isolates these from the compute pool.
 *
 * Two output modes:
 *   - `run`  captures stdout/stderr to Strings (for parse-the-output
 *            patterns like `git status --porcelain`)
 *   - `exec` inherits stdout/stderr to the parent terminal (for
 *            interactive commands like `sbt --client compile` where
 *            we want the user to see the live build output)
 */
package rescale.common

import cats.effect.IO

import java.io.File
import scala.sys.process.{Process, ProcessLogger}

object Proc {

  final case class Result(exitCode: Int, stdout: String, stderr: String) {
    def ok: Boolean = exitCode == 0
  }

  /** Resolve a bare command name against `$PATH`. Absolute paths and
    * paths containing `/` are returned as-is.
    */
  private def resolveCmd(cmd: String): String = {
    if (cmd.startsWith("/") || cmd.contains("/")) cmd
    else {
      val path = sys.env.getOrElse("PATH", "")
      val found = path.split(File.pathSeparatorChar).iterator.map { dir =>
        new File(dir, cmd)
      }.find(f => f.isFile && f.canExecute)
      found.map(_.getAbsolutePath).getOrElse(cmd)
    }
  }

  /** Run a subprocess and capture stdout + stderr. Returns once the
    * subprocess exits.
    */
  def run(
    cmd:  String,
    args: List[String]      = Nil,
    cwd:  Option[File]      = None,
    env:  Map[String, String] = Map.empty
  ): IO[Result] =
    IO.blocking {
      val cmdList   = resolveCmd(cmd) :: args
      val stdoutBuf = new StringBuilder
      val stderrBuf = new StringBuilder
      val logger = ProcessLogger(
        line => { stdoutBuf.append(line); stdoutBuf.append('\n') },
        line => { stderrBuf.append(line); stderrBuf.append('\n') }
      )
      val exit =
        try Process(cmdList, cwd, env.toSeq*).!(logger)
        catch {
          case e: java.io.IOException =>
            stderrBuf.append(s"Failed to run: ${cmdList.mkString(" ")}: ${e.getMessage}\n")
            127
        }
      Result(exit, stdoutBuf.toString.stripTrailing, stderrBuf.toString.stripTrailing)
    }

  /** Run a subprocess with stdout/stderr inherited from the parent
    * terminal. Returns the exit code only.
    *
    * Use this for interactive commands like `sbt --client compile`
    * where the user wants to see live output instead of a captured
    * post-mortem.
    */
  def exec(
    cmd:  String,
    args: List[String]       = Nil,
    cwd:  Option[File]       = None,
    env:  Map[String, String] = Map.empty
  ): IO[Int] =
    IO.blocking {
      val cmdList = resolveCmd(cmd) :: args
      try Process(cmdList, cwd, env.toSeq*).!
      catch {
        case e: java.io.IOException =>
          Term.err(s"Failed to run: ${cmdList.mkString(" ")}: ${e.getMessage}")
          127
      }
    }

  /** Send a signal to a pid. Returns true if `kill` exited cleanly. */
  def signalProcess(pid: Long, signal: Int = 15): IO[Boolean] =
    run("sh", List("-c", s"kill -$signal $pid")).map(_.ok)

  /** Check whether a pid is alive (signal 0 ping). */
  def isAlive(pid: Long): IO[Boolean] =
    run("sh", List("-c", s"kill -0 $pid 2>/dev/null")).map(_.ok)
}
