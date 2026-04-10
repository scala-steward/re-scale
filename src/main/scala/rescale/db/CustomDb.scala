/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Custom DB tables driven by `.rescale/databases.yaml`.
 *
 * Lets any project define its own TSV-backed tables with arbitrary
 * columns, a key column, and full CRUD via `re-scale db <table> <op>`.
 * The ssg-sass port-tasks workflow is the motivating use case — instead
 * of hard-coding `port-tasks.tsv` into the binary, the table schema
 * lives in the YAML config and re-scale provides generic list / get /
 * set / stats / add operations on any declared table.
 *
 * Schema example (`.rescale/databases.yaml`):
 *
 *   tables:
 *     port-tasks:
 *       file: port-tasks.tsv
 *       key: id
 *       headers: [id, file, status, source, notes, updated]
 *       defaults:
 *         status: pending
 *     sass-spec-baseline:
 *       file: sass-spec-baseline.tsv
 *       key: test
 *       headers: [test, result, timestamp]
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import hearth.kindlings.yamlderivation.{KindlingsYamlCodec, YamlConfig}
import org.virtuslab.yaml.*
import rescale.common.{Cli, Paths, Term, Tsv}

import java.io.File
import scala.io.Source

given YamlConfig = YamlConfig().withUseDefaults

final case class DatabasesConfig(
  tables: Map[String, DatabasesConfig.TableDef] = Map.empty
) derives KindlingsYamlCodec

object DatabasesConfig {

  final case class TableDef(
    file:     String,
    key:      String,
    headers:  List[String],
    defaults: Map[String, String] = Map.empty
  ) derives KindlingsYamlCodec

  def load(configFile: File): Either[String, DatabasesConfig] = {
    if (!configFile.exists()) Right(DatabasesConfig(Map.empty))
    else {
      val src = Source.fromFile(configFile)
      val text =
        try src.mkString
        finally src.close()
      text.as[DatabasesConfig] match {
        case Left(err)  => Left(s"YAML parse error: $err")
        case Right(cfg) => Right(cfg)
      }
    }
  }

  def configPath(root: File): File = new File(root, ".rescale/databases.yaml")
}

object CustomDb {

  /** Try to handle a `re-scale db <name> <args>` where <name> is a
    * custom table defined in `.rescale/databases.yaml`. Returns None
    * if the table isn't defined (so DbCmd can fall back to its error).
    */
  def tryRun(tableName: String, rest: List[String]): IO[Option[ExitCode]] =
    Paths.projectRoot.flatMap { root =>
      val cfg = DatabasesConfig.configPath(root)
      IO.blocking(DatabasesConfig.load(cfg)).flatMap {
        case Left(err) =>
          IO(Term.warn(s"re-scale db: ignoring $cfg ($err)")).as(None)
        case Right(dbCfg) =>
          dbCfg.tables.get(tableName) match {
            case None      => IO.pure(None)
            case Some(tbl) => run(root, tableName, tbl, rest).map(Some(_))
          }
      }
    }

  private def run(root: File, name: String, tbl: DatabasesConfig.TableDef, args: List[String]): IO[ExitCode] = {
    val tsvFile = new File(Paths.dataDir(root), tbl.file)
    args match {
      case Nil | "list" :: _ =>
        list(tsvFile, tbl, Cli.parse(args.drop(1)))
      case "get" :: rest =>
        get(tsvFile, tbl, Cli.parse(rest))
      case "set" :: rest =>
        set(tsvFile, tbl, Cli.parse(rest))
      case "add" :: rest =>
        add(tsvFile, tbl, Cli.parse(rest))
      case "stats" :: _ =>
        stats(tsvFile, tbl)
      case "delete" :: rest =>
        delete(tsvFile, tbl, Cli.parse(rest))
      case _ =>
        IO(Term.err(s"Usage: re-scale db $name <list|get|set|add|delete|stats> [--key VALUE] [--COL VALUE ...]"))
          .as(ExitCode.Error)
    }
  }

  private def list(file: File, tbl: DatabasesConfig.TableDef, args: Cli.Args): IO[ExitCode] =
    Tsv.readOrEmpty(file, tbl.headers).flatMap { table =>
      val filtered = args.flag("status") match {
        case Some(s) => table.filter(_.getOrElse("status", "") == s)
        case None    => table
      }
      IO {
        if (filtered.rows.isEmpty) {
          println("(no rows)")
        } else {
          filtered.rows.foreach { row =>
            val line = tbl.headers.map(h => row.getOrElse(h, "")).mkString("\t")
            println(line)
          }
          println(s"\n${filtered.rows.size} row(s)")
        }
      }.as(ExitCode.Success)
    }

  private def get(file: File, tbl: DatabasesConfig.TableDef, args: Cli.Args): IO[ExitCode] = {
    val keyVal = args.flag("key").orElse(args.positionalAt(0))
    keyVal match {
      case None =>
        IO(Term.err(s"Missing --key: re-scale db <table> get --key VALUE")).as(ExitCode.Error)
      case Some(k) =>
        Tsv.readOrEmpty(file, tbl.headers).flatMap { table =>
          table.find(_.getOrElse(tbl.key, "") == k) match {
            case None =>
              IO(Term.err(s"No row with ${tbl.key}=$k")).as(ExitCode.Error)
            case Some(row) =>
              IO {
                tbl.headers.foreach { h =>
                  println(s"$h: ${row.getOrElse(h, "")}")
                }
              }.as(ExitCode.Success)
          }
        }
    }
  }

  private def set(file: File, tbl: DatabasesConfig.TableDef, args: Cli.Args): IO[ExitCode] = {
    val keyVal = args.flag("key").orElse(args.positionalAt(0))
    keyVal match {
      case None =>
        IO(Term.err(s"Missing --key: re-scale db <table> set --key VALUE --COL VALUE ...")).as(ExitCode.Error)
      case Some(k) =>
        modifyCustom(file, tbl) { table =>
          val idx = table.rows.indexWhere(_.getOrElse(tbl.key, "") == k)
          if (idx < 0) {
            Term.err(s"No row with ${tbl.key}=$k")
            table
          } else {
            val row     = table.rows(idx)
            val updates = tbl.headers.flatMap(h => args.flag(h).map(h -> _)).toMap
            val newRow  = row ++ updates
            table.copy(rows = table.rows.updated(idx, newRow))
          }
        }.flatMap(_ => IO(Term.ok(s"Updated ${tbl.key}=$keyVal")).as(ExitCode.Success))
    }
  }

  private def add(file: File, tbl: DatabasesConfig.TableDef, args: Cli.Args): IO[ExitCode] = {
    val row = tbl.headers.map { h =>
      h -> args.flag(h).orElse(tbl.defaults.get(h)).getOrElse("")
    }.toMap
    if (row.getOrElse(tbl.key, "").isEmpty) {
      IO(Term.err(s"Missing --${tbl.key} (the key column)")).as(ExitCode.Error)
    } else {
      modifyCustom(file, tbl) { table =>
        table.copy(rows = table.rows :+ row)
      }.flatMap(_ => IO(Term.ok(s"Added row: ${tbl.key}=${row(tbl.key)}")).as(ExitCode.Success))
    }
  }

  private def delete(file: File, tbl: DatabasesConfig.TableDef, args: Cli.Args): IO[ExitCode] = {
    val keyVal = args.flag("key").orElse(args.positionalAt(0))
    keyVal match {
      case None =>
        IO(Term.err(s"Missing --key: re-scale db <table> delete --key VALUE")).as(ExitCode.Error)
      case Some(k) =>
        modifyCustom(file, tbl) { table =>
          table.copy(rows = table.rows.filterNot(_.getOrElse(tbl.key, "") == k))
        }.flatMap(_ => IO(Term.ok(s"Deleted ${tbl.key}=$k")).as(ExitCode.Success))
    }
  }

  /** Modify a custom table's TSV file, creating it with the correct
    * headers if it doesn't exist yet.
    */
  private def modifyCustom(file: File, tbl: DatabasesConfig.TableDef)(fn: Tsv.Table => Tsv.Table): IO[Tsv.Table] = {
    // Ensure the file exists with headers before calling Tsv.modify
    IO.blocking {
      if (!file.exists()) {
        val parent = file.getParentFile
        if (parent != null && !parent.exists()) parent.mkdirs(): Unit
        val w = new java.io.PrintWriter(file)
        try w.println("# " + tbl.headers.mkString("\t"))
        finally w.close()
      }
    } *> Tsv.modify(file)(fn)
  }

  private def stats(file: File, tbl: DatabasesConfig.TableDef): IO[ExitCode] =
    Tsv.readOrEmpty(file, tbl.headers).flatMap { table =>
      IO {
        println(s"Total rows: ${table.rows.size}")
        // Group by status column if it exists
        if (tbl.headers.contains("status")) {
          val byStatus = table.rows.groupBy(_.getOrElse("status", "(empty)"))
          byStatus.toList.sortBy(-_._2.size).foreach { case (s, rows) =>
            println(f"  $s%-20s ${rows.size}%d")
          }
        }
      }.as(ExitCode.Success)
    }
}
