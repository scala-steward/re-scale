/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Skip-policy database — Phase 4 of the re-scale plan.
 *
 * The skip-policy TSV records files (or directories) that the
 * enforcement tools should NOT flag, with a justification per row.
 * This is the escape hatch for legitimate exceptions: vendored code,
 * generated files, intentionally-stub fixtures, etc. The TSV format
 * makes the policy auditable in git history.
 *
 * Schema (`scripts/data/skip-policy.tsv`):
 *   path           — relative file path or directory glob (e.g. `ssg-md/foo.scala`
 *                    or `ssg-sass/generated/`)
 *   tool           — which enforcement tool the skip applies to
 *                    (`shortcuts`, `stale-stubs`, `methods`, `verify`, or `*`)
 *   reason         — human-readable justification
 *   added          — ISO date (e.g. `2026-04-08`)
 *   added_by       — name of the person who added the entry
 */
package rescale.enforce

import cats.effect.IO
import rescale.common.Tsv
import rescale.common.Tsv.Table

import java.io.File

object SkipPolicy {

  val headers: List[String] = List("path", "tool", "reason", "added", "added_by")

  final case class Entry(
    path:    String,
    tool:    String,
    reason:  String,
    added:   String,
    addedBy: String
  ) {
    /** Match this entry against a (file, tool) pair. The path field
      * matches if the file path starts with it (so a directory entry
      * covers everything underneath) OR equals it (file entry).
      * The tool field matches if it equals `tool` or `*`.
      */
    def matches(filePath: String, toolName: String): Boolean = {
      val toolOk = tool == "*" || tool == toolName
      if (!toolOk) return false
      // Directory entries end with /
      if (path.endsWith("/")) filePath.startsWith(path)
      else filePath == path || filePath.endsWith("/" + path)
    }
  }

  /** Read the skip-policy file. Returns an empty list if the file
    * doesn't exist.
    */
  def read(file: File): IO[List[Entry]] = {
    IO(file.exists()).flatMap {
      case false => IO.pure(Nil)
      case true =>
        Tsv.read(file).map { tbl =>
          tbl.rows.map { r =>
            Entry(
              path    = r.getOrElse("path", ""),
              tool    = r.getOrElse("tool", "*"),
              reason  = r.getOrElse("reason", ""),
              added   = r.getOrElse("added", ""),
              addedBy = r.getOrElse("added_by", "")
            )
          }
        }
    }
  }

  /** Filter a list of (file path, hit) pairs to drop entries covered
    * by the skip policy for the given tool name.
    */
  def filter[A](policy: List[Entry], toolName: String, filePath: A => String, hits: List[A]): List[A] = {
    if (policy.isEmpty) hits
    else hits.filterNot(h => policy.exists(_.matches(filePath(h), toolName)))
  }

  /** Append an entry to the skip-policy file via Tsv.modify (atomic +
    * locked).
    */
  def add(file: File, entry: Entry): IO[Unit] = {
    Tsv.modify(file) { current =>
      val tbl = if (current.headers.isEmpty)
        Table(headers, Nil, List("# re-scale skip-policy"))
      else current
      tbl.addRow(
        Map(
          "path"     -> entry.path,
          "tool"     -> entry.tool,
          "reason"   -> entry.reason,
          "added"    -> entry.added,
          "added_by" -> entry.addedBy
        )
      )
    }.void
  }
}
