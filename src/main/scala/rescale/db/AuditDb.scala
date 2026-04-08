/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Audit database — per-file pass/minor_issues/major_issues verdicts
 * with notes. Ported from ssg-dev with all I/O wrapped in `IO` and
 * the modify path going through Tsv.modify (atomic file lock + safe
 * release on cancellation).
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Paths, Term, Tsv}
import rescale.common.Tsv.Table

import java.io.File
import java.time.LocalDate

object AuditDb {

  val headers: List[String] = List("file_path", "package", "status", "tested", "last_audited", "notes", "source")

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "list" :: rest  => list(Cli.parse(rest))
      case "get" :: rest   => get(Cli.parse(rest))
      case "set" :: rest   => set(Cli.parse(rest))
      case "stats" :: _    => stats()
      case other :: _      =>
        IO(Term.err(s"Unknown audit command: $other")).as(ExitCode.Error)
    }

  def list(args: Cli.Args): IO[ExitCode] =
    for {
      file <- auditFile
      base <- loadOrEmpty(file)
      filtered = applyFilters(base, args)
      paged    = filtered.paginate(args.flag("limit").map(_.toInt), args.flag("offset").map(_.toInt))
      _        = printTable(paged)
    } yield ExitCode.Success

  def get(args: Cli.Args): IO[ExitCode] = {
    val path = args.requirePositional(0, "file_path")
    for {
      file <- auditFile
      base <- loadOrEmpty(file)
      _ <- base.find(_.getOrElse("file_path", "").contains(path)) match {
             case Some(row) =>
               IO(headers.foreach(h => println(s"  $h: ${row.getOrElse(h, "")}")))
             case None =>
               IO(Term.err(s"Not found: $path"))
           }
    } yield ExitCode.Success
  }

  def set(args: Cli.Args): IO[ExitCode] = {
    val path = args.requirePositional(0, "file_path")
    val updates = scala.collection.mutable.Map.empty[String, String]
    args.flag("status").foreach(s => updates("status") = s)
    args.flag("tested").foreach(t => updates("tested") = t)
    args.flag("notes").foreach(n => updates("notes") = n)
    updates("last_audited") = LocalDate.now().toString
    val updatesMap = updates.toMap

    for {
      file <- auditFile
      _    <- Tsv.modify(file) { loaded =>
                val tbl = if (loaded.headers.isEmpty)
                  Table(headers, Nil, List("# re-scale Audit Database"))
                else loaded
                if (tbl.rows.exists(_.getOrElse("file_path", "") == path)) {
                  tbl.updateRow(_.getOrElse("file_path", "") == path, updatesMap)
                } else {
                  val pkg = path.split("/").dropRight(1).lastOption.getOrElse("")
                  val row = Map(
                    "file_path"    -> path,
                    "package"      -> pkg,
                    "status"       -> updatesMap.getOrElse("status", "pass"),
                    "tested"       -> updatesMap.getOrElse("tested", "no"),
                    "last_audited" -> updatesMap.getOrElse("last_audited", LocalDate.now().toString),
                    "notes"        -> updatesMap.getOrElse("notes", "")
                  )
                  tbl.addRow(row)
                }
              }
      _ <- IO(Term.ok(s"Updated: $path"))
    } yield ExitCode.Success
  }

  def stats(): IO[ExitCode] =
    for {
      file <- auditFile
      tbl  <- loadOrEmpty(file)
      _ <- IO {
             println("=== Audit Summary ===")
             tbl.stats("status").toList.sortBy(-_._2).foreach { case (s, c) => println(f"  $s%-20s $c%d") }
             println(f"  ${"Total"}%-20s ${tbl.size}%d")
             println()
             println("=== By Package ===")
             tbl.stats("package").toList.sortBy(_._1).foreach { case (p, c) => println(f"  $p%-30s $c%d") }
             println()
             println("=== Test Coverage ===")
             tbl.stats("tested").toList.sortBy(-_._2).foreach { case (t, c) => println(f"  $t%-15s $c%d") }
           }
    } yield ExitCode.Success

  // -- helpers ---------------------------------------------------------

  private def auditFile: IO[File] =
    Paths.projectRoot.map(Paths.auditTsv)

  private def loadOrEmpty(file: File): IO[Table] =
    IO(file.exists()).flatMap {
      case true  => Tsv.read(file)
      case false => IO.pure(Table(headers, Nil, List("# re-scale Audit Database")))
    }

  private def applyFilters(table: Table, args: Cli.Args): Table = {
    var t = table
    args.flag("status").foreach(s => t = t.filter(_.getOrElse("status", "") == s))
    args.flag("package").foreach(p => t = t.filter(_.getOrElse("package", "").contains(p)))
    args.flag("tested").foreach(x => t = t.filter(_.getOrElse("tested", "") == x))
    t
  }

  private def printTable(table: Table): Unit = {
    if (table.rows.isEmpty) { println("(no results)"); return }
    val display = List("file_path", "package", "status", "tested", "notes")
    println(Term.table(display, table.rows.map(r => display.map(h => r.getOrElse(h, "")))))
  }

  private val usage: String =
    """Usage: re-scale db audit <command>
      |
      |Commands:
      |  list [--status S] [--package P] [--tested T] [--limit N] [--offset N]
      |  get <file_path>
      |  set <file_path> --status S [--tested T] [--notes TEXT]
      |  stats     Summary counts""".stripMargin
}
