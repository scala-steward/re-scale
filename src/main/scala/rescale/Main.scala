/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * re-scale entry point. Phase 0 scaffolding — only prints the version.
 * Real command dispatch lands in Phases 1-5. This IOApp.Simple stub is
 * enough to prove the sbt + sbt-scala-native + cats-effect wiring works
 * end-to-end: `sbt nativeLink` produces a binary, the wrapper runs it,
 * and the memory-bound tests can spawn it as a subprocess.
 */
package rescale

import cats.effect.{ExitCode, IO, IOApp}
import rescale.hook.HookCmd

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ | "-h" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "--version" :: _ | "version" :: _ =>
        IO.println(s"re-scale ${Version.value}").as(ExitCode.Success)
      case "hook" :: rest =>
        HookCmd.run(rest)
      case "db" :: rest =>
        rescale.db.DbCmd.run(rest)
      case "enforce" :: rest =>
        rescale.enforce.EnforceCmd.run(rest)
      case "build" :: rest =>
        rescale.build.BuildCmd.run(rest)
      case "test" :: rest =>
        rescale.test.TestCmd.run(rest)
      case "git" :: rest =>
        rescale.git.GitCmd.run(rest)
      case "proc" :: rest =>
        rescale.proc.ProcCmd.run(rest)
      case "doctor" :: rest =>
        rescale.doctor.DoctorCmd.run(rest)
      case "runner" :: rest =>
        rescale.runner.RunnerCmd.run(rest)
      case "metals" :: rest =>
        rescale.metals.MetalsCmd.run(rest)
      case unknown :: _ =>
        IO.println(s"re-scale: unknown command '$unknown'")
          .flatMap(_ => IO.println(usage))
          .as(ExitCode.Error)
    }

  private val usage: String =
    s"""re-scale ${Version.value} — Scala Native porting toolkit
       |
       |Usage: re-scale <command> [args...]
       |
       |Commands:
       |  hook        PreToolUse validator (per-project rules: .rescale/claude-hooks.yaml)
       |  db          Database queries (migration, issues, audit)
       |  enforce     Covenant verify, shortcuts scan, stale-stubs, skip-policy
       |  build       Compile / fmt / publish-local / kill-sbt
       |  test        Unit tests + cross-platform verify
       |  git         Git read / write + gh PR/issue/run/api
       |  proc        Process discovery + targeted termination
       |  doctor      Project dev-env bootstrap (.rescale/doctor.yaml)
       |  runner      Generic test-runner adapter (.rescale/runners.yaml)
       |  metals      Metals LSP server lifecycle (install/start/stop/status)
       |  version     Print version
       |
       |Options:
       |  --help      Show this help
       |  --version   Show version""".stripMargin
}
