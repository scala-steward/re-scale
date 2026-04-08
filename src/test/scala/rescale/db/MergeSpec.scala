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

  // -- Cross-reference rewriting --------------------------------------

  test("issues merge: cross-reference renaming — description references the renumbered ID") {
    // Source has ISS-002 (collides) and ISS-003 (no collision).
    // ISS-003's description references ISS-002. After merge,
    // ISS-002 → ISS-005; ISS-003's description must be rewritten
    // to point to ISS-005, not the now-stale ISS-002.
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "first"),
      ("ISS-002", "b.scala", "second"),
      ("ISS-003", "c.scala", "third"),
      ("ISS-004", "d.scala", "fourth")
    ))
    val right = issuesTable(List(
      ("ISS-002", "shared.scala", "right's ISS-002 — collides"),
      ("ISS-003", "right3.scala", "right's ISS-003 — collides"),
      ("ISS-005", "ref.scala",    "duplicate of ISS-002 (depends on ISS-003)")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.Renumber)

    // Both colliding source rows get renumbered: ISS-002 → ISS-006, ISS-003 → ISS-007
    // (max(targetIds ∪ keptSourceIds) = 5 because right's ISS-005 is kept).
    // The non-colliding ISS-005 from right stays at ISS-005 — but its description
    // references ISS-002 + ISS-003, which must be rewritten.

    val iss5 = merged.rows.find(_.get("id").contains("ISS-005"))
      .getOrElse(fail("expected ISS-005 to survive merge"))
    val desc = iss5.getOrElse("description", "")
    assert(!desc.contains("ISS-002"), s"ISS-005 description still references stale ISS-002: $desc")
    assert(!desc.contains("ISS-003"), s"ISS-005 description still references stale ISS-003: $desc")
    assert(desc.contains("ISS-006"), s"ISS-005 description should reference renamed ISS-006: $desc")
    assert(desc.contains("ISS-007"), s"ISS-005 description should reference renamed ISS-007: $desc")
  }

  test("issues merge: cross-reference rewrite preserves [was X] audit trail") {
    // The renumbered row's description gets a [was ISS-002] prefix.
    // The literal "ISS-002" in that prefix is the AUDIT TRAIL — it
    // must NOT be rewritten by the cross-ref pass. Otherwise the
    // [was ...] tag would point at the new ID and the original ID
    // would be lost.
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "first"),
      ("ISS-002", "b.scala", "second")
    ))
    val right = issuesTable(List(
      ("ISS-002", "shared.scala", "collides")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.Renumber)
    val renumbered = merged.rows.find(_.get("id").contains("ISS-003"))
      .getOrElse(fail("expected ISS-003 (renumbered ISS-002) to exist"))
    val desc = renumbered.getOrElse("description", "")
    assert(desc.startsWith("[was ISS-002]"),
      s"audit trail should preserve original ID literally: $desc")
  }

  test("issues merge: target ISS-X reference stays, source ISS-X reference rewrites") {
    // The classic dual-branch scenario:
    //
    //   Branch 1 (target): ISS-056 + ISS-066 ("references ISS-056")
    //   Branch 2 (source): ISS-056 + ISS-077 ("references ISS-056")
    //
    // After merge:
    //   - Target's ISS-056 stays at ISS-056.
    //   - Target's ISS-066 stays at ISS-066. Its description still
    //     reads "references ISS-056" — pointing at TARGET's ISS-056,
    //     which is still ISS-056. UNCHANGED.
    //   - Source's ISS-056 collides → renamed (e.g. ISS-078, since
    //     max(target ∪ kept-source) = 77 from source's ISS-077).
    //   - Source's ISS-077 doesn't collide → stays at ISS-077. But
    //     its description text "references ISS-056" was about
    //     SOURCE's ISS-056, so it must be rewritten to point at the
    //     renamed value (ISS-078).
    //
    // Net effect: ISS-066 and ISS-077 BOTH have descriptions that
    // mention an "ISS-056"-shaped value, but they point at DIFFERENT
    // rows (target's vs source's renamed).
    val target = issuesTable(List(
      ("ISS-056", "feature.scala", "the original feature issue"),
      ("ISS-066", "blocker.scala", "blocked by ISS-056")
    ))
    val source = issuesTable(List(
      ("ISS-056", "feature.scala", "branch B's view of the same feature"),
      ("ISS-077", "blocker2.scala", "also blocked by ISS-056")
    ))
    val merged = Merge.merge(Kind.Issues, target, source, Strategy.Renumber)

    // Find the rows.
    val targetIss66 = merged.rows.find(r =>
      r.get("id").contains("ISS-066") && r.get("file_path").contains("blocker.scala")
    ).getOrElse(fail("expected target ISS-066 to survive"))

    val sourceIss77 = merged.rows.find(r =>
      r.get("id").contains("ISS-077") && r.get("file_path").contains("blocker2.scala")
    ).getOrElse(fail("expected source ISS-077 to survive"))

    // Target ISS-066's description was NEVER rewritten — still says ISS-056
    // (correctly pointing at target's still-ISS-056).
    assertEquals(
      targetIss66.getOrElse("description", ""),
      "blocked by ISS-056",
      "target rows must not be rewritten — their references already point at target IDs"
    )

    // Source ISS-077's description WAS rewritten — its original text
    // "ISS-056" referred to SOURCE's ISS-056, which got renumbered.
    val sourceDesc = sourceIss77.getOrElse("description", "")
    assert(!sourceDesc.contains("ISS-056"),
      s"source ISS-077 description should no longer mention stale ISS-056: $sourceDesc")
    // The renamed source ISS-056 is the FIRST collision, so it gets
    // max(target ∪ kept-source) + 1 = max(56, 66, 77) + 1 = 78. But
    // ISS-077 also exists in the kept-source set, so max-taken = 77,
    // first allocation = ISS-078.
    assert(sourceDesc.contains("ISS-078"),
      s"source ISS-077 description should reference renamed ISS-078: $sourceDesc")
  }

  test("issues merge: chained renames don't double-rewrite") {
    // Renames map: ISS-002 → ISS-005, ISS-005 → ISS-006.
    // A naive `for ((old, new) <- renames) text.replace(old, new)`
    // would turn ISS-002 → ISS-005 → ISS-006 (double-rewrite).
    // The Regex.replaceAllIn implementation walks the string ONCE,
    // so each match is decided before the next is examined.
    //
    // To trigger this, we need source rows where one references both
    // an ID that maps to the rename target of another. Construct:
    //   left  = ISS-001..004   (target IDs)
    //   right = ISS-002 (collides → 005), ISS-005 (collides → 006),
    //           plus a row whose description says "ISS-002 ISS-005".
    val left = issuesTable(List(
      ("ISS-001", "a.scala", "first"),
      ("ISS-002", "b.scala", "second"),
      ("ISS-005", "e.scala", "fifth")
    ))
    val right = issuesTable(List(
      ("ISS-002", "right2.scala", "right's ISS-002 collides"),
      ("ISS-005", "right5.scala", "right's ISS-005 collides"),
      ("ISS-100", "ref.scala",    "references ISS-002 and ISS-005")
    ))
    val merged = Merge.merge(Kind.Issues, left, right, Strategy.Renumber)
    val ref = merged.rows.find(_.get("id").contains("ISS-100"))
      .getOrElse(fail("expected ISS-100 to survive merge"))
    val desc = ref.getOrElse("description", "")
    // Renames: max(left ∪ kept) = 100 (right's ISS-100 is kept), so
    // colliders go to ISS-101 and ISS-102.
    // After single-pass rewrite: "references ISS-101 and ISS-102".
    assert(!desc.contains("ISS-002"), s"stale ISS-002 left in: $desc")
    assert(!desc.contains("ISS-005"), s"stale ISS-005 left in: $desc")
    assert(desc.contains("ISS-101"), s"expected rewritten ISS-101 in: $desc")
    assert(desc.contains("ISS-102"), s"expected rewritten ISS-102 in: $desc")
    // The double-rewrite bug would have produced ISS-102/ISS-102 or
    // some chain artifact. Confirm we have the two distinct values.
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
