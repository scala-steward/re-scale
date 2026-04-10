/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale enforce covenant-apply` — stamp or update Covenant headers.
 *
 * Reads a Scala file, extracts its current method set + LOC, optionally
 * verifies zero shortcut hits, and writes (or updates) the Covenant
 * header block in the file's leading block comment.
 *
 * Used for retroactive covenant application: once a file passes all
 * enforcement gates (shortcuts, stale-stubs, method parity), it earns
 * a Covenant header that locks in the baseline. The CI gate
 * (`enforce verify --all`) then prevents silent regressions.
 */
package rescale.enforce

import cats.effect.IO
import fs2.io.file.Path
import rescale.common.FileOps

import java.io.{File, PrintWriter}
import java.time.LocalDate

object CovenantApply {

  final case class Result(
    path:    String,
    methods: List[String],
    loc:     Int,
    action:  String // "created", "updated", "dry-run", "refused"
  )

  /** Apply or update a Covenant header on a single file.
    *
    * @param file            the Scala file to stamp
    * @param sourceReference the `Covenant-source-reference` value
    *                        (e.g. `lib/src/environment.dart`)
    * @param specPass        optional `Covenant-baseline-spec-pass` value
    * @param covenant        covenant level (default `full-port`)
    * @param dryRun          if true, report what would happen without writing
    * @param force           if true, skip the shortcut pre-check
    */
  def apply(
    file:            File,
    sourceReference: String,
    specPass:        Option[Int]  = None,
    covenant:        String       = "full-port",
    dryRun:          Boolean      = false,
    force:           Boolean      = false
  ): IO[Result] = {
    val path = Path.fromNioPath(file.toPath)
    for {
      // Extract current method set + LOC
      methods <- Methods.extractScalaNames(path)
      lines   <- FileOps.readAllLines(path)
      loc      = lines.size

      // Pre-check: refuse to covenant a file with shortcut hits
      // unless --force is set.
      shortcutHits <- if (force) IO.pure(Nil) else Shortcuts.scanFile(path)
      result <- {
        if (shortcutHits.nonEmpty) {
          IO.pure(Result(
            file.getPath, methods, loc,
            s"refused — ${shortcutHits.size} shortcut hit(s): ${shortcutHits.take(3).map(h => s"${h.pattern}@${h.line}").mkString(", ")}"
          ))
        } else if (dryRun) {
          IO.pure(Result(file.getPath, methods, loc, "dry-run"))
        } else {
          writeHeader(file, lines, methods, loc, sourceReference, specPass, covenant).map { action =>
            Result(file.getPath, methods, loc, action)
          }
        }
      }
    } yield result
  }

  /** Write or update the Covenant header in the file. Returns "created"
    * or "updated" depending on whether fields already existed.
    */
  private def writeHeader(
    file:            File,
    lines:           Vector[String],
    methods:         List[String],
    loc:             Int,
    sourceReference: String,
    specPass:        Option[Int],
    covenant:        String
  ): IO[String] = IO.blocking {
    val today      = LocalDate.now().toString
    val methodsCsv = methods.sorted.mkString(",")

    val covenantLines = List(
      s" * Covenant: $covenant",
      s" * Covenant-baseline-spec-pass: ${specPass.getOrElse(0)}",
      s" * Covenant-baseline-loc: $loc",
      s" * Covenant-baseline-methods: $methodsCsv",
      s" * Covenant-source-reference: $sourceReference",
      s" * Covenant-verified: $today"
    )

    // Find the header comment block boundaries.
    val headerStart = lines.indexWhere(_.trim.startsWith("/*"))
    val headerEnd   = if (headerStart >= 0) {
      lines.indexWhere(l => l.trim == "*/" || l.trim.endsWith("*/"), headerStart)
    } else -1

    if (headerStart < 0 || headerEnd < 0) {
      throw new RuntimeException(
        s"No block comment found in ${file.getPath} — covenant-apply requires a leading /* ... */ comment"
      )
    }

    // Check for existing Covenant lines within the header.
    val existingRange = (headerStart to headerEnd).filter { i =>
      val t = lines(i).trim.stripPrefix("*").trim
      t.startsWith("Covenant")
    }

    val newLines: Vector[String] = if (existingRange.nonEmpty) {
      // UPDATE: replace the existing Covenant block with new values.
      val firstCov = existingRange.head
      val lastCov  = existingRange.last
      lines.take(firstCov) ++ covenantLines ++ lines.drop(lastCov + 1)
    } else {
      // CREATE: insert Covenant lines before the closing `*/`.
      // Add a blank ` *` separator if the line before `*/` isn't already blank.
      val needsSeparator = {
        val prevLine = if (headerEnd > 0) lines(headerEnd - 1).trim else ""
        prevLine.nonEmpty && prevLine != "*"
      }
      val separator = if (needsSeparator) Vector(" *") else Vector.empty
      lines.take(headerEnd) ++ separator ++ covenantLines ++ lines.drop(headerEnd)
    }

    val w = new PrintWriter(file)
    try {
      newLines.foreach(w.println)
    } finally w.close()

    if (existingRange.nonEmpty) "updated" else "created"
  }
}
