/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale db <table> <subcommand>` dispatcher.
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import rescale.common.Term

object DbCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "migration" :: rest => MigrationDb.run(rest)
      case "issues" :: rest    => IssuesDb.run(rest)
      case "audit" :: rest     => AuditDb.run(rest)
      case "merge" :: rest     => Merge.run(rest)
      case other :: _          =>
        IO(Term.err(s"Unknown db subcommand: $other")).as(ExitCode.Error)
    }

  private val usage: String =
    """Usage: re-scale db <command>
      |
      |Commands:
      |  migration <list|get|set|stats>
      |  issues    <list|add|resolve|stats>
      |  audit     <list|get|set|stats>
      |  merge     --target T --source S [--strategy renumber|prefer-left|prefer-right|stricter]
      |""".stripMargin
}
