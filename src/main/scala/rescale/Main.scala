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
       |Commands (planned — implemented phase-by-phase):
       |  hook        PreToolUse validator
       |  db          Database queries (migration, issues, audit)
       |  enforce     Covenant verify, shortcuts scan, stale-stubs, skip-policy
       |  git         Git and GitHub operations (Phase 5)
       |  build       Build commands (Phase 5)
       |  quality     Quality scans (Phase 5)
       |  test        Test orchestration (Phase 5)
       |  compare     Source/Scala file comparison (Phase 5)
       |  proc        Process management (Phase 5)
       |  version     Print version
       |
       |Options:
       |  --help      Show this help
       |  --version   Show version""".stripMargin
}
