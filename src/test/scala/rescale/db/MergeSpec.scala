/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for re-scale db merge — the cross-branch reconciliation
 * tool. Includes the exact ID-collision scenario the user hit during
 * the gap-audit campaign (elegant worktree added ISS-002..025,
 * flickering added ISS-002..012, both starting at 2).
 */
package rescale.db

import munit.FunSuite
import rescale.common.Tsv.Table
import rescale.db.Merge.{Kind, Strategy}

final class MergeSpec extends FunSuite {

  // -- helpers --------------------------------------------------------

  private def issuesTable(rows: List[(String, String, String)]): Table = {
    val headers = List("id", "file_path", "description")
    val mapped = rows.map { case (id, path, desc) =>
      Map("id" -> id, "file_path" -> path, "description" -> desc)
    }
    Table(headers, mapped)
  }

  private def auditTable(rows: List[(String, String, String)]): Table = {
    val headers = List("file_path", "status", "notes")
    val mapped = rows.map { case (path, status, notes) =>
      Map("file_path" -> path, "status" -> status, "notes" -> notes)
    }
    Table(headers, mapped)
  }

  // -- Issues: ID renumbering ----------------------------------------

  test("issues merge: disjoint sets just concatenate") {
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "first"),
      ("ISS-002", "b.scala", "second")
    ))
    val right = issuesTable(List(
      ("ISS-003", "c.scala", "third")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.Renumber)
    val ids    = merged.rows.flatMap(_.get("id"))
    assertEquals(ids.toSet, Set("ISS-001", "ISS-002", "ISS-003"))
  }

  test("issues merge: ID collision triggers renumbering") {
    val left = issuesTable(List(
      ("ISS-001", "shared.scala", "shared issue"),
      ("ISS-002", "left.scala",   "left only"),
      ("ISS-003", "left2.scala",  "left only 2")
    ))
    val right = issuesTable(List(
      ("ISS-001", "shared.scala",  "different content but same id"),
      ("ISS-002", "right.scala",   "right only"),
      ("ISS-004", "right2.scala",  "right unique id")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.Renumber)
    val ids    = merged.rows.flatMap(_.get("id"))

    // Left's ISS-001..003 untouched. ISS-004 from right is new (no collision).
    // Right's ISS-001 and ISS-002 collide → renumbered to ISS-005, ISS-006.
    assertEquals(ids.size, 6)
    assert(ids.contains("ISS-001"))
    assert(ids.contains("ISS-002"))
    assert(ids.contains("ISS-003"))
    assert(ids.contains("ISS-004"))
    assert(ids.contains("ISS-005"))
    assert(ids.contains("ISS-006"))

    // Renumbered rows record the original ID in the description.
    val renumbered = merged.rows.filter(r => r.get("id").contains("ISS-005") || r.get("id").contains("ISS-006"))
    assertEquals(renumbered.size, 2)
    assert(renumbered.forall(_.get("description").exists(_.startsWith("[was ISS-"))))
  }

  test("issues merge: real-world scenario from gap-audit campaign") {
    // The exact situation: elegant added ISS-002..025 (24 issues),
    // flickering added ISS-002..012 (11 issues). Renumbering should
    // preserve all 35 entries, no collisions.
    val elegant = issuesTable(
      ("ISS-001", "ssg-md/ArrayUtils.scala", "preexisting") ::
        (2 to 25).map(i => (f"ISS-$i%03d", s"ssg-liquid/file$i.scala", s"liquid issue $i")).toList
    )
    val flickering = issuesTable(
      ("ISS-001", "ssg-md/ArrayUtils.scala", "preexisting") ::
        (2 to 12).map(i => (f"ISS-$i%03d", s"ssg-js/file$i.scala", s"js issue $i")).toList
    )

    val merged = Merge.merge(Kind.Issues, elegant, flickering, Strategy.Renumber)
    val ids    = merged.rows.flatMap(_.get("id")).toSet

    // ISS-001 is shared (same content) — collision is renumbered too.
    // Total: 25 (elegant) + 1 (preexisting from flickering, renumbered) + 11 (flickering renumbered) = 37
    // OR if we treat ISS-001 as identical and dedupe... no, our merge always renumbers on collision.
    // Both ISS-001s collide → 25 from left + 12 from right = 37 unique IDs
    assertEquals(merged.rows.size, 37, s"expected 37 rows; got ${merged.rows.size}")
    assertEquals(ids.size, 37, "all IDs must be unique after renumbering")

    // 25 elegant rows occupy ISS-001..ISS-025; all 12 flickering rows
    // collide and renumber to ISS-026..ISS-037, so the highest is 37.
    val maxId = ids.flatMap(s => s.stripPrefix("ISS-").toIntOption).max
    assertEquals(maxId, 37)
  }

  test("issues merge: prefer-left drops source on conflict") {
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "left wins")
    ))
    val right = issuesTable(List(
      ("ISS-001", "a.scala", "right loses"),
      ("ISS-002", "b.scala", "right unique")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.PreferLeft)
    val descs  = merged.rows.flatMap(_.get("description")).toSet
    assert(descs.contains("left wins"))
    assert(!descs.contains("right loses"), "prefer-left should drop colliding source row")
    assert(descs.contains("right unique"), "non-colliding source row should be kept")
  }

  test("issues merge: prefer-right replaces target on conflict") {
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "old")
    ))
    val right = issuesTable(List(
      ("ISS-001", "a.scala", "new")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.PreferRight)
    assertEquals(merged.rows.head.get("description"), Some("new"))
  }

  // -- Audit: stricter status -----------------------------------------

  test("audit merge: stricter picks max(major > minor > pass)") {
    val left  = auditTable(List(
      ("a.scala", "pass",         "left note"),
      ("b.scala", "minor_issues", "left note b")
    ))
    val right = auditTable(List(
      ("a.scala", "minor_issues", "right note"),
      ("b.scala", "major_issues", "right note b")
    ))
    val merged = Merge.merge(Kind.Audit, left, right, Strategy.Stricter)
    val byPath = merged.rows.map(r => r.getOrElse("file_path", "") -> r).toMap

    // a.scala: pass (left) vs minor_issues (right) → minor wins
    assertEquals(byPath("a.scala").get("status"), Some("minor_issues"))
    assertEquals(byPath("a.scala").get("notes"), Some("right note"))

    // b.scala: minor (left) vs major (right) → major wins
    assertEquals(byPath("b.scala").get("status"), Some("major_issues"))
    assertEquals(byPath("b.scala").get("notes"), Some("right note b"))
  }

  test("audit merge: rows only in source are added") {
    val left  = auditTable(List(("a.scala", "pass", "")))
    val right = auditTable(List(("b.scala", "pass", "right only")))
    val merged = Merge.merge(Kind.Audit, left, right, Strategy.Stricter)
    val paths = merged.rows.flatMap(_.get("file_path")).toSet
    assertEquals(paths, Set("a.scala", "b.scala"))
  }

  test("audit merge: prefer-left ignores source notes") {
    val left  = auditTable(List(("a.scala", "pass", "left wins")))
    val right = auditTable(List(("a.scala", "minor_issues", "right loses")))
    val merged = Merge.merge(Kind.Audit, left, right, Strategy.PreferLeft)
    assertEquals(merged.rows.head.get("status"), Some("pass"))
    assertEquals(merged.rows.head.get("notes"), Some("left wins"))
  }

  // -- Migration: additive ----------------------------------------

  test("migration merge: additive on disjoint source_path") {
    val left = Table(
      headers = List("source_path", "status"),
      rows    = List(Map("source_path" -> "a.java", "status" -> "ported"))
    )
    val right = Table(
      headers = List("source_path", "status"),
      rows    = List(Map("source_path" -> "b.java", "status" -> "skipped"))
    )
    val merged = Merge.merge(Kind.Migration, left, right, Strategy.PreferLeft)
    assertEquals(merged.rows.size, 2)
  }
}
