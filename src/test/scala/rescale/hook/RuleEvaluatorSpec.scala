/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests the rule evaluator's matching logic and decision composition.
 * Uses small synthetic rule sets so each test focuses on one operator
 * or one composition rule. DefaultRulesSpec covers the canonical rule
 * set end-to-end.
 */
package rescale.hook

import munit.FunSuite
import rescale.hook.Rule.*
import rescale.hook.Rule.Condition as C

final class RuleEvaluatorSpec extends FunSuite {

  private def eval(cmdLine: String, rules: RuleSet): Decision =
    RuleEvaluator.evaluate(BashParser.parse(cmdLine), rules)

  // -- Condition primitives ------------------------------------------

  test("True condition matches anything") {
    val rs = RuleSet(List(RuleEntry(C.True, Some(Decision.Deny("nope")))))
    assertEquals(eval("anything goes", rs), Decision.Deny("nope"))
  }

  test("False condition matches nothing → fall through to allow") {
    val rs = RuleSet(List(RuleEntry(C.False, Some(Decision.Deny("never")))))
    assertEquals(eval("anything", rs), Decision.Allow)
  }

  test("StartsWith matches command + args prefix") {
    val rs = RuleSet(List(
      RuleEntry(C.StartsWith(List("git", "push")), Some(Decision.Deny("no push")))
    ))
    assertEquals(eval("git push origin main", rs), Decision.Deny("no push"))
    assertEquals(eval("git status", rs), Decision.Allow)
  }

  test("StartsWith does NOT match a partial program name") {
    val rs = RuleSet(List(
      RuleEntry(C.StartsWith(List("git")), Some(Decision.Deny("no git")))
    ))
    assertEquals(eval("github", rs), Decision.Allow)
  }

  test("HasAny matches if any value is in command line") {
    val rs = RuleSet(List(
      RuleEntry(C.HasAny(List("--force", "-f")), Some(Decision.Deny("forceful")))
    ))
    assertEquals(eval("git push --force origin", rs), Decision.Deny("forceful"))
    assertEquals(eval("git push -f origin", rs), Decision.Deny("forceful"))
    assertEquals(eval("git push origin", rs), Decision.Allow)
  }

  test("HasAll requires every value present") {
    val rs = RuleSet(List(
      RuleEntry(C.HasAll(List("--rules", "lint")), Some(Decision.Deny("wat")))
    ))
    assertEquals(eval("scalafix --rules lint", rs), Decision.Deny("wat"))
    assertEquals(eval("scalafix --rules", rs), Decision.Allow)
  }

  test("HasRedirect matches a specific operator") {
    val rs = RuleSet(List(
      RuleEntry(C.HasRedirect("2>&1"), Some(Decision.Deny("no err merge")))
    ))
    assertEquals(eval("cmd 2>&1", rs), Decision.Deny("no err merge"))
    assertEquals(eval("cmd > out.txt", rs), Decision.Allow)
  }

  test("ProgramIn matches normalized program name") {
    val rs = RuleSet(List(
      RuleEntry(C.ProgramIn(List("rm")), Some(Decision.Deny("dangerous")))
    ))
    assertEquals(eval("rm foo", rs), Decision.Deny("dangerous"))
    assertEquals(eval("/usr/bin/rm foo", rs), Decision.Deny("dangerous"))
    assertEquals(eval("rmdir foo", rs), Decision.Allow)
  }

  // -- Boolean composition --------------------------------------------

  test("And requires all sub-conditions") {
    val rs = RuleSet(List(
      RuleEntry(
        C.And(List(C.ProgramIn(List("git")), C.HasAny(List("--force")))),
        Some(Decision.Deny("force push"))
      )
    ))
    assertEquals(eval("git push --force", rs), Decision.Deny("force push"))
    assertEquals(eval("git push", rs), Decision.Allow)
    assertEquals(eval("npm publish --force", rs), Decision.Allow)
  }

  test("Or matches if any sub-condition matches") {
    val rs = RuleSet(List(
      RuleEntry(
        C.Or(List(C.ProgramIn(List("rm")), C.ProgramIn(List("rmdir")))),
        Some(Decision.Deny("destructive"))
      )
    ))
    assertEquals(eval("rm foo", rs), Decision.Deny("destructive"))
    assertEquals(eval("rmdir foo", rs), Decision.Deny("destructive"))
    assertEquals(eval("ls foo", rs), Decision.Allow)
  }

  test("Not negates a condition") {
    val rs = RuleSet(List(
      RuleEntry(
        C.And(List(C.ProgramIn(List("git")), C.Not(C.HasAny(List("--dry-run"))))),
        Some(Decision.Deny("real action"))
      )
    ))
    assertEquals(eval("git push", rs), Decision.Deny("real action"))
    assertEquals(eval("git push --dry-run", rs), Decision.Allow)
  }

  // -- First-match-wins ----------------------------------------------

  test("Rules are evaluated top-down; first match wins") {
    val rs = RuleSet(List(
      RuleEntry(C.StartsWith(List("git", "push")), Some(Decision.Deny("first"))),
      RuleEntry(C.ProgramIn(List("git")), Some(Decision.Allow))
    ))
    assertEquals(eval("git push", rs), Decision.Deny("first"))
    assertEquals(eval("git status", rs), Decision.Allow)
  }

  // -- Nested rules ---------------------------------------------------

  test("Nested rules: outer when gates inner; inner deny wins") {
    val rs = RuleSet(List(
      RuleEntry(
        when     = C.ProgramIn(List("gh")),
        action   = Some(Decision.Allow),
        nested = List(
          RuleEntry(C.StartsWith(List("gh", "pr", "create")), Some(Decision.Deny("no PR create")))
        )
      )
    ))
    assertEquals(eval("gh pr list", rs), Decision.Allow)
    assertEquals(eval("gh pr create --title foo", rs), Decision.Deny("no PR create"))
  }

  test("Nested rules: outer fallback applies when no inner matches") {
    val rs = RuleSet(List(
      RuleEntry(
        when     = C.ProgramIn(List("gh")),
        action   = None,
        nested = List(
          RuleEntry(C.StartsWith(List("gh", "pr", "create")), Some(Decision.Deny("no")))
        ),
        fallback = Some(Decision.Allow)
      )
    ))
    assertEquals(eval("gh pr list", rs), Decision.Allow)
    assertEquals(eval("gh pr create", rs), Decision.Deny("no"))
  }

  // -- Composition across pipelines / chains -------------------------

  test("compose: deny wins over allow in a pipeline") {
    val rs = RuleSet(List(
      RuleEntry(C.ProgramIn(List("rm")), Some(Decision.Deny("rm bad"))),
      RuleEntry(C.True, Some(Decision.Allow))
    ))
    assertEquals(eval("ls foo | rm /tmp/bar", rs), Decision.Deny("rm bad"))
  }

  test("compose: ask wins over allow when no deny") {
    val rs = RuleSet(List(
      RuleEntry(C.ProgramIn(List("gh")), Some(Decision.Ask("confirm gh"))),
      RuleEntry(C.True, Some(Decision.Allow))
    ))
    assertEquals(eval("ls && gh pr list", rs), Decision.Ask("confirm gh"))
  }

  test("compose: allow if every command allows") {
    val rs = RuleSet(List(
      RuleEntry(C.ProgramIn(List("ls", "echo", "cat")), Some(Decision.Allow))
    ))
    assertEquals(eval("ls && echo done", rs), Decision.Allow)
  }

  test("compose: deny + ask = deny (deny wins)") {
    val rs = RuleSet(List(
      RuleEntry(C.ProgramIn(List("rm")), Some(Decision.Deny("rm bad"))),
      RuleEntry(C.ProgramIn(List("gh")), Some(Decision.Ask("confirm gh"))),
      RuleEntry(C.True, Some(Decision.Allow))
    ))
    assertEquals(eval("rm foo && gh pr list", rs), Decision.Deny("rm bad"))
  }

  // -- FollowedBy / PrecededBy ---------------------------------------

  test("FollowedBy fires when the next command matches") {
    val rs = RuleSet(List(
      RuleEntry(
        C.And(List(
          C.ProgramIn(List("re-scale")),
          C.FollowedBy(C.ProgramIn(List("head", "tail", "wc")))
        )),
        Some(Decision.Deny("don't pipe re-scale to head/tail/wc"))
      )
    ))
    assertEquals(eval("re-scale db audit list | head", rs), Decision.Deny("don't pipe re-scale to head/tail/wc"))
    assertEquals(eval("re-scale db audit list", rs), Decision.Allow)
    assertEquals(eval("ls | head", rs), Decision.Allow)
  }

  test("PrecededBy mirrors FollowedBy from the other side") {
    val rs = RuleSet(List(
      RuleEntry(
        C.And(List(
          C.ProgramIn(List("head")),
          C.PrecededBy(C.ProgramIn(List("re-scale")))
        )),
        Some(Decision.Deny("don't head re-scale output"))
      )
    ))
    assertEquals(eval("re-scale db audit list | head", rs), Decision.Deny("don't head re-scale output"))
  }

  // -- Empty / unparseable inputs ------------------------------------

  test("empty input → Allow") {
    assertEquals(eval("", DefaultRules.ruleSet), Decision.Allow)
  }
}
