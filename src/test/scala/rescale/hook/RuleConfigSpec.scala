/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the per-project hook YAML loader
 * (.rescale/claude-hooks.yaml). Covers:
 *   - basic action / reason / when shapes
 *   - the full Condition catalog (and/or/not + each leaf type)
 *   - merge ordering: per-project rules win over defaults
 *   - malformed YAML produces a Left error, not a thrown exception
 *   - missing config file falls back to DefaultRules
 */
package rescale.hook

import munit.FunSuite
import rescale.hook.Rule.{Decision, RuleSet}

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class RuleConfigSpec extends FunSuite {

  private def writeYaml(content: String): File = {
    val f = NIOFiles.createTempFile("re-scale-hooks-", ".yaml").toFile
    f.deleteOnExit()
    val w = new PrintWriter(f)
    try w.write(content)
    finally w.close()
    f
  }

  private def decideAgainst(rs: RuleSet, command: String): Decision =
    RuleEvaluator.evaluate(BashParser.parse(command), rs)

  // -- Basic shapes --------------------------------------------------

  test("simple deny rule: starts-with") {
    val yaml =
      """rules:
        |  - when:
        |      starts-with: [adb]
        |    action: deny
        |    reason: "Use 're-scale test android' instead"
        |""".stripMargin
    val parsed = RuleConfig.parse(yaml)
    val rs = parsed match {
      case Right(rs)  => rs
      case Left(err)  => fail(s"parse error: $err")
    }
    decideAgainst(rs, "adb shell ls") match {
      case Decision.Deny(r) => assert(r.contains("test android"), s"reason='$r'")
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("ask action") {
    val yaml =
      """rules:
        |  - when:
        |      starts-with: [terraform, apply]
        |    action: ask
        |    reason: "Confirm before applying infra"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "terraform apply -auto-approve") match {
      case Decision.Ask(_) => // ok
      case other           => fail(s"expected Ask, got $other")
    }
  }

  // -- Condition catalog --------------------------------------------

  test("and: composes multiple conditions") {
    val yaml =
      """rules:
        |  - when:
        |      and:
        |        - starts-with: [git, push]
        |        - has-any: ["--no-verify"]
        |    action: deny
        |    reason: "Don't bypass git hooks"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "git push --no-verify origin main") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
    // Without --no-verify, the rule shouldn't fire — falls through to defaults
    // which allow `git push`.
    decideAgainst(rs, "git push origin main") match {
      case Decision.Allow => // ok (default git push allow)
      case other          => fail(s"expected Allow, got $other")
    }
  }

  test("or: any sub-condition triggers") {
    val yaml =
      """rules:
        |  - when:
        |      or:
        |        - program-in: [foo]
        |        - program-in: [bar]
        |    action: deny
        |    reason: "neither foo nor bar"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "foo --x") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny on foo, got $other")
    }
    decideAgainst(rs, "bar --y") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny on bar, got $other")
    }
  }

  test("not: inverts a sub-condition") {
    val yaml =
      """rules:
        |  - when:
        |      and:
        |        - program-in: [sbt]
        |        - not:
        |            has-any: ["--client"]
        |    action: deny
        |    reason: "Use sbt --client"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "sbt compile") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("has-any-suffix: filename extension match") {
    val yaml =
      """rules:
        |  - when:
        |      and:
        |        - program-in: [curl]
        |        - has-any-suffix: [".tar.zst"]
        |    action: ask
        |    reason: "Downloading a zstd archive"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "curl https://repo/foo.tar.zst -o foo.tar.zst") match {
      case Decision.Ask(_) => // ok
      case other           => fail(s"expected Ask, got $other")
    }
  }

  test("has-any-contains: substring match") {
    val yaml =
      """rules:
        |  - when:
        |      has-any-contains: ["DELETE FROM"]
        |    action: deny
        |    reason: "Refusing SQL DELETE"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "psql -c \"DELETE FROM users\"") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("has-redirect-target-prefix: write under a forbidden dir") {
    val yaml =
      """rules:
        |  - when:
        |      has-redirect-target-prefix: ["/private/"]
        |    action: deny
        |    reason: "no writes under /private/"
        |""".stripMargin
    val rs = RuleConfig.parse(yaml).toOption.get
    decideAgainst(rs, "echo foo > /private/bar") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
  }

  // -- Merge ordering -----------------------------------------------

  test("merge: per-project rules win over defaults (first-match)") {
    // The default rule set DENIES `ls` (suboptimal-tools rule). This
    // override should re-allow `ls -la /tmp` for this project only.
    val yaml =
      """rules:
        |  - when:
        |      and:
        |        - program-in: [ls]
        |        - has-any: ["-la"]
        |    action: allow
        |    reason: "ls -la is fine in this project"
        |""".stripMargin
    val merged = RuleConfig.parse(yaml).toOption.flatMap { overrides =>
      Some(RuleSet(overrides.rules ++ DefaultRules.ruleSet.rules))
    }.get
    decideAgainst(merged, "ls -la /tmp") match {
      case Decision.Allow   => // ok — override fired before default deny
      case Decision.Pass(r) =>
        // `action: allow` with a non-empty reason is mapped to Pass
        // (which still permission-decides as `allow` in the wrapped JSON
        // but preserves the user-provided reason).
        assert(r.contains("fine in this project"), s"reason='$r'")
      case other            => fail(s"expected Allow/Pass from override, got $other")
    }
    // Plain `ls` (no -la) should still hit the default deny.
    decideAgainst(merged, "ls") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected default Deny on bare ls, got $other")
    }
  }

  // -- Error handling ----------------------------------------------

  test("missing file → returns defaults") {
    val ghost = new File("/tmp/does-not-exist-rescale-hooks.yaml")
    val rs = RuleConfig.loadAndMerge(ghost).toOption.get
    assertEquals(rs, DefaultRules.ruleSet)
  }

  test("malformed YAML → Left error") {
    val f = writeYaml("rules:\n  - when: [unbalanced")
    val r = RuleConfig.loadAndMerge(f)
    assert(r.isLeft, s"expected Left, got $r")
  }

  test("empty when: → Left error") {
    val yaml =
      """rules:
        |  - when: {}
        |    action: deny
        |    reason: "nope"
        |""".stripMargin
    val r = RuleConfig.parse(yaml)
    assert(r.isLeft, s"expected Left for empty when, got $r")
  }

  test("multiple when: branches → Left error") {
    val yaml =
      """rules:
        |  - when:
        |      starts-with: [git]
        |      has-any: [push]
        |    action: deny
        |    reason: "ambiguous"
        |""".stripMargin
    val r = RuleConfig.parse(yaml)
    assert(r.isLeft, s"expected Left for multi-branch when, got $r")
  }

  test("unknown action → Left error") {
    val yaml =
      """rules:
        |  - when:
        |      starts-with: [foo]
        |    action: kill
        |    reason: "wat"
        |""".stripMargin
    val r = RuleConfig.parse(yaml)
    assert(r.isLeft, s"expected Left for unknown action, got $r")
  }
}
