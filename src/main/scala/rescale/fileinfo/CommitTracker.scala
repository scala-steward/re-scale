/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Git commit tracking for the `fileinfo` command. For each file with
 * an original-src or source-reference header, looks up the latest
 * commit hash of the original file via `git log` and writes it into
 * the upstream-commit header field. Also auto-updates migration.tsv.
 */
package rescale.fileinfo

import cats.effect.{ExitCode, IO}
import fs2.Stream
import fs2.io.file.Path
import rescale.common.{Paths, Proc, Term, Tsv}

import java.io.File
import java.time.LocalDate

object CommitTracker {

  final case class TrackResult(
    filePath:    String,
    originalSrc: String,
    oldCommit:   Option[String],
    newCommit:   String,
    changed:     Boolean
  )

  def run(
    root:   File,
    files:  Stream[IO, Path],
    pred:   Option[Predicate.Expr],
    mr:     Boolean,
    dryRun: Boolean
  ): IO[ExitCode] = {
    files
      .evalMap(p => FileHeader.parse(p).map(fp => (p, fp)))
      .filter { case (_, fp) => pred.forall(Predicate.evaluate(_, fp.properties)) }
      .filter { case (_, fp) =>
        fp.properties.contains("original-src") || fp.properties.contains("source-reference")
      }
      .evalMap { case (p, fp) =>
        trackOne(p.toNioPath.toFile, fp, root, dryRun)
      }
      .compile
      .toList
      .flatMap { results =>
        val (successes, failures) = results.partition(_.isRight)
        val tracked = successes.collect { case Right(r) => r }
        val changed = tracked.filter(_.changed)

        IO {
          if (mr) {
            println("# file\toriginal_src\told_commit\tnew_commit\tchanged")
            tracked.foreach { r =>
              println(s"${r.filePath}\t${r.originalSrc}\t${r.oldCommit.getOrElse("")}\t${r.newCommit}\t${r.changed}")
            }
            failures.foreach { case Left(err) => System.err.println(err); case _ => () }
          } else {
            if (changed.nonEmpty)
              Term.ok(s"${changed.size} file(s) ${if (dryRun) "would be " else ""}updated")
            if (tracked.size > changed.size)
              Term.info(s"${tracked.size - changed.size} file(s) already up to date")
            failures.foreach { case Left(err) => Term.warn(err); case _ => () }
          }
        } *>
        updateMigrationDb(root, tracked.filter(_.changed), dryRun).as {
          if (failures.nonEmpty) ExitCode.Error else ExitCode.Success
        }
      }
  }

  private def trackOne(
    file:   File,
    fp:     FileHeader.FileProperties,
    root:   File,
    dryRun: Boolean
  ): IO[Either[String, TrackResult]] = {
    val originalSrc = fp.properties.get("original-src")
      .orElse(fp.properties.get("source-reference"))

    originalSrc match {
      case None =>
        IO.pure(Left(s"${file.getPath}: no original-src or source-reference header"))
      case Some(src) =>
        Proc.run("git", List("log", "-1", "--format=%H", "--", src), cwd = Some(root)).flatMap { r =>
          if (r.ok && r.stdout.trim.nonEmpty) {
            val newHash = r.stdout.trim
            val oldHash = fp.properties.get("upstream-commit")
            val changed = !oldHash.contains(newHash)
            val relPath = relativize(root, file)

            if (changed && !dryRun) {
              FileHeaderApply.setProperties(file, Map("upstream-commit" -> newHash)).map { _ =>
                Right(TrackResult(relPath, src, oldHash, newHash, changed = true))
              }
            } else {
              IO.pure(Right(TrackResult(relPath, src, oldHash, newHash, changed)))
            }
          } else {
            IO.pure(Left(s"${file.getPath}: git log failed for $src: ${r.stderr}"))
          }
        }
    }
  }

  private def updateMigrationDb(
    root:    File,
    results: List[TrackResult],
    dryRun:  Boolean
  ): IO[Unit] = {
    if (results.isEmpty || dryRun) IO.unit
    else {
      val migFile = Paths.migrationTsv(root)
      IO.blocking(migFile.exists()).flatMap {
        case false => IO.unit
        case true =>
          Tsv.modify(migFile) { table =>
            val today = LocalDate.now().toString
            results.foldLeft(table) { (t, r) =>
              t.updateRow(
                row => {
                  val ssgPath = row.getOrElse("ssg_path", "")
                  val srcPath = row.getOrElse("source_path", "")
                  ssgPath == r.filePath || srcPath == r.originalSrc
                },
                Map(
                  "source_sync_commit" -> r.newCommit,
                  "last_sync_date" -> today
                )
              )
            }
          }.void
      }
    }
  }

  private def relativize(root: File, file: File): String = {
    val rootPath = root.toPath.toAbsolutePath
    val filePath = file.toPath.toAbsolutePath
    if (filePath.startsWith(rootPath)) rootPath.relativize(filePath).toString
    else filePath.toString
  }
}
