/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Migration database — original-source-to-port mapping with status
 * (ported / not_started / skipped / done). Ported from ssg-dev as a
 * read-mostly subset; the legacy `sync` command (which scans original
 * source trees and populates rows) is deferred to a follow-up commit.
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Term, Tsv}
import rescale.common.Tsv.Table

import java.io.File
import java.time.LocalDate

object MigrationDb {

  val headers: List[String] = List(
    "source_lib", "source_path", "ssg_path", "status", "module",
    "last_updated", "notes", "source_sync_commit", "last_sync_date"
  )

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "list" :: rest  => list(Cli.parse(rest))
      case "get" :: rest   => get(Cli.parse(rest))
      case "set" :: rest   => set(Cli.parse(rest))
      case "stats" :: _    => stats()
      case other :: _ =>
        IO(Term.err(s"Unknown migration command: $other")).as(ExitCode.Error)
    }

  def list(args: Cli.Args): IO[ExitCode] =
    for {
      file <- migrationFile
      base <- loadOrEmpty(file)
      filtered = applyFilters(base, args)
      paged    = filtered.paginate(args.flag("limit").map(_.toInt), args.flag("offset").map(_.toInt))
      _        = printTable(paged)
    } yield ExitCode.Success

  def get(args: Cli.Args): IO[ExitCode] = {
    val path = args.requirePositional(0, "source_path")
    for {
      file <- migrationFile
      base <- loadOrEmpty(file)
      _ <- base.find(_.getOrElse("source_path", "").contains(path)) match {
             case Some(row) =>
               IO(headers.foreach(h => println(s"  $h: ${row.getOrElse(h, "")}")))
             case None =>
               IO(Term.err(s"Not found: $path"))
           }
    } yield ExitCode.Success
  }

  def set(args: Cli.Args): IO[ExitCode] = {
    val path = args.requirePositional(0, "source_path")
    val updates = scala.collection.mutable.Map.empty[String, String]
    args.flag("status").foreach(s => updates("status") = s)
    args.flag("notes").foreach(n => updates("notes") = n)
    args.flag("ssg-path").foreach(p => updates("ssg_path") = p)
    updates("last_updated") = LocalDate.now().toString
    val updatesMap = updates.toMap

    for {
      file <- migrationFile
      _ <- Tsv.modify(file) { loaded =>
             val tbl = if (loaded.headers.isEmpty)
               Table(headers, Nil, List("# re-scale Migration Database"))
             else loaded
             tbl.updateRow(_.getOrElse("source_path", "") == path, updatesMap)
           }
      _ <- IO(Term.ok(s"Updated: $path"))
    } yield ExitCode.Success
  }

  def stats(): IO[ExitCode] =
    for {
      file <- migrationFile
      tbl  <- loadOrEmpty(file)
      _ <- IO {
             println("=== Migration Status ===")
             tbl.stats("status").toList.sortBy(-_._2).foreach { case (s, c) => println(f"  $s%-20s $c%d") }
             println(f"  ${"Total"}%-20s ${tbl.size}%d")
             println()
             println("=== By Library ===")
             tbl.stats("source_lib").toList.sortBy(-_._2).foreach { case (l, c) => println(f"  $l%-25s $c%d") }
             println()
             println("=== By Module ===")
             tbl.stats("module").toList.sortBy(-_._2).foreach { case (m, c) => println(f"  $m%-25s $c%d") }
           }
    } yield ExitCode.Success

  // -- helpers ---------------------------------------------------------

  private def migrationFile: IO[File] =
    Paths.projectRoot.map(Paths.migrationTsv)

  private def loadOrEmpty(file: File): IO[Table] =
    IO(file.exists()).flatMap {
      case true  => Tsv.read(file)
      case false => IO.pure(Table(headers, Nil, List("# re-scale Migration Database")))
    }

  private def applyFilters(table: Table, args: Cli.Args): Table = {
    var t = table
    args.flag("status").foreach(s => t = t.filter(_.getOrElse("status", "") == s))
    args.flag("lib").foreach(l => t = t.filter(_.getOrElse("source_lib", "") == l))
    args.flag("module").foreach(m => t = t.filter(_.getOrElse("module", "") == m))
    args.flag("package").foreach(p => t = t.filter(_.getOrElse("source_path", "").contains(s"/$p/")))
    t
  }

  private def printTable(table: Table): Unit = {
    if (table.rows.isEmpty) { println("(no results)"); return }
    val display = List("source_lib", "source_path", "ssg_path", "status", "module", "notes")
    println(Term.table(display, table.rows.map(r => display.map(h => r.getOrElse(h, "")))))
  }

  private val usage: String =
    """Usage: re-scale db migration <command>
      |
      |Commands:
      |  list [--status S] [--lib L] [--module M] [--package P] [--limit N] [--offset N]
      |  get <source_path>
      |  set <source_path> --status S [--notes TEXT] [--ssg-path P]
      |  stats     Summary counts""".stripMargin
}
