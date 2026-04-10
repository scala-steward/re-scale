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
import rescale.hook.Rule.Decision

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

  // All process responses are wrapped in `hookSpecificOutput` per the
  // Claude Code PreToolUse contract — re-scale's earlier `{}` for allow
  // was silently ignored by the harness.

  test("process: empty command → wrapped allow") {
    val r = HookCmd.process("""{"command":""}""", DefaultRules.ruleSet)
    assert(r.contains("\"hookSpecificOutput\""), s"got: $r")
    assert(r.contains("\"permissionDecision\":\"allow\""), s"got: $r")
  }

  test("process: non-Bash event → wrapped allow") {
    val r = HookCmd.process("""{"tool_name":"Read","tool_input":{"file_path":"x"}}""", DefaultRules.ruleSet)
    assert(r.contains("\"hookSpecificOutput\""), s"got: $r")
    assert(r.contains("\"permissionDecision\":\"allow\""), s"got: $r")
  }

  test("process: ls denied (suboptimal-tool rule)") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"ls /tmp"}}"""
    val r    = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"deny\""), s"got: $r")
  }

  test("process: re-scale call → allow") {
    val json = """{"tool_name":"Bash","tool_input":{"command":"re-scale db audit list"}}"""
    val r    = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"allow\""), s"got: $r")
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

  test("process: giant multi-line commit message via heredoc is bounded-time") {
    // Reproducer for the 48 GB incident observed on
    //   re-scale git commit --m "$(cat <<'EOF' <5 KB of message> EOF)"
    // Claude Code serializes the whole shell command (including the
    // heredoc body) into `tool_input.command`. The hook then has to
    // extract that string, parse it through BashParser, and match it
    // against every DefaultRules entry.
    //
    // This test builds a realistic payload of the same shape and
    // asserts the whole pipeline completes without exploding. munit's
    // per-test timeout catches quadratic-or-worse regressions; a
    // bounded-memory run finishes in milliseconds.
    val body = (1 to 200)
      .map(i => s"line $i: some commit message text with (parens) and :pseudo-classes")
      .mkString("\\n")
    val escapedCommand =
      "re-scale git commit --m \\\"$(cat <<'EOF'\\n" + body + "\\nEOF\\n)\\\""
    val json = s"""{"tool_name":"Bash","tool_input":{"command":"$escapedCommand","description":"commit"}}"""
    val r = HookCmd.process(json, DefaultRules.ruleSet)
    // The exact decision doesn't matter for this regression — what
    // matters is that the parser + evaluator complete at all.
    assert(
      r.contains("\"hookSpecificOutput\""),
      s"hook produced no wrapped response: $r"
    )
  }

  test("process: Read on .rescale/data/ path → deny") {
    val json = """{"tool_name":"Read","tool_input":{"file_path":"/foo/bar/.rescale/data/audit.tsv"}}"""
    val r = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"deny\""), s"expected deny, got: $r")
    assert(r.contains("re-scale db"), s"expected db hint, got: $r")
  }

  test("process: Read on normal file → allow") {
    val json = """{"tool_name":"Read","tool_input":{"file_path":"/foo/bar/src/Main.scala"}}"""
    val r = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"allow\""), s"expected allow, got: $r")
  }

  test("process: Edit on .rescale/data/ path → deny") {
    val json = """{"tool_name":"Edit","tool_input":{"file_path":"/foo/.rescale/data/issues.tsv","old_string":"a","new_string":"b"}}"""
    val r = HookCmd.process(json, DefaultRules.ruleSet)
    assert(r.contains("\"permissionDecision\":\"deny\""), s"expected deny, got: $r")
  }

  test("process: every response is wrapped in hookSpecificOutput") {
    val cases = List(
      """{"tool_name":"Bash","tool_input":{"command":"git status"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"rm -rf /tmp"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"gh pr create"}}""",
      """{"tool_name":"Read","tool_input":{"file_path":"x"}}"""
    )
    cases.foreach { json =>
      val r = HookCmd.process(json, DefaultRules.ruleSet)
      assert(r.startsWith("""{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"""),
        s"unwrapped output for $json: $r")
    }
  }

  // -- renderDecision -----------------------------------------------

  test("renderDecision: Allow → wrapped allow with empty reason") {
    val out = HookCmd.renderDecision(Decision.Allow)
    assert(out.contains("\"permissionDecision\":\"allow\""), s"got: $out")
    assert(out.contains("\"permissionDecisionReason\":\"\""), s"got: $out")
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
