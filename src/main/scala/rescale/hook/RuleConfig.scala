/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `.rescale/claude-hooks.yaml` loader.
 *
 * Schema (mirrors the legacy `.claude-hook.yaml` plan):
 *
 *   rules:
 *     - when:
 *         starts-with: [adb]
 *       action: deny
 *       reason: "Use 're-scale test android' instead of direct adb"
 *
 *     - when:
 *         and:
 *           - starts-with: [git, push]
 *           - has-any: [--no-verify]
 *       action: deny
 *       reason: "Don't bypass git hooks with --no-verify"
 *
 *     - when:
 *         or:
 *           - program-in: [python, python3]
 *           - program-in: [ruby]
 *       action: ask
 *       reason: "Ad-hoc scripting needs review"
 *
 * Loaded rules are merged in FRONT of `DefaultRules.ruleSet`, so
 * per-project overrides win on first-match.
 *
 * The schema uses a flat polymorphic representation for `Condition`:
 * each constructor maps to a kebab-case field on a single `YamlCondition`
 * case class. The decoder picks the first non-empty field as the active
 * branch. This reads more naturally than nested-tag YAML and lets
 * kindlings auto-derivation handle the whole tree without writing a
 * custom polymorphic decoder.
 */
package rescale.hook

import hearth.kindlings.yamlderivation.{KindlingsYamlCodec, YamlConfig}
import org.virtuslab.yaml.*
import rescale.hook.Rule.{Condition as C, Decision, RuleEntry, RuleSet}

import java.io.File
import scala.io.Source

object RuleConfig {

  // `useDefaults` is REQUIRED for the YamlCondition schema below: it's
  // a flat polymorphic type where every YAML node sets exactly one
  // field and every other field must default to None. Without
  // useDefaults the decoder treats Option[T] fields as required and
  // rejects any node that doesn't list every key.
  //
  // This `given YamlConfig` must be in scope BEFORE the `derives`
  // declarations below — the kindlings derivation macro picks it up
  // implicitly.
  given YamlConfig = YamlConfig().withUseDefaults

  /** Top-level YAML document shape. */
  final case class YamlRuleSet(rules: List[YamlRule] = Nil) derives KindlingsYamlCodec

  /** A single rule entry. `nested` mirrors the optional sub-rule list
    * the legacy plan supported (gate-then-dispatch).
    */
  final case class YamlRule(
    `when`:  YamlCondition,
    action:  Option[String]   = None, // "allow" | "ask" | "deny" | "pass"
    reason:  Option[String]   = None,
    nested:  Option[List[YamlRule]] = None
  ) derives KindlingsYamlCodec

  /** Flat polymorphic condition. Exactly ONE of the constructor fields
    * should be non-empty per node. The decoder picks the first
    * non-empty in declaration order.
    */
  final case class YamlCondition(
    `starts-with`:                Option[List[String]] = None,
    `has-any`:                    Option[List[String]] = None,
    `has-all`:                    Option[List[String]] = None,
    `has-any-suffix`:             Option[List[String]] = None,
    `has-any-contains`:           Option[List[String]] = None,
    `has-redirect`:               Option[String]       = None,
    `has-redirect-target-prefix`: Option[List[String]] = None,
    `program-in`:                 Option[List[String]] = None,
    `and`:                        Option[List[YamlCondition]] = None,
    `or`:                         Option[List[YamlCondition]] = None,
    `not`:                        Option[YamlCondition]       = None,
    `followed-by`:                Option[YamlCondition]       = None,
    `preceded-by`:                Option[YamlCondition]       = None
  ) derives KindlingsYamlCodec

  // -- Public API ---------------------------------------------------

  /** Load and merge per-project rule overrides from
    * `.rescale/claude-hooks.yaml`. The file is optional — when missing,
    * the returned `RuleSet` is exactly `DefaultRules.ruleSet`.
    */
  def loadAndMerge(configFile: File, defaults: RuleSet = DefaultRules.ruleSet): Either[String, RuleSet] = {
    if (!configFile.exists()) Right(defaults)
    else {
      val src = Source.fromFile(configFile)
      val text =
        try src.mkString
        finally src.close()
      parse(text).map { overrides =>
        // First-match-wins: per-project rules go in FRONT of defaults.
        RuleSet(overrides.rules ++ defaults.rules)
      }
    }
  }

  /** Parse a YAML string into a typed RuleSet. Pure — no IO.
    * Returns Left(error) if the YAML is malformed or contains an
    * invalid condition / decision.
    */
  def parse(yamlText: String): Either[String, RuleSet] = {
    yamlText.as[YamlRuleSet] match {
      case Left(err)  => Left(s"YAML parse error: $err")
      case Right(doc) =>
        val converted = doc.rules.map(toRuleEntry)
        converted.partitionMap(identity) match {
          case (Nil, ok) => Right(RuleSet(ok))
          case (errs, _) => Left(errs.mkString("; "))
        }
    }
  }

  /** Convert a YamlRule into the internal RuleEntry. */
  private[hook] def toRuleEntry(y: YamlRule): Either[String, RuleEntry] = {
    for {
      cond     <- toCondition(y.`when`)
      action   <- toDecisionOpt(y.action, y.reason)
      nested   <- y.nested.getOrElse(Nil).map(toRuleEntry).partitionMap(identity) match {
                    case (Nil, ok) => Right(ok)
                    case (errs, _) => Left(errs.mkString("; "))
                  }
    } yield RuleEntry(when = cond, action = action, nested = nested)
  }

  /** Map the optional `action` + `reason` strings to a Decision.
    *
    * Note on the `allow` mapping: when an `allow` rule carries a non-empty
    * reason, we map it to `Decision.Pass(reason)` instead of bare
    * `Decision.Allow`. Both render to `permissionDecision=allow` in the
    * Claude Code response, but `Pass` preserves the user-provided reason
    * in `permissionDecisionReason` so the override is visible.
    */
  private[hook] def toDecisionOpt(action: Option[String], reason: Option[String]): Either[String, Option[Decision]] = {
    action match {
      case None => Right(None)
      case Some(name) =>
        val r = reason.getOrElse("")
        name.toLowerCase match {
          case "allow" => Right(Some(if (r.isEmpty) Decision.Allow else Decision.Pass(r)))
          case "pass"  => Right(Some(Decision.Pass(r)))
          case "ask"   => Right(Some(Decision.Ask(r)))
          case "deny"  => Right(Some(Decision.Deny(r)))
          case other   => Left(s"Unknown action: '$other' (expected allow/pass/ask/deny)")
        }
    }
  }

  /** Convert YamlCondition (one-of-many flat fields) to the internal Condition.
    * Picks the FIRST non-empty field; rejects nodes with zero or
    * multiple non-empty fields.
    */
  private[hook] def toCondition(y: YamlCondition): Either[String, C] = {
    val branches: List[(String, Either[String, C])] = List(
      "starts-with"                -> y.`starts-with`.map(p => Right(C.StartsWith(p))),
      "has-any"                    -> y.`has-any`.map(v => Right(C.HasAny(v))),
      "has-all"                    -> y.`has-all`.map(v => Right(C.HasAll(v))),
      "has-any-suffix"             -> y.`has-any-suffix`.map(v => Right(C.HasAnySuffix(v))),
      "has-any-contains"           -> y.`has-any-contains`.map(v => Right(C.HasAnyContains(v))),
      "has-redirect"               -> y.`has-redirect`.map(op => Right(C.HasRedirect(op))),
      "has-redirect-target-prefix" -> y.`has-redirect-target-prefix`.map(p => Right(C.HasRedirectTargetPrefix(p))),
      "program-in"                 -> y.`program-in`.map(p => Right(C.ProgramIn(p))),
      "and"                        -> y.`and`.map(cs => sequence(cs.map(toCondition)).map(C.And(_))),
      "or"                         -> y.`or`.map(cs => sequence(cs.map(toCondition)).map(C.Or(_))),
      "not"                        -> y.`not`.map(c => toCondition(c).map(C.Not(_))),
      "followed-by"                -> y.`followed-by`.map(c => toCondition(c).map(C.FollowedBy(_))),
      "preceded-by"                -> y.`preceded-by`.map(c => toCondition(c).map(C.PrecededBy(_)))
    ).collect { case (name, Some(result)) => (name, result) }

    branches match {
      case Nil           => Left("Empty when: clause — every rule needs at least one condition")
      case (_, r) :: Nil => r
      case multi         => Left(s"Multiple when: branches set (${multi.map(_._1).mkString(", ")}) — pick exactly one per node")
    }
  }

  private def sequence(xs: List[Either[String, C]]): Either[String, List[C]] = {
    val (errs, oks) = xs.partitionMap(identity)
    if (errs.nonEmpty) Left(errs.mkString("; ")) else Right(oks)
  }
}
