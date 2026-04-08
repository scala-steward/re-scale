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
 * Three output modes:
 *   - `run`         captures stdout/stderr to Strings (for parse-the-output
 *                   patterns like `git status --porcelain`). **Only safe
 *                   for commands with bounded output** — if the subprocess
 *                   can produce megabytes of text, use [[runStreamed]]
 *                   instead, otherwise the `StringBuilder`s will grow
 *                   unboundedly and eventually OOM the wrapper.
 *   - `runStreamed` hands each stdout/stderr line to a [[LineSink]] as
 *                   it arrives. The sink decides what (if anything) to
 *                   retain. This is the correct primitive for runners
 *                   that scan verbose test output (sass-spec, Jest,
 *                   etc.) — it keeps the in-process footprint bounded
 *                   by whatever the sink chooses to hold on to.
 *   - `exec`        inherits stdout/stderr to the parent terminal (for
 *                   interactive commands like `sbt --client compile`
 *                   where we want the user to see the live build output)
 */
package rescale.common

import cats.effect.IO

import java.io.File
import scala.sys.process.{Process, ProcessLogger}

object Proc {

  final case class Result(exitCode: Int, stdout: String, stderr: String) {
    def ok: Boolean = exitCode == 0
  }

  /** Per-line sink for [[runStreamed]]. Callbacks fire on the
    * sys.process reader threads; implementations that share state
    * between stdout and stderr **must** synchronize that state
    * themselves.
    */
  trait LineSink {
    def onOut(line: String): Unit
    def onErr(line: String): Unit
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

  /** Run a subprocess, feeding each stdout/stderr line to `sink` as it
    * arrives. Nothing is buffered inside Proc itself — the in-process
    * footprint is whatever the sink chooses to retain.
    *
    * THIS is the primitive to use whenever the subprocess can produce
    * more than a few KB of output. sass-spec, Jest, `sbt compile` on a
    * large module, and similar verbose runners all fall in this
    * category — using [[run]] on them retains the full transcript in
    * memory and was the direct cause of the sass-spec 48 GB incident.
    */
  def runStreamed(
    cmd:  String,
    args: List[String]       = Nil,
    cwd:  Option[File]       = None,
    env:  Map[String, String] = Map.empty,
    sink: LineSink
  ): IO[Int] =
    IO.blocking {
      val cmdList = resolveCmd(cmd) :: args
      val logger  = ProcessLogger(line => sink.onOut(line), line => sink.onErr(line))
      try Process(cmdList, cwd, env.toSeq*).!(logger)
      catch {
        case e: java.io.IOException =>
          sink.onErr(s"Failed to run: ${cmdList.mkString(" ")}: ${e.getMessage}")
          127
      }
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
