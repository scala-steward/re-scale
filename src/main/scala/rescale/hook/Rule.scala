/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Rule data model for the configurable PreToolUse hook.
 *
 * The user-facing config is YAML (loaded by RuleConfig.scala in a
 * follow-up commit), but rules are evaluated in this Scala
 * representation. Keeping the Scala model independent of the YAML
 * loader means the evaluator stays testable without a config file
 * round-trip and the default rules can be expressed as plain Scala.
 *
 * Schema (mirrors the YAML the user spec'd):
 *
 *   match:
 *     - when:
 *         and:                # condition DSL
 *           - starts-with: [git, push]
 *           - not:
 *               has-any: [--dry-run]
 *       action: deny
 *       reason: "Pushes are gated; use 're-scale git push' which prompts."
 *     - when:
 *         starts-with: [gh, pr, create]
 *       match:                # nested when — only evaluated if outer when is true
 *         - when:
 *             has-any: [--draft]
 *           action: allow
 *           reason: "Draft PRs are safe."
 *       action: ask           # fallback if no nested rule matched
 *       reason: "PR create is a shared-state action."
 *
 * Composition rules (applied across all subcommands of a pipeline /
 * chain / substitution):
 *   - any deny  ⇒ whole command deny  (with the strongest deny reason)
 *   - else any ask ⇒ whole command ask
 *   - else any pass ⇒ whole command pass
 *   - else allow
 */
package rescale.hook

object Rule {

  /** The terminal verdict for a single command (or composed for a
    * pipeline / chain).
    */
  enum Decision {
    case Allow
    case Pass(reason: String)  // default-allow with explanation surfaced to user
    case Ask(reason: String)   // user confirmation required
    case Deny(reason: String)  // hard fail; the tool call is blocked

    def reason: String = this match {
      case Allow      => ""
      case Pass(r)    => r
      case Ask(r)     => r
      case Deny(r)    => r
    }

    /** Strength ordering — higher number wins when composing decisions
      * across pipe / chain / sub command lists.
      */
    def strength: Int = this match {
      case Allow   => 0
      case Pass(_) => 1
      case Ask(_)  => 2
      case Deny(_) => 3
    }
  }

  /** A condition is a predicate over a single command's program + args
    * + redirects. Conditions compose with and/or/not. The
    * `followed-by` and `preceded-by` operators are pipeline-aware and
    * evaluated by the RuleEvaluator, not by the condition itself —
    * see [[Condition.FollowedBy]] / [[Condition.PrecededBy]].
    */
  enum Condition {
    case True
    case False
    case And(conds: List[Condition])
    case Or(conds: List[Condition])
    case Not(cond: Condition)

    /** Match if the command's program + args start with the given
      * sequence of literals. The first element is matched against the
      * program; subsequent elements against args[0], args[1], ...
      *
      * Example: `StartsWith(List("git", "push"))` matches `git push`,
      * `git push origin main`, but not `git status` or `gh push`.
      */
    case StartsWith(prefix: List[String])

    /** Match if any of the listed values appears anywhere in the
      * command (program, args, or redirect targets).
      */
    case HasAny(values: List[String])

    /** Match if all of the listed values appear in the command.
      */
    case HasAll(values: List[String])

    /** Match if a command in the same pipeline is followed by another
      * command matching `next`. Used by the legacy ssg-dev rule that
      * forbids `ssg-dev db ... | head` etc.
      */
    case FollowedBy(next: Condition)

    /** Mirror of FollowedBy. */
    case PrecededBy(prev: Condition)

    /** Match against a redirect operator on the command (e.g. "2>&1"). */
    case HasRedirect(op: String)

    /** Match if the program (after path normalization) equals one of
      * the given values. Convenience for the very common
      * `program in {x, y, z}` case.
      */
    case ProgramIn(programs: List[String])
  }

  /** A single rule. Either has a leaf `action` (the simple form) or a
    * nested `match` of sub-rules (where the outer `when` gates the
    * sub-rules and the outer `action` is the fallback if no sub-rule
    * matched).
    */
  final case class RuleEntry(
    when:     Condition,
    action:   Option[Decision],
    nested:   List[RuleEntry] = Nil,
    fallback: Option[Decision] = None
  )

  /** A complete rule set, evaluated top-down. First match wins. */
  final case class RuleSet(rules: List[RuleEntry])

  object RuleSet {
    val empty: RuleSet = RuleSet(Nil)
  }
}
