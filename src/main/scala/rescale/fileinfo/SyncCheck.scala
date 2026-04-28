/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * DB-to-file sync verification for the `fileinfo` command. Compares
 * migration.tsv records against in-file header properties and reports
 * mismatches.
 */
package rescale.fileinfo

import cats.effect.{ExitCode, IO}
import fs2.Stream
import fs2.io.file.Path
import rescale.common.{Paths, Term, Tsv}
import rescale.db.MigrationDb

import java.io.File

object SyncCheck {

  final case class Mismatch(
    filePath:  String,
    kind:      String,
    dbValue:   String,
    fileValue: String
  )

  def check(
    root:   File,
    files:  Stream[IO, Path],
    pred:   Option[Predicate.Expr],
    mr:     Boolean
  ): IO[ExitCode] = {
    val migFile = Paths.migrationTsv(root)
    for {
      migExists <- IO.blocking(migFile.exists())
      migTable  <- if (migExists) Tsv.read(migFile) else IO.pure(Tsv.Table.empty)
      fileProps <- files
                     .evalMap(p => FileHeader.parse(p).map(fp => (p, fp)))
                     .filter { case (_, fp) => pred.forall(Predicate.evaluate(_, fp.properties)) }
                     .compile
                     .toList
      mismatches = findMismatches(root, migTable, fileProps)
      _ <- IO {
        if (mismatches.isEmpty) {
          if (mr) println("(no mismatches)")
          else Term.ok("DB and file headers are in sync")
        } else {
          if (mr) {
            println("# file\tkind\tdb_value\tfile_value")
            mismatches.foreach { m =>
              println(s"${m.filePath}\t${m.kind}\t${m.dbValue}\t${m.fileValue}")
            }
          } else {
            println(s"Found ${mismatches.size} mismatch(es):")
            mismatches.foreach { m =>
              println(f"  ${m.kind}%-22s ${m.filePath}")
              if (m.dbValue.nonEmpty || m.fileValue.nonEmpty)
                println(f"${""}%-24s db=${m.dbValue}  file=${m.fileValue}")
            }
          }
        }
      }
    } yield if (mismatches.nonEmpty) ExitCode.Error else ExitCode.Success
  }

  private def findMismatches(
    root:      File,
    migTable:  Tsv.Table,
    fileProps: List[(Path, FileHeader.FileProperties)]
  ): List[Mismatch] = {
    val dbByPortPath = migTable.rows.flatMap { row =>
      val pp = MigrationDb.portPath(row)
      if (pp.nonEmpty) Some(pp -> row) else None
    }.toMap

    val dbBySourcePath = migTable.rows.flatMap { row =>
      row.get("source_path").filter(_.nonEmpty).map(_ -> row)
    }.toMap

    val mismatches = scala.collection.mutable.ListBuffer.empty[Mismatch]

    fileProps.foreach { case (p, fp) =>
      val relPath = {
        val rootPath = root.toPath.toAbsolutePath
        val filePath = p.toNioPath.toAbsolutePath
        if (filePath.startsWith(rootPath)) rootPath.relativize(filePath).toString
        else filePath.toString
      }

      val originalSrc = FileHeader.sourceReference(fp.properties)

      originalSrc.foreach { src =>
        val dbRow = dbByPortPath.get(relPath).orElse(dbBySourcePath.get(src))
        dbRow match {
          case None =>
            mismatches += Mismatch(relPath, "in-file-not-in-db", "", src)
          case Some(row) =>
            val dbCommit   = row.getOrElse("source_sync_commit", "")
            val fileCommit = fp.properties.getOrElse("upstream-commit", "")
            if (dbCommit.nonEmpty && fileCommit.nonEmpty && dbCommit != fileCommit) {
              mismatches += Mismatch(relPath, "commit-mismatch", dbCommit, fileCommit)
            }
            val dbStatus   = row.getOrElse("status", "")
            val fileStatus = fp.properties.getOrElse("status", "")
            if (dbStatus.nonEmpty && fileStatus.nonEmpty && dbStatus != fileStatus) {
              mismatches += Mismatch(relPath, "status-mismatch", dbStatus, fileStatus)
            }
        }
      }
    }

    val filesWithOriginalSrc = fileProps.flatMap { case (p, fp) =>
      val relPath = {
        val rootPath = root.toPath.toAbsolutePath
        val filePath = p.toNioPath.toAbsolutePath
        if (filePath.startsWith(rootPath)) rootPath.relativize(filePath).toString
        else filePath.toString
      }
      FileHeader.sourceReference(fp.properties).map(_ => relPath)
    }.toSet

    migTable.rows.foreach { row =>
      val pp = MigrationDb.portPath(row)
      if (pp.nonEmpty && !filesWithOriginalSrc.contains(pp)) {
        val status = row.getOrElse("status", "")
        if (status == "ported" || status == "done") {
          mismatches += Mismatch(pp, "in-db-not-in-file", status, "")
        }
      }
    }

    mismatches.toList
  }
}
