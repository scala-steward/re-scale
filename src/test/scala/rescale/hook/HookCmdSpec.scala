/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests the JSON-in / JSON-out plumbing of `re-scale hook`. The
 * decision logic is fully covered by RuleEvaluatorSpec and
 * DefaultRulesSpec; this file just verifies the JSON wrapper.
 */
package rescale.hook

import munit.FunSuite
import rescale.hook.Rule.{Decision, RuleSet}

final class HookCmdSpec extends FunSuite {

  // -- extractBashCommand --------------------------------------------

  test("extractBashCommand returns None for empty input") {
    assertEquals(HookCmd.extractBashCommand(""), None)
  }

  test("extractBashCommand returns None when no command field") {
    assertEquals(HookCmd.extractBashCommand("""{"tool_name":"Read","tool_input":{"file_path":"x"}}"""), None)
  }

  test("extractBashCommand finds a simple command field") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"ls -la"}}"""
    assertEquals(HookCmd.extractBashCommand(json), Some("ls -la"))
  }

  test("extractBashCommand handles escaped quotes") {
    val json = """{"command":"echo \"hello\""}"""
    assertEquals(HookCmd.extractBashCommand(json), Some("""echo "hello""""))
  }

  test("extractBashCommand handles escaped backslashes") {
    val json = """{"command":"echo a\\b"}"""
    assertEquals(HookCmd.extractBashCommand(json), Some("""echo a\b"""))
  }

  test("extractBashCommand handles \\n inside string") {
    val json = """{"command":"line1\nline2"}"""
    assertEquals(HookCmd.extractBashCommand(json), Some("line1\nline2"))
  }

  // -- process ------------------------------------------------------

  test("process: empty command → empty response (no decision)") {
    assertEquals(HookCmd.process("""{"command":""}""", DefaultRules.ruleSet), "{}")
  }

  test("process: non-Bash event → empty response") {
    assertEquals(HookCmd.process("""{"tool_name":"Read","tool_input":{"file_path":"x"}}""", DefaultRules.ruleSet), "{}")
  }

  test("process: allowed command → empty response") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"ls /tmp"}}"""
    // ls is denied (suboptimal tools), so should NOT be empty.
    val r = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"deny\""), s"got: $r")
  }

  test("process: re-scale call → empty (allow)") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"re-scale db audit list"}}"""
    assertEquals(HookCmd.process(json, DefaultRules.ruleSet), "{}")
  }

  test("process: rm -rf → deny JSON") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"rm -rf /tmp/foo"}}"""
    val r    = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"deny\""), s"got: $r")
    assert(r.contains("\"permissionDecisionReason\""), s"got: $r")
  }

  test("process: gh pr create → ask JSON") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"gh pr create --title foo"}}"""
    val r    = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"ask\""), s"got: $r")
  }

  // -- renderDecision -----------------------------------------------

  test("renderDecision: Allow → empty") {
    assertEquals(HookCmd.renderDecision(Decision.Allow), "{}")
  }

  test("renderDecision: Deny escapes the reason") {
    val out = HookCmd.renderDecision(Decision.Deny("contains \"quotes\" and \\backslash"))
    assert(out.contains("\\\"quotes\\\""), s"got: $out")
    assert(out.contains("\\\\backslash"), s"got: $out")
  }

  test("renderDecision: newlines in reason are escaped") {
    val out = HookCmd.renderDecision(Decision.Ask("line1\nline2"))
    assert(out.contains("line1\\nline2"), s"got: $out")
  }
}
