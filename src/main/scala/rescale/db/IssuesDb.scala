/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Issues database — open issues with id / category / severity / status.
 * Ported from ssg-dev with `add` performing atomic ID allocation inside
 * the file lock so concurrent `add` calls cannot collide on ISS-NNN.
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Term, Tsv}
import rescale.common.Tsv.Table

import java.io.File
import java.time.LocalDate

object IssuesDb {

  val headers: List[String] = List("id", "file_path", "category", "status", "severity", "description", "last_updated", "source")

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "list" :: rest    => list(Cli.parse(rest))
      case "add" :: rest     => add(Cli.parse(rest))
      case "resolve" :: rest => resolve(Cli.parse(rest))
      case "reopen" :: rest  => reopen(Cli.parse(rest))
      case "stats" :: _      => stats()
      case other :: _ =>
        IO(Term.err(s"Unknown issues command: $other")).as(ExitCode.Error)
    }

  def list(args: Cli.Args): IO[ExitCode] =
    for {
      file <- issuesFile
      base <- loadOrEmpty(file)
      filtered = applyFilters(base, args)
      paged    = filtered.paginate(args.flag("limit").map(_.toInt), args.flag("offset").map(_.toInt))
      _        = printTable(paged)
    } yield ExitCode.Success

  def add(args: Cli.Args): IO[ExitCode] = {
    val filePath    = args.requirePositional(0, "file_path")
    val category    = args.requirePositional(1, "category")
    val severity    = args.requirePositional(2, "severity")
    val description = args.requirePositional(3, "description")

    for {
      file <- issuesFile
      assigned <- Tsv.modifyWith(file) { loaded =>
                    val tbl = if (loaded.headers.isEmpty)
                      Table(headers, Nil, List("# re-scale Issues Database"))
                    else loaded
                    val nextId = {
                      val existing = tbl.rows.flatMap(_.get("id")).filter(_.startsWith("ISS-"))
                      if (existing.isEmpty) 1
                      else existing.map(_.stripPrefix("ISS-").toIntOption.getOrElse(0)).max + 1
                    }
                    val newId = f"ISS-$nextId%03d"
                    val row = Map(
                      "id"           -> newId,
                      "file_path"    -> filePath,
                      "category"     -> category,
                      "status"       -> "open",
                      "severity"     -> severity,
                      "description"  -> description,
                      "last_updated" -> LocalDate.now().toString,
                      "source"       -> "manual"
                    )
                    (tbl.addRow(row), newId)
                  }
      _ <- IO(Term.ok(s"Added: $assigned — $description"))
    } yield ExitCode.Success
  }

  def resolve(args: Cli.Args): IO[ExitCode] = {
    val id = args.requirePositional(0, "id")
    val updates = scala.collection.mutable.Map[String, String](
      "status"       -> "resolved",
      "last_updated" -> LocalDate.now().toString
    )
    args.flag("notes").foreach(n => updates("description") = updates.getOrElse("description", "") + " — " + n)
    val updatesMap = updates.toMap

    for {
      file <- issuesFile
      found <- Tsv.modifyWith(file) { tbl =>
                 val isPresent = tbl.rows.exists(_.getOrElse("id", "") == id)
                 if (!isPresent) (tbl, false)
                 else (tbl.updateRow(_.getOrElse("id", "") == id, updatesMap), true)
               }
      result <- if (!found) IO(Term.err(s"Not found: $id")).as(ExitCode.Error)
                else IO(Term.ok(s"Resolved: $id")).as(ExitCode.Success)
    } yield result
  }

  def reopen(args: Cli.Args): IO[ExitCode] = {
    val id = args.requirePositional(0, "id")
    val updates = Map(
      "status"       -> "open",
      "last_updated" -> LocalDate.now().toString
    )
    for {
      file <- issuesFile
      found <- Tsv.modifyWith(file) { tbl =>
                 val isPresent = tbl.rows.exists(_.getOrElse("id", "") == id)
                 if (!isPresent) (tbl, false)
                 else (tbl.updateRow(_.getOrElse("id", "") == id, updates), true)
               }
      result <- if (!found) IO(Term.err(s"Not found: $id")).as(ExitCode.Error)
                else IO(Term.ok(s"Reopened: $id")).as(ExitCode.Success)
    } yield result
  }

  def stats(): IO[ExitCode] =
    for {
      file <- issuesFile
      tbl  <- loadOrEmpty(file)
      _ <- IO {
             println("=== Issues Summary ===")
             tbl.stats("status").toList.sortBy(-_._2).foreach { case (s, c) => println(f"  $s%-20s $c%d") }
             println(f"  ${"Total"}%-20s ${tbl.size}%d")
             println()
             println("=== By Category ===")
             tbl.stats("category").toList.sortBy(-_._2).foreach { case (c, n) => println(f"  $c%-25s $n%d") }
             println()
             println("=== By Severity ===")
             tbl.stats("severity").toList.sortBy(-_._2).foreach { case (s, n) => println(f"  $s%-15s $n%d") }
           }
    } yield ExitCode.Success

  // -- helpers ---------------------------------------------------------

  private def issuesFile: IO[File] =
    Paths.projectRoot.map(Paths.issuesTsv)

  private def loadOrEmpty(file: File): IO[Table] =
    IO(file.exists()).flatMap {
      case true  => Tsv.read(file)
      case false => IO.pure(Table(headers, Nil, List("# re-scale Issues Database")))
    }

  private def applyFilters(table: Table, args: Cli.Args): Table = {
    var t = table
    args.flag("status").foreach(s => t = t.filter(_.getOrElse("status", "") == s))
    args.flag("category").foreach(c => t = t.filter(_.getOrElse("category", "").contains(c)))
    args.flag("file").foreach(f => t = t.filter(_.getOrElse("file_path", "").contains(f)))
    args.flag("severity").foreach(s => t = t.filter(_.getOrElse("severity", "") == s))
    t
  }

  private def printTable(table: Table): Unit = {
    if (table.rows.isEmpty) { println("(no results)"); return }
    val display = List("id", "file_path", "category", "status", "severity", "description")
    println(Term.table(display, table.rows.map(r => display.map(h => r.getOrElse(h, "")))))
  }

  private val usage: String =
    """Usage: re-scale db issues <command>
      |
      |Commands:
      |  list [--status S] [--category C] [--file PATH] [--severity SEV] [--limit N] [--offset N]
      |  add <file> <category> <severity> <description>
      |  resolve <id> [--notes TEXT]
      |  stats     Summary counts""".stripMargin
}
