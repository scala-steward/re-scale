/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * NEW FEATURE — cross-branch TSV reconciliation.
 *
 * The user hit this exact bug during the gap-audit campaign: two
 * worktrees independently added rows to issues.tsv starting at
 * ISS-002, and the result was an ID-collision space where ISS-002
 * meant different things in different branches. Manually renumbering
 * 24 + 11 issues took an hour.
 *
 * `re-scale db merge` automates this reconciliation:
 *   - issues.tsv: ID-aware merge that renumbers conflicting ISS-NNN
 *     to preserve all entries from both sides
 *   - audit.tsv: row-key-aware merge (file_path is the natural key);
 *     conflicts pick the stricter status or report
 *   - migration.tsv: additive merge (source_path is the key)
 *
 * Strategies:
 *   prefer-left   — on conflict, keep the target side and discard source
 *   prefer-right  — on conflict, replace target with source
 *   renumber      — for issues.tsv: keep both, renumber the source side
 *   stricter      — for audit.tsv: pick max(status) where major>minor>pass
 */
package rescale.db

import cats.effect.{ExitCode, IO}
import rescale.common.{Cli, Term, Tsv}
import rescale.common.Tsv.Table

import java.io.File

object Merge {

  enum Strategy {
    case PreferLeft
    case PreferRight
    case Renumber
    case Stricter
  }

  enum Kind {
    case Issues
    case Audit
    case Migration
  }

  def run(args: List[String]): IO[ExitCode] = {
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case _ =>
        val parsed = Cli.parse(args)
        val target = parsed.flag("target")
        val source = parsed.flag("source")
        val strategyName = parsed.flagOrDefault("strategy", "renumber")

        (target, source) match {
          case (Some(t), Some(s)) =>
            val strategy = parseStrategy(strategyName).getOrElse(Strategy.Renumber)
            mergeFiles(new File(t), new File(s), strategy)
          case _ =>
            IO(Term.err("Usage: re-scale db merge --target <tsv> --source <tsv> [--strategy ...]"))
              .as(ExitCode.Error)
        }
    }
  }

  def mergeFiles(target: File, source: File, strategy: Strategy): IO[ExitCode] = {
    if (!source.exists()) {
      return IO(Term.err(s"Source file does not exist: $source")).as(ExitCode.Error)
    }
    val kind = inferKind(target.getName)
    for {
      _ <- IO.blocking {
             if (!target.exists()) target.createNewFile(): Unit
           }
      _ <- Tsv.modify(target) { current =>
             // Read source inside the lock too — read it inline since we
             // already have the file lock on target. The source itself
             // doesn't need locking; we're just reading.
             val src = readSourceUnlocked(source)
             merge(kind, current, src, strategy)
           }
      _ <- IO(Term.ok(s"Merged $source → $target (strategy=${strategy})"))
    } yield ExitCode.Success
  }

  // -- merge core ------------------------------------------------------

  /** Pure-function merge of two tables. Strategy + kind determine the
    * key and conflict resolution. Comments and headers come from the
    * target side; rows are reconciled.
    */
  def merge(kind: Kind, target: Table, source: Table, strategy: Strategy): Table = {
    val keyFn: Map[String, String] => String = kind match {
      case Kind.Issues    => _.getOrElse("id", "")
      case Kind.Audit     => _.getOrElse("file_path", "")
      case Kind.Migration => _.getOrElse("source_path", "")
    }

    kind match {
      case Kind.Issues =>
        mergeIssues(target, source, strategy, keyFn)
      case Kind.Audit =>
        mergeByKey(target, source, strategy, keyFn, auditConflict)
      case Kind.Migration =>
        mergeByKey(target, source, strategy, keyFn, defaultConflict)
    }
  }

  /** Generic key-based merge. */
  private def mergeByKey(
    target:   Table,
    source:   Table,
    strategy: Strategy,
    keyFn:    Map[String, String] => String,
    conflict: (Map[String, String], Map[String, String]) => Map[String, String]
  ): Table = {
    val targetByKey = target.rows.map(r => keyFn(r) -> r).toMap
    val sourceByKey = source.rows.map(r => keyFn(r) -> r).toMap

    val keysOnlyInSource = sourceByKey.keySet -- targetByKey.keySet
    val keysOnlyInTarget = targetByKey.keySet -- sourceByKey.keySet
    val keysInBoth       = sourceByKey.keySet intersect targetByKey.keySet

    val merged: List[Map[String, String]] = (
      keysInBoth.toList.map { k =>
        val l = targetByKey(k)
        val r = sourceByKey(k)
        strategy match {
          case Strategy.PreferLeft  => l
          case Strategy.PreferRight => r
          case Strategy.Renumber    => l // ID-renumber doesn't apply to non-issues
          case Strategy.Stricter    => conflict(l, r)
        }
      } ++
        keysOnlyInTarget.toList.map(targetByKey) ++
        keysOnlyInSource.toList.map(sourceByKey)
    )

    target.copy(rows = merged.sortBy(keyFn))
  }

  /** Issues merge: ID collisions trigger renumbering when strategy
    * is Renumber. Source rows that collide get fresh IDs allocated
    * past the highest existing target ID; their old IDs are recorded
    * in `description` so the trail is preserved.
    */
  private def mergeIssues(
    target:   Table,
    source:   Table,
    strategy: Strategy,
    keyFn:    Map[String, String] => String
  ): Table = {
    val targetIds = target.rows.flatMap(_.get("id")).filter(_.startsWith("ISS-")).toSet
    // Source rows that don't collide get kept at their original IDs.
    // We need their numeric IDs in the "already-taken" set so renumbering
    // won't reallocate over them.
    val keptSourceIds = source.rows
      .flatMap(_.get("id"))
      .filter(id => id.startsWith("ISS-") && !targetIds.contains(id))
      .toSet
    val allTakenIds = targetIds ++ keptSourceIds
    val maxTakenId = allTakenIds
      .flatMap(_.stripPrefix("ISS-").toIntOption)
      .maxOption
      .getOrElse(0)

    // -- Pass 1: dry-run the renumbering to build the rename map ----
    //
    // For Renumber and Stricter strategies we need a Map[oldId, newId]
    // BEFORE we start emitting rows so the Pass-2 cross-reference
    // rewriter can rewrite descriptions/notes/etc. that mention an
    // old ID. PreferLeft drops colliding source rows entirely (no
    // renumber); PreferRight overwrites target rows in place (also no
    // renumber). Both leave the rename map empty, so Pass 2 is a
    // no-op rewrite.
    val renames = scala.collection.mutable.LinkedHashMap.empty[String, String]
    if (strategy == Strategy.Renumber || strategy == Strategy.Stricter) {
      var nextId = maxTakenId + 1
      for (srcRow <- source.rows) {
        val srcId = srcRow.getOrElse("id", "")
        if (srcId.startsWith("ISS-") && targetIds.contains(srcId)) {
          renames(srcId) = f"ISS-$nextId%03d"
          nextId += 1
        }
      }
    }
    val renamesMap = renames.toMap

    // -- Pass 2: apply the merge, rewriting cross-references --------
    //
    // Target rows are NOT rewritten — only source IDs get renumbered,
    // so any reference inside a TARGET row's text column to "ISS-002"
    // is to the TARGET's own ISS-002, which still exists at ISS-002.
    // Only source rows can carry stale references after renumbering.
    val out = scala.collection.mutable.ListBuffer.empty[Map[String, String]]
    out ++= target.rows

    for (srcRow <- source.rows) {
      val srcId = srcRow.getOrElse("id", "")
      if (srcId.isEmpty || !srcId.startsWith("ISS-")) {
        out += rewriteCrossRefs(srcRow, renamesMap)
      } else if (!targetIds.contains(srcId)) {
        // No collision — keep at original ID, but still rewrite any
        // cross-references in case this row mentions another source
        // row that DID get renumbered.
        out += rewriteCrossRefs(srcRow, renamesMap)
      } else {
        // Collision. Resolve per strategy.
        strategy match {
          case Strategy.PreferLeft =>
            // Drop the source row entirely.
            ()
          case Strategy.PreferRight =>
            // Replace the target row with the source row. Rewrite the
            // source row's text columns first so it carries no stale
            // references.
            val idx = out.indexWhere(_.getOrElse("id", "") == srcId)
            if (idx >= 0) out.update(idx, rewriteCrossRefs(srcRow, renamesMap))
          case Strategy.Renumber | Strategy.Stricter =>
            val newId    = renamesMap(srcId)
            // Rewrite cross-refs in non-id text columns first…
            val rewritten = rewriteCrossRefs(srcRow, renamesMap)
            // …then build the [was $srcId] audit trail with the LITERAL
            // old id, NOT from the rewritten text (rewriteCrossRefs
            // would otherwise have turned `[was ISS-002]` into
            // `[was ISS-026]` and lost the audit trail).
            val oldDesc   = srcRow.getOrElse("description", "")
            val rewrittenOldDesc =
              if (renamesMap.isEmpty) oldDesc
              else rewriteString(oldDesc, renamesMap)
            val annotated = rewritten ++ Map(
              "id"          -> newId,
              "description" -> s"[was $srcId] $rewrittenOldDesc"
            )
            out += annotated
        }
      }
    }

    target.copy(rows = out.toList.sortBy(keyFn))
  }

  // -- Cross-reference rewriting --------------------------------------

  /** Pattern that matches `ISS-NNN` IDs (3+ digits) anywhere in a
    * string. Used by `rewriteString` to substitute old → new in a
    * single pass.
    */
  private val IssueIdPattern: scala.util.matching.Regex = """ISS-\d{3,}""".r

  /** Rewrite every ISS-NNN reference in `value` according to `renames`,
    * in a SINGLE walk over the string.
    *
    * We can't use `Regex.replaceAllIn(text, m => fn(m))` here because
    * it transitively requires `java.util.regex.Matcher.appendReplacement`
    * + `appendTail`, neither of which is implemented in Scala Native's
    * regex stdlib (yet). Instead we use `findAllMatchIn` (which IS
    * supported) and manually splice the surviving + replacement chunks.
    *
    * The "single walk" property is important: a fold of `text.replace`
    * over the renames map has a chain bug — if renames = {ISS-002 →
    * ISS-026, ISS-026 → ISS-027}, the first replace turns ISS-002 →
    * ISS-026, the second replace then turns that ISS-026 → ISS-027
    * (wrong). The findAllMatchIn approach decides each match BEFORE
    * the next is examined, so chains can't form.
    */
  private[db] def rewriteString(value: String, renames: Map[String, String]): String = {
    if (renames.isEmpty) return value
    val matches = IssueIdPattern.findAllMatchIn(value).toList
    if (matches.isEmpty) return value
    // We use String.substring + StringBuilder.append(String) instead of
    // the Java-style `append(CharSequence, start, end)` overload because
    // scala.collection.mutable.StringBuilder's `append(Any, Any, Any)`
    // overload tuples the three args (you'd get the literal text
    // "(value,cursor,start)" instead of the substring).
    val sb = new StringBuilder
    var cursor = 0
    for (m <- matches) {
      if (m.start > cursor) sb.append(value.substring(cursor, m.start))
      sb.append(renames.getOrElse(m.matched, m.matched))
      cursor = m.end
    }
    if (cursor < value.length) sb.append(value.substring(cursor))
    sb.toString
  }

  /** Rewrite every text column in a row, leaving the `id` column
    * untouched (the id column is set authoritatively by the merge
    * loop, not via reference substitution).
    */
  private[db] def rewriteCrossRefs(row: Map[String, String], renames: Map[String, String]): Map[String, String] = {
    if (renames.isEmpty) row
    else
      row.map {
        case ("id", v) => "id" -> v
        case (k, v)    => k -> rewriteString(v, renames)
      }
  }

  /** Audit conflict resolver — picks the stricter status. */
  private def auditConflict(left: Map[String, String], right: Map[String, String]): Map[String, String] = {
    val leftStatus  = left.getOrElse("status", "pass")
    val rightStatus = right.getOrElse("status", "pass")
    val rank: String => Int = {
      case "major_issues" => 3
      case "minor_issues" => 2
      case "pass"         => 1
      case _              => 0
    }
    if (rank(rightStatus) > rank(leftStatus)) right else left
  }

  /** Default conflict resolver — keep the left side. */
  private def defaultConflict(left: Map[String, String], right: Map[String, String]): Map[String, String] =
    left

  /** Read a source file synchronously. Used inside Tsv.modify so it
    * runs while we already hold the target lock. Source is read-only;
    * no lock needed for it.
    */
  private def readSourceUnlocked(source: File): Table = {
    import cats.effect.unsafe.IORuntime.global
    Tsv.read(source).unsafeRunSync()(using global)
  }

  // -- name → kind inference -------------------------------------------

  private def inferKind(filename: String): Kind = {
    val n = filename.toLowerCase
    if (n.contains("issues")) Kind.Issues
    else if (n.contains("audit")) Kind.Audit
    else if (n.contains("migration")) Kind.Migration
    else Kind.Audit
  }

  private def parseStrategy(s: String): Option[Strategy] = s.toLowerCase match {
    case "prefer-left"  => Some(Strategy.PreferLeft)
    case "prefer-right" => Some(Strategy.PreferRight)
    case "renumber"     => Some(Strategy.Renumber)
    case "stricter"     => Some(Strategy.Stricter)
    case _              => None
  }

  private val usage: String =
    """Usage: re-scale db merge --target <tsv> --source <tsv> [--strategy STRATEGY]
      |
      |Reconciles two TSVs from different branches. The kind is inferred
      |from the filename (issues / audit / migration). Strategies:
      |  renumber     For issues.tsv: keep both, renumber colliding source IDs (default)
      |  prefer-left  Keep target on conflict, discard source
      |  prefer-right Replace target with source on conflict
      |  stricter     For audit.tsv: pick max(major > minor > pass) on conflict""".stripMargin
}
