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

    val out = scala.collection.mutable.ListBuffer.empty[Map[String, String]]
    out ++= target.rows
    var nextId = maxTakenId + 1

    for (srcRow <- source.rows) {
      val srcId = srcRow.getOrElse("id", "")
      if (srcId.isEmpty || !srcId.startsWith("ISS-")) {
        out += srcRow
      } else if (!targetIds.contains(srcId)) {
        // No collision — keep the source row as-is.
        out += srcRow
      } else {
        // Collision. Resolve per strategy.
        strategy match {
          case Strategy.PreferLeft =>
            // Drop the source row entirely.
            ()
          case Strategy.PreferRight =>
            // Replace the target row with the source row.
            val idx = out.indexWhere(_.getOrElse("id", "") == srcId)
            if (idx >= 0) out.update(idx, srcRow)
          case Strategy.Renumber =>
            val newId = f"ISS-$nextId%03d"
            nextId += 1
            val oldDesc = srcRow.getOrElse("description", "")
            val renumbered = srcRow ++ Map(
              "id"          -> newId,
              "description" -> s"[was $srcId] $oldDesc"
            )
            out += renumbered
          case Strategy.Stricter =>
            // For issues, "stricter" doesn't have an obvious meaning.
            // Fall back to renumber.
            val newId = f"ISS-$nextId%03d"
            nextId += 1
            out += (srcRow.updated("id", newId))
        }
      }
    }

    target.copy(rows = out.toList.sortBy(keyFn))
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
    Tsv.read(source).unsafeRunSync()(global)
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
