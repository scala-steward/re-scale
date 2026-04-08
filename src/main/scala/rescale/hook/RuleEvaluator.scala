/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Evaluates a [[Rule.RuleSet]] against a parsed [[BashParser.BashExpr]]
 * and returns a single composed [[Rule.Decision]].
 *
 * Composition algorithm:
 *   1. Flatten the expression to its leaf Simple commands via
 *      BashParser.allCommands.
 *   2. For each leaf command, evaluate the rule set top-down.
 *      First matching rule wins. If none matches → Allow.
 *   3. Compose the per-command decisions: pick the maximum-strength
 *      decision (Deny > Ask > Pass > Allow). On ties, the first
 *      occurrence wins so the reason is the strongest one users see.
 *   4. Layer on the structural checks:
 *      - HasRedirect / FollowedBy / PrecededBy run against the
 *        full BashExpr, not just leaf commands, so they can see the
 *        pipeline context.
 *
 * The evaluator never throws on malformed input — an unparseable
 * command yields Allow (since we already passed the BashParser).
 */
package rescale.hook

import rescale.hook.BashParser.BashExpr
import rescale.hook.Rule.*

object RuleEvaluator {

  /** Evaluate the rule set against a parsed bash expression. */
  def evaluate(expr: BashExpr, rules: RuleSet): Decision = {
    val commands = BashParser.allCommands(expr)
    if (commands.isEmpty) return Decision.Allow

    val perCommand = commands.zipWithIndex.map { case (cmd, idx) =>
      evaluateCommand(cmd, rules, commands, idx)
    }

    compose(perCommand)
  }

  /** Evaluate a single Simple command in isolation, with knowledge of
    * its surrounding pipeline context (for FollowedBy / PrecededBy).
    */
  private def evaluateCommand(
    cmd:      BashExpr.Simple,
    rules:    RuleSet,
    pipeline: List[BashExpr.Simple],
    idx:      Int
  ): Decision = {
    rules.rules.iterator
      .map(r => evaluateRule(r, cmd, pipeline, idx))
      .collectFirst { case Some(d) => d }
      .getOrElse(Decision.Allow)
  }

  /** Returns Some(decision) if the rule matched, None otherwise. */
  private def evaluateRule(
    entry:    RuleEntry,
    cmd:      BashParser.BashExpr.Simple,
    pipeline: List[BashParser.BashExpr.Simple],
    idx:      Int
  ): Option[Decision] = {
    if (!matches(entry.when, cmd, pipeline, idx)) return None

    // Try nested rules first; if any matched, return its decision.
    val nestedHit = entry.nested.iterator
      .map(r => evaluateRule(r, cmd, pipeline, idx))
      .collectFirst { case Some(d) => d }

    nestedHit match {
      case Some(d) => Some(d)
      case None =>
        // No nested rule matched. Use the leaf action, or the fallback.
        entry.action.orElse(entry.fallback)
    }
  }

  /** Recursively evaluate a Condition. */
  private def matches(
    cond:     Condition,
    cmd:      BashParser.BashExpr.Simple,
    pipeline: List[BashParser.BashExpr.Simple],
    idx:      Int
  ): Boolean = {
    cond match {
      case Condition.True              => true
      case Condition.False             => false
      case Condition.And(cs)           => cs.forall(c => matches(c, cmd, pipeline, idx))
      case Condition.Or(cs)            => cs.exists(c => matches(c, cmd, pipeline, idx))
      case Condition.Not(c)            => !matches(c, cmd, pipeline, idx)
      case Condition.StartsWith(prefix) =>
        startsWith(cmd, prefix)
      case Condition.HasAny(values) =>
        val all = (normalizeProgramName(cmd.program) :: cmd.args) ++ cmd.redirects.map(_.target)
        values.exists(v => all.contains(v))
      case Condition.HasAnySuffix(suffixes) =>
        val all = (normalizeProgramName(cmd.program) :: cmd.args) ++ cmd.redirects.map(_.target)
        suffixes.exists(suffix => all.exists(_.endsWith(suffix)))
      case Condition.HasAnyContains(substrs) =>
        val all = (normalizeProgramName(cmd.program) :: cmd.args) ++ cmd.redirects.map(_.target)
        substrs.exists(s => all.exists(_.contains(s)))
      case Condition.HasRedirectTargetPrefix(prefixes) =>
        cmd.redirects.exists(r => prefixes.exists(p => r.target.startsWith(p)))
      case Condition.HasAll(values) =>
        val all = (normalizeProgramName(cmd.program) :: cmd.args) ++ cmd.redirects.map(_.target)
        values.forall(v => all.contains(v))
      case Condition.HasRedirect(op) =>
        cmd.redirects.exists(_.op == op)
      case Condition.ProgramIn(programs) =>
        programs.contains(normalizeProgramName(cmd.program))
      case Condition.FollowedBy(next) =>
        if (idx + 1 >= pipeline.length) false
        else matches(next, pipeline(idx + 1), pipeline, idx + 1)
      case Condition.PrecededBy(prev) =>
        if (idx == 0) false
        else matches(prev, pipeline(idx - 1), pipeline, idx - 1)
    }
  }

  /** Check that program + args start with the given prefix list. */
  private def startsWith(cmd: BashExpr.Simple, prefix: List[String]): Boolean = {
    prefix match {
      case Nil => true
      case head :: tail =>
        if (normalizeProgramName(cmd.program) != head) false
        else cmd.args.take(tail.length) == tail && tail.length <= cmd.args.length
    }
  }

  /** Strip directory components from a program path so `/usr/bin/find`
    * matches `find` rules.
    */
  def normalizeProgramName(program: String): String = {
    if (program.contains("/")) program.split("/").last
    else program
  }

  /** Compose per-command decisions into a single result.
    *
    * The strongest decision wins (Deny > Ask > Pass > Allow). When
    * multiple commands produce the same maximum strength, the first
    * one's reason is kept.
    */
  def compose(decisions: List[Decision]): Decision = {
    if (decisions.isEmpty) Decision.Allow
    else decisions.maxBy(_.strength)
  }
}
