/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale git` — git + GitHub CLI passthroughs.
 *
 * The PreToolUse hook (rescale.hook) gates these commands by category:
 * read-only ops are auto-allowed, write ops are gated, and dangerous
 * ops (force-push, reset --hard) are denied. Routing through `re-scale
 * git` instead of bare `git` lets the hook see a single canonical
 * subcommand instead of having to parse arbitrary git argv strings.
 */
package rescale.git

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Proc, Term}

import java.io.File

object GitCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      // Read-only
      case "status" :: rest      => gitPass("status" :: rest)
      case "diff" :: rest        => gitPass("diff" :: rest)
      case "diff-staged" :: rest => gitPass("diff" :: "--cached" :: rest)
      case "diff-stat" :: rest   => gitPass("diff" :: "--stat" :: rest)
      case "diff-count" :: _     => diffCount()
      case "log" :: rest         => gitLog(rest)
      case "log-full" :: rest    => gitLogFull(rest)
      case "show" :: rest        => gitPass("show" :: rest)
      case "branch" :: rest      => gitPass("branch" :: rest)
      case "blame" :: rest       => gitPass("blame" :: rest)
      case "tags" :: _           => gitPass(List("tag", "-l"))
      // Write
      case "stage" :: rest       => gitPass("add" :: rest)
      case "stage-all" :: _      => gitPass(List("add", "-A"))
      case "commit" :: rest      => gitCommit(rest)
      case "push" :: rest        => gitPass("push" :: rest)
      // GitHub
      case "gh" :: rest          => gh(rest)
      case other :: _ =>
        IO(Term.err(s"Unknown git command: $other")).as(ExitCode.Error)
    }

  // -- core helpers --------------------------------------------------

  private def withRoot(f: File => IO[ExitCode]): IO[ExitCode] =
    Paths.projectRoot.flatMap(f)

  private def gitPass(args: List[String]): IO[ExitCode] =
    withRoot(root => Proc.exec("git", args, cwd = Some(root)).map(ExitCode.apply))

  private def gitLog(rest: List[String]): IO[ExitCode] = {
    val n = rest.headOption.filter(_.startsWith("-n")).map(_.drop(2)).getOrElse("20")
    gitPass(List("log", "--oneline", "-n", n))
  }

  private def gitLogFull(rest: List[String]): IO[ExitCode] = {
    val args = Cli.parse(rest)
    val n    = args.flagOrDefault("n", "10")
    gitPass(List("log", "--stat", "-n", n))
  }

  private def diffCount(): IO[ExitCode] =
    withRoot { root =>
      Proc.run("git", List("diff", "--stat"), cwd = Some(root)).flatMap { result =>
        IO {
          if (result.ok) {
            val lines = result.stdout.split("\n").filter(_.nonEmpty)
            if (lines.nonEmpty) println(lines.last) else println("No changes")
          }
        }.as(ExitCode(result.exitCode))
      }
    }

  private def gitCommit(rest: List[String]): IO[ExitCode] = {
    val args    = Cli.parse(rest)
    val message = args.flag("m").orElse(args.flag("message"))
    message match {
      case Some(msg) => gitPass(List("commit", "-m", msg))
      case None =>
        IO(Term.err("Commit message required: re-scale git commit --m 'message'"))
          .as(ExitCode.Error)
    }
  }

  // -- gh subcommands ------------------------------------------------

  private def gh(rest: List[String]): IO[ExitCode] = rest match {
    case Nil | "--help" :: _ =>
      IO.println(ghUsage).as(ExitCode.Success)
    case "pr" :: sub :: rest2    => ghExec("pr" :: sub :: rest2)
    case "issue" :: sub :: rest2 => ghExec("issue" :: sub :: rest2)
    case "run" :: sub :: rest2   => ghExec("run" :: sub :: rest2)
    case "api" :: rest2          => ghExec("api" :: rest2)
    case other :: _ =>
      IO(Term.err(s"Unknown gh command: $other")).as(ExitCode.Error)
  }

  private def ghExec(args: List[String]): IO[ExitCode] =
    withRoot(root => Proc.exec("gh", args, cwd = Some(root)).map(ExitCode.apply))

  private val ghUsage: String =
    """Usage: re-scale git gh <command>
      |
      |Commands:
      |  pr list/view/diff/checks
      |  issue list/view
      |  run list/view/log
      |  api <endpoint>""".stripMargin

  private val usage: String =
    """Usage: re-scale git <command>
      |
      |Read-only:
      |  status / diff / diff-staged / diff-stat / diff-count
      |  log / log-full / show / branch / blame / tags
      |
      |Write:
      |  stage / stage-all / commit --m 'message' / push
      |
      |GitHub:
      |  gh pr|issue|run|api ...""".stripMargin
}
