/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Predicate expression parser and evaluator for the `fileinfo` command.
 * Uses cats-parse for a small expression grammar supporting field
 * comparisons (=, !=, contains, starts-with, ends-with) composed
 * with boolean operators (&&, ||, !).
 */
package rescale.fileinfo

import cats.parse.{Parser, Parser0}

object Predicate {

  sealed trait Expr
  object Expr {
    final case class Eq(field: String, value: String) extends Expr
    final case class Neq(field: String, value: String) extends Expr
    final case class Contains(field: String, value: String) extends Expr
    final case class StartsWith(field: String, value: String) extends Expr
    final case class EndsWith(field: String, value: String) extends Expr
    final case class And(left: Expr, right: Expr) extends Expr
    final case class Or(left: Expr, right: Expr) extends Expr
    final case class Not(inner: Expr) extends Expr
  }

  def parse(input: String): Either[String, Expr] =
    exprParser.parseAll(input.trim) match {
      case Right(e) => Right(e)
      case Left(err) =>
        Left(s"parse error at position ${err.failedAtOffset}: " +
          err.expected.toList.mkString(", "))
    }

  def evaluate(expr: Expr, props: Map[String, String]): Boolean = expr match {
    case Expr.Eq(f, v)         => props.getOrElse(f, "") == v
    case Expr.Neq(f, v)        => props.getOrElse(f, "") != v
    case Expr.Contains(f, v)   =>
      val raw = props.getOrElse(f, "")
      raw.contains(v) || raw.split(",").map(_.trim).contains(v)
    case Expr.StartsWith(f, v) => props.getOrElse(f, "").startsWith(v)
    case Expr.EndsWith(f, v)   => props.getOrElse(f, "").endsWith(v)
    case Expr.And(l, r)        => evaluate(l, props) && evaluate(r, props)
    case Expr.Or(l, r)         => evaluate(l, props) || evaluate(r, props)
    case Expr.Not(inner)       => !evaluate(inner, props)
  }

  // -- parser internals --------------------------------------------------

  private val ws: Parser0[Unit] = Parser.charsWhile0(_.isWhitespace).void

  private val fieldP: Parser[String] =
    Parser.charsWhile(c => c.isLetterOrDigit || c == '-' || c == '_')

  private val quotedDouble: Parser[String] =
    Parser.char('"') *> Parser.charsWhile0(_ != '"') <* Parser.char('"')

  private val quotedSingle: Parser[String] =
    Parser.char('\'') *> Parser.charsWhile0(_ != '\'') <* Parser.char('\'')

  private val bareWord: Parser[String] =
    Parser.charsWhile(c =>
      !c.isWhitespace && c != ')' && c != '(' && c != '&' && c != '|' && c != '!'
    )

  private val valueP: Parser[String] =
    quotedDouble.backtrack | quotedSingle.backtrack | bareWord

  private sealed trait Op
  private case object EqOp extends Op
  private case object NeqOp extends Op
  private case object ContainsOp extends Op
  private case object StartsWithOp extends Op
  private case object EndsWithOp extends Op

  private def mkExpr(f: String, op: Op, v: String): Expr = op match {
    case EqOp         => Expr.Eq(f, v)
    case NeqOp        => Expr.Neq(f, v)
    case ContainsOp   => Expr.Contains(f, v)
    case StartsWithOp => Expr.StartsWith(f, v)
    case EndsWithOp   => Expr.EndsWith(f, v)
  }

  private val symbolOp: Parser[Op] =
    Parser.string("!=").as(NeqOp: Op).backtrack |
    Parser.char('=').as(EqOp: Op)

  private val wordBoundary: Parser0[Unit] =
    Parser.not(Parser.charWhere(c => c.isLetterOrDigit || c == '-' || c == '_'))

  private val keywordOp: Parser[Op] =
    (Parser.string("starts-with") <* wordBoundary).as(StartsWithOp: Op).backtrack |
    (Parser.string("ends-with") <* wordBoundary).as(EndsWithOp: Op).backtrack |
    (Parser.string("contains") <* wordBoundary).as(ContainsOp: Op)

  private val comparison: Parser[Expr] = {
    val withSymbol = (fieldP ~ (ws *> symbolOp) ~ (ws *> valueP)).map {
      case ((f, op), v) => mkExpr(f, op, v)
    }
    val withKeyword = (fieldP ~ (ws *> keywordOp) ~ (ws *> valueP)).map {
      case ((f, op), v) => mkExpr(f, op, v)
    }
    withSymbol.backtrack | withKeyword
  }

  private val exprParser: Parser[Expr] = Parser.recursive[Expr] { recurse =>
    val parens: Parser[Expr] =
      Parser.char('(') *> ws *> recurse <* ws <* Parser.char(')')

    lazy val primary: Parser[Expr] = {
      val negated =
        (Parser.char('!') *> ws *> Parser.defer(primary)).map(Expr.Not(_))
      parens.backtrack | negated.backtrack | comparison
    }

    val andSep: Parser[Unit] =
      (ws.with1 *> Parser.string("&&") *> ws).void.backtrack |
      (Parser.string("&&") *> ws).void
    val orSep: Parser[Unit] =
      (ws.with1 *> Parser.string("||") *> ws).void.backtrack |
      (Parser.string("||") *> ws).void

    val andExpr: Parser[Expr] =
      (primary ~ (andSep *> primary).rep0).map { case (first, rest) =>
        rest.foldLeft(first)(Expr.And(_, _))
      }

    (andExpr ~ (orSep *> andExpr).rep0).map { case (first, rest) =>
      rest.foldLeft(first)(Expr.Or(_, _))
    }
  }
}
