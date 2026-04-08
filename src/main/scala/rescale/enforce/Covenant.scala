/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Covenant header parser + verifier (Phase 4 of re-scale plan).
 *
 * A covenanted file carries a header block of the form:
 *
 *   /*
 *    * ... standard license header ...
 *    *
 *    * Covenant: full-port
 *    * Covenant-baseline-spec-pass: 4157
 *    * Covenant-baseline-loc: 1153
 *    * Covenant-baseline-methods: name1,name2,name3,...
 *    * Covenant-source-reference: lib/src/environment.dart
 *    * Covenant-verified: 2026-04-08
 *    */
 *
 * `verify` re-extracts the file's current method set and compares it
 * against `baseline-methods`. A removed method is a hard fail (catches
 * silent reverts). Also re-runs `Shortcuts.scanFile` — any hit in a
 * covenanted file is a hard fail.
 *
 * Backward compat: BOTH `Covenant-source-reference` (worktree) and
 * `Covenant-dart-reference` (main repo's sass-port branch) parse into
 * the same `sourceReference` field. New writes use `source-reference`.
 */
package rescale.enforce

import cats.effect.IO
import fs2.io.file.Path
import rescale.common.FileOps

import java.io.File

object Covenant {

  final case class Header(
    covenant:         String, // "full-port", "partial-port", etc.
    baselineSpecPass: Int,
    baselineLoc:      Int,
    baselineMethods:  Set[String],
    sourceReference:  String,
    verified:         String
  )

  /** Streaming header parse. Reads at most 80 lines from the file's
    * top before giving up. Holds zero file content beyond the in-flight
    * line.
    */
  def parse(path: Path): IO[Option[Header]] = {
    val initial: ParseAcc = ParseAcc(None, None, None, None, None, None)
    FileOps
      .streamLines(path)
      .take(80)
      .compile
      .fold(initial) { case (acc, (_, line)) =>
        val t = stripCommentMarkers(line)
        if (t.startsWith("Covenant:"))
          acc.copy(covenant = Some(t.substring("Covenant:".length).trim))
        else if (t.startsWith("Covenant-baseline-spec-pass:"))
          acc.copy(baseSpecPass = t.substring("Covenant-baseline-spec-pass:".length).trim.toIntOption)
        else if (t.startsWith("Covenant-baseline-loc:"))
          acc.copy(baseLoc = t.substring("Covenant-baseline-loc:".length).trim.toIntOption)
        else if (t.startsWith("Covenant-baseline-methods:"))
          acc.copy(baseMethods =
            Some(
              t.substring("Covenant-baseline-methods:".length).trim
                .split(",").map(_.trim).filter(_.nonEmpty).toSet
            )
          )
        else if (t.startsWith("Covenant-source-reference:"))
          acc.copy(sourceRef = Some(t.substring("Covenant-source-reference:".length).trim))
        else if (t.startsWith("Covenant-dart-reference:"))
          // backward-compat with ssg-sass-only headers from the main repo
          acc.copy(sourceRef = Some(t.substring("Covenant-dart-reference:".length).trim))
        else if (t.startsWith("Covenant-verified:"))
          acc.copy(verified = Some(t.substring("Covenant-verified:".length).trim))
        else acc
      }
      .map { acc =>
        acc.covenant.map { c =>
          Header(
            covenant         = c,
            baselineSpecPass = acc.baseSpecPass.getOrElse(0),
            baselineLoc      = acc.baseLoc.getOrElse(0),
            baselineMethods  = acc.baseMethods.getOrElse(Set.empty),
            sourceReference  = acc.sourceRef.getOrElse(""),
            verified         = acc.verified.getOrElse("")
          )
        }
      }
  }

  def parse(file: File): IO[Option[Header]] = parse(Path.fromNioPath(file.toPath))

  /** Verify a covenanted file. Returns Right(()) on pass, Left(reason)
    * on fail. Fails on:
    *   - No covenant header present.
    *   - Current method set is missing names from `baseline-methods`.
    *   - `Shortcuts.scanFile` returns any hit.
    *
    * Only `full-port` covenants are enforced; other covenant tags
    * (e.g. `partial-port`) are accepted as-is.
    */
  def verify(path: Path): IO[Either[String, Unit]] = {
    parse(path).flatMap {
      case None =>
        IO.pure(Left("no covenant header"))
      case Some(header) if header.covenant != "full-port" =>
        IO.pure(Right(()))
      case Some(header) =>
        for {
          currentMethods <- Methods.extractScalaNames(path)
          missing         = header.baselineMethods -- currentMethods.toSet
          shortcutHits   <- Shortcuts.scanFile(path)
        } yield {
          if (missing.nonEmpty)
            Left(s"methods removed since baseline: ${missing.toList.sorted.take(5).mkString(", ")}")
          else if (shortcutHits.nonEmpty)
            Left(s"shortcuts introduced: ${shortcutHits.size} hit(s), e.g. ${shortcutHits.head.pattern} at line ${shortcutHits.head.line}")
          else
            Right(())
        }
    }
  }

  def verify(file: File): IO[Either[String, Unit]] = verify(Path.fromNioPath(file.toPath))

  // -- internals -------------------------------------------------------

  private final case class ParseAcc(
    covenant:     Option[String],
    baseSpecPass: Option[Int],
    baseLoc:      Option[Int],
    baseMethods:  Option[Set[String]],
    sourceRef:    Option[String],
    verified:     Option[String]
  )

  // Strip leading `*`, opening block-comment, `//` and surrounding
  // whitespace from a comment line so the field-name match works
  // regardless of comment style.
  private def stripCommentMarkers(line: String): String = {
    val open = "/" + "*"
    line.trim.stripPrefix("*").stripPrefix(open).stripPrefix("//").trim
  }
}
