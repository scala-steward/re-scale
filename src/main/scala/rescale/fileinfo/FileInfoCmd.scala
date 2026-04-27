/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale fileinfo` — query, filter, and batch-update file header
 * metadata. Provides a unified interface over Covenant-* and plain
 * key-value header fields.
 *
 * API:
 *   re-scale fileinfo --given <path|dir|glob> [--when <predicate>] --then <action>
 *
 * Actions:
 *   select field1,field2   Print selected fields from matching files
 *   select *               Print all fields
 *   set key1=val1,key2=val2  Batch-update fields in matching files
 *   sync-check             Verify DB records match file headers
 *   track-commit           Record upstream commit hash in file headers
 */
package rescale.fileinfo

import cats.effect.{ExitCode, IO}
import fs2.Stream
import fs2.io.file.{Files, Path}
import rescale.common.{Cli, FileOps, Paths, Term}

import java.io.File

object FileInfoCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case _ =>
        val parsed = Cli.parse(args)
        val givenPath = parsed.flagOrDefault("given", ".")
        val whenExpr  = parsed.flag("when")
        val thenStr   = parsed.flagOrDefault("then", "select *")
        val mr        = parsed.hasFlag("machine-readable")
        val dryRun    = parsed.hasFlag("dry-run")
        dispatch(givenPath, whenExpr, thenStr, mr, dryRun)
    }

  // -- action parsing ---------------------------------------------------

  private sealed trait Action
  private object Action {
    final case class Select(fields: List[String]) extends Action
    final case class Set(values: Map[String, String]) extends Action
    case object SyncCheck extends Action
    case object TrackCommit extends Action
  }

  private def parseAction(raw: String): Either[String, Action] = {
    val trimmed = raw.trim
    if (trimmed == "sync-check") Right(Action.SyncCheck)
    else if (trimmed == "track-commit") Right(Action.TrackCommit)
    else if (trimmed.startsWith("select")) {
      val fields = trimmed.drop("select".length).trim
      if (fields.isEmpty || fields == "*") Right(Action.Select(Nil))
      else Right(Action.Select(fields.split(",").map(_.trim).toList))
    } else if (trimmed.startsWith("set")) {
      val pairs = trimmed.drop("set".length).trim
      if (pairs.isEmpty) Left("set requires key=value pairs")
      else {
        val m = pairs.split(",").map(_.trim).flatMap { kv =>
          val idx = kv.indexOf('=')
          if (idx > 0) Some(kv.substring(0, idx).trim -> kv.substring(idx + 1).trim)
          else None
        }.toMap
        if (m.isEmpty) Left("set: no valid key=value pairs found")
        else Right(Action.Set(m))
      }
    } else Left(s"unknown action: $trimmed")
  }

  // -- dispatch ----------------------------------------------------------

  private def dispatch(
    givenPath: String,
    whenExpr:  Option[String],
    thenStr:   String,
    mr:        Boolean,
    dryRun:    Boolean
  ): IO[ExitCode] = {
    val predicate = whenExpr match {
      case None    => Right(None)
      case Some(w) => Predicate.parse(w).map(Some(_))
    }

    predicate match {
      case Left(err) =>
        IO(Term.err(s"--when: $err")).as(ExitCode.Error)
      case Right(pred) =>
        parseAction(thenStr) match {
          case Left(err) =>
            IO(Term.err(s"--then: $err")).as(ExitCode.Error)
          case Right(Action.Select(fields)) =>
            runSelect(givenPath, pred, fields, mr)
          case Right(Action.Set(values)) =>
            runSet(givenPath, pred, values, mr, dryRun)
          case Right(Action.SyncCheck) =>
            runSyncCheck(givenPath, pred, mr)
          case Right(Action.TrackCommit) =>
            runTrackCommit(givenPath, pred, mr, dryRun)
        }
    }
  }

  // -- select ------------------------------------------------------------

  private def runSelect(
    givenPath: String,
    pred:      Option[Predicate.Expr],
    fields:    List[String],
    mr:        Boolean
  ): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      val stream = resolveGiven(givenPath, root)
        .evalMap(p => FileHeader.parse(p).map(fp => (p, fp)))
        .filter { case (_, fp) => matchesPredicate(fp, pred) }

      if (mr) {
        stream.compile.toList.flatMap { results =>
          IO {
            if (results.isEmpty) { println("(no results)") }
            else {
              val allKeys = if (fields.isEmpty)
                results.flatMap(_._2.properties.keys).distinct.sorted
              else fields
              println("# file\t" + allKeys.mkString("\t"))
              results.foreach { case (p, fp) =>
                val relPath = relativize(root, p)
                val vals = allKeys.map(k => fp.properties.getOrElse(k, ""))
                println(relPath + "\t" + vals.mkString("\t"))
              }
            }
          }.as(ExitCode.Success)
        }
      } else {
        stream.compile.toList.flatMap { results =>
          IO {
            if (results.isEmpty) { println("(no results)") }
            else {
              val allKeys = if (fields.isEmpty)
                results.flatMap(_._2.properties.keys).distinct.sorted
              else fields
              results.foreach { case (p, fp) =>
                println(relativize(root, p))
                allKeys.foreach { k =>
                  fp.properties.get(k).foreach(v => println(f"  $k%-30s $v"))
                }
                println()
              }
              println(s"${results.size} file(s) matched")
            }
          }.as(ExitCode.Success)
        }
      }
    }

  // -- set ---------------------------------------------------------------

  private def runSet(
    givenPath: String,
    pred:      Option[Predicate.Expr],
    values:    Map[String, String],
    mr:        Boolean,
    dryRun:    Boolean
  ): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      resolveGiven(givenPath, root)
        .evalMap(p => FileHeader.parse(p).map(fp => (p, fp)))
        .filter { case (_, fp) => matchesPredicate(fp, pred) }
        .evalMap { case (p, _) =>
          if (dryRun)
            IO(Term.info(s"would update: ${relativize(root, p)}")).as(1)
          else
            FileHeaderApply.setProperties(p.toNioPath.toFile, values).as(1)
        }
        .compile
        .foldMonoid
        .flatMap { count =>
          val verb = if (dryRun) "would update" else "updated"
          IO(Term.ok(s"$verb $count file(s)")).as(ExitCode.Success)
        }
    }

  // -- sync-check --------------------------------------------------------

  private def runSyncCheck(
    givenPath: String,
    pred:      Option[Predicate.Expr],
    mr:        Boolean
  ): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      SyncCheck.check(root, resolveGiven(givenPath, root), pred, mr)
    }

  // -- track-commit ------------------------------------------------------

  private def runTrackCommit(
    givenPath: String,
    pred:      Option[Predicate.Expr],
    mr:        Boolean,
    dryRun:    Boolean
  ): IO[ExitCode] =
    Paths.projectRoot.flatMap { root =>
      CommitTracker.run(root, resolveGiven(givenPath, root), pred, mr, dryRun)
    }

  // -- path resolution ---------------------------------------------------

  private def resolveGiven(givenPath: String, root: File): Stream[IO, Path] = {
    if (givenPath == ".") {
      FileOps.streamFilesAcross(Paths.scanTargets(root), ".scala")
    } else {
      val resolved = {
        val f = new File(givenPath)
        if (f.isAbsolute) f else new File(root, givenPath)
      }
      if (resolved.isFile) {
        Stream.emit(Path.fromNioPath(resolved.toPath))
      } else if (resolved.isDirectory) {
        FileOps.streamFiles(resolved, ".scala")
      } else if (givenPath.contains("*")) {
        val base = root.toPath
        val matcher = java.nio.file.FileSystems.getDefault
          .getPathMatcher(s"glob:$givenPath")
        Files[IO]
          .walk(Path.fromNioPath(base))
          .filter(p => p.fileName.toString.endsWith(".scala"))
          .filter(p => matcher.matches(base.relativize(p.toNioPath)))
      } else {
        Stream.raiseError[IO](
          new RuntimeException(s"Cannot resolve --given '$givenPath': not a file, directory, or glob")
        )
      }
    }
  }

  // -- helpers -----------------------------------------------------------

  private def matchesPredicate(
    fp:   FileHeader.FileProperties,
    pred: Option[Predicate.Expr]
  ): Boolean =
    pred.forall(Predicate.evaluate(_, fp.properties))

  private def relativize(root: File, path: Path): String = {
    val rootPath = root.toPath.toAbsolutePath
    val filePath = path.toNioPath.toAbsolutePath
    if (filePath.startsWith(rootPath))
      rootPath.relativize(filePath).toString
    else filePath.toString
  }

  private val usage: String =
    """Usage: re-scale fileinfo --given <path|dir|glob> [--when <predicate>] --then <action>
      |
      |Actions:
      |  select *                          Print all header properties
      |  select field1,field2              Print selected fields
      |  set key1=val1,key2=val2           Batch-update header fields
      |  sync-check                        Verify DB matches file headers
      |  track-commit                      Record upstream commit hash
      |
      |Predicates (--when):
      |  field=value                       Equality
      |  field!=value                      Inequality
      |  field contains value              Substring or list membership
      |  field starts-with value           Prefix match
      |  field ends-with value             Suffix match
      |  expr && expr                      Logical AND
      |  expr || expr                      Logical OR
      |  !expr                             Negation
      |  (expr)                            Grouping
      |
      |Options:
      |  --machine-readable                Output as TSV
      |  --dry-run                         Show what would change (for set/track-commit)""".stripMargin
}
