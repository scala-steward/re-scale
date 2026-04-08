/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale hook` — PreToolUse validator. Reads a JSON event from
 * stdin, dispatches Bash tool calls through the BashParser +
 * RuleEvaluator, and emits a JSON response per the Claude Code hook
 * contract.
 *
 * Hook contract (PreToolUse):
 *   Input  (stdin):
 *     { "tool_name": "Bash", "tool_input": { "command": "...", "description": "..." } }
 *
 *   Output (stdout) — wrapped in `hookSpecificOutput` per the Claude
 *   Code spec:
 *     {
 *       "hookSpecificOutput": {
 *         "hookEventName": "PreToolUse",
 *         "permissionDecision": "allow|ask|deny",
 *         "permissionDecisionReason": "..."
 *       }
 *     }
 *
 * Non-Bash tools are passed through as `allow`. Empty / unparseable
 * input is also `allow` — we don't want to block on a malformed event.
 *
 * Parity-tested against the legacy ssg-dev hook output format so this
 * is a drop-in replacement for `.claude/hooks/pre-tool-use.sh`.
 */
package rescale.hook

import cats.effect.{ExitCode, IO}
import rescale.hook.Rule.*

object HookCmd {

  def run(args: List[String]): IO[ExitCode] = {
    args match {
      case "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case _ =>
        for {
          stdin    <- readStdin
          response  = process(stdin, DefaultRules.ruleSet)
          _        <- IO.println(response)
        } yield ExitCode.Success
    }
  }

  /** Process a single PreToolUse event JSON string and return the
    * response JSON. Pure function — no IO. Lets tests cover the full
    * decision pipeline without spawning subprocesses.
    */
  def process(input: String, rules: RuleSet): String = {
    val command = extractBashCommand(input)
    command match {
      case None =>
        // Not a Bash tool call, or input is malformed — let the harness allow.
        responseJson("allow", "")
      case Some(cmd) if cmd.trim.isEmpty =>
        responseJson("allow", "")
      case Some(cmd) =>
        val expr     = BashParser.parse(cmd)
        val decision = RuleEvaluator.evaluate(expr, rules)
        renderDecision(decision)
    }
  }

  /** Minimal-effort JSON extraction. We don't pull in a JSON parser
    * for this — the only field we care about is
    * `tool_input.command`, which the Claude Code hook contract
    * guarantees is a top-level string. A full JSON parser is overkill
    * and would add a Scala Native dependency for one field lookup.
    *
    * Looks for `"command"\s*:\s*"..."` and unescapes the standard
    * JSON string escapes. Returns None if no command field is found.
    */
  private[hook] def extractBashCommand(input: String): Option[String] = {
    if (input.isEmpty) return None
    // Quick reject: not a Bash tool call.
    if (!input.contains("\"command\"")) return None

    // Find "command" : "..." with proper escape handling.
    val key = "\"command\""
    var i   = input.indexOf(key)
    while (i >= 0) {
      var j = i + key.length
      // Skip whitespace and colon
      while (j < input.length && (input(j) == ' ' || input(j) == '\t' || input(j) == ':')) j += 1
      if (j < input.length && input(j) == '"') {
        // Found the opening quote — read the string body
        j += 1
        val sb = new StringBuilder
        var done = false
        while (j < input.length && !done) {
          input(j) match {
            case '\\' if j + 1 < input.length =>
              input(j + 1) match {
                case 'n'  => sb.append('\n'); j += 2
                case 't'  => sb.append('\t'); j += 2
                case 'r'  => sb.append('\r'); j += 2
                case '"'  => sb.append('"');  j += 2
                case '\\' => sb.append('\\'); j += 2
                case '/'  => sb.append('/');  j += 2
                case c    => sb.append(c);    j += 2
              }
            case '"' =>
              done = true
            case c =>
              sb.append(c); j += 1
          }
        }
        if (done) return Some(sb.toString)
      }
      i = input.indexOf(key, i + 1)
    }
    None
  }

  /** Render a Decision as the Claude Code hook response JSON. */
  private[hook] def renderDecision(decision: Decision): String = decision match {
    case Decision.Allow      => responseJson("allow", "")
    case Decision.Pass(r)    => responseJson("allow", r)
    case Decision.Ask(r)     => responseJson("ask", r)
    case Decision.Deny(r)    => responseJson("deny", r)
  }

  /** Build the wrapped Claude Code hook response. The wrapping under
    * `hookSpecificOutput` with `hookEventName: PreToolUse` is the
    * exact shape Claude Code expects from a PreToolUse hook — without
    * it, the decision is silently ignored.
    */
  private def responseJson(decision: String, reason: String): String = {
    val escapedReason = reason
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s"""{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"$decision","permissionDecisionReason":"$escapedReason"}}"""
  }

  /** Read all of stdin into a String. Bounded by the harness — Claude
    * Code never sends more than a few KB to a hook.
    */
  private val readStdin: IO[String] = IO.blocking {
    val sb  = new StringBuilder
    val buf = new Array[Byte](4096)
    var n   = 0
    var done = false
    while (!done) {
      n = System.in.read(buf)
      if (n < 0) done = true
      else sb.append(new String(buf, 0, n, "UTF-8"))
    }
    sb.toString
  }

  private val usage: String =
    """re-scale hook — PreToolUse validator
      |
      |Reads a Claude Code hook event from stdin, validates Bash tool
      |calls against the configured rule set, and emits a JSON response.
      |
      |Use from .claude/hooks/pre-tool-use.sh:
      |
      |  #!/bin/bash
      |  exec re-scale hook
      |
      |Per-repo overrides: .claude-hook.yaml at the project root
      |(coming in a follow-up commit).""".stripMargin
}
