/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Recursive-descent parser for the bash subset Claude Code actually
 * produces. Ported verbatim from ssg-dev's hand-rolled implementation.
 *
 * Why hand-rolled and not cats-parse: the legacy parser already handles
 * the tricky edge cases (heredocs, command substitution, escaped chars,
 * 2>&1, etc.) and is in production use. A rewrite in cats-parse would
 * risk silent behavior drift on real Claude tool calls. Phase 2 adds
 * the test corpus the legacy never had — the parser stays, the tests
 * are new.
 *
 * Grammar (informal):
 *   BashExpr      = Pipeline (("&&" | "||" | ";") Pipeline)*
 *   Pipeline      = Command ("|" Command)*
 *   Command       = SimpleCommand | Subshell | $(BashExpr)
 *   SimpleCommand = word+ Redirect*
 *   Redirect      = (">" | ">>" | "<" | "2>" | "2>&1") word
 *
 * Word lexing:
 *   - single quotes are literal (no interpolation, no escapes)
 *   - double quotes interpret backslash escapes but stop at the closing "
 *   - $(...) is captured as a word with parens balanced
 *   - heredocs <<DELIM ... DELIM are captured as a single word with content
 */
package rescale.hook

object BashParser {

  enum BashExpr {
    case Simple(program: String, args: List[String], redirects: List[Redirect])
    case Pipe(left: BashExpr, right: BashExpr)
    case Chain(left: BashExpr, op: String, right: BashExpr)
    case Sub(inner: BashExpr)
    case Empty
  }

  final case class Redirect(op: String, target: String)

  enum Token {
    case Word(value: String)
    case Pipe
    case And
    case Or
    case Semi
    case LParen
    case RParen
    case DollarParen
    case RedirectOp(op: String)
    case Eof
  }

  final class Tokenizer(input: String) {
    private var pos: Int = 0

    def peek(): Token = {
      val saved = pos
      val tok   = next()
      pos = saved
      tok
    }

    def next(): Token = {
      skipWhitespace()
      if (pos >= input.length) return Token.Eof

      input(pos) match {
        case ';' | '\n' =>
          pos += 1
          Token.Semi
        case '|' =>
          pos += 1
          if (pos < input.length && input(pos) == '|') {
            pos += 1
            Token.Or
          } else Token.Pipe
        case '&' =>
          pos += 1
          if (pos < input.length && input(pos) == '&') {
            pos += 1
            Token.And
          } else Token.Semi
        case '(' =>
          pos += 1
          Token.LParen
        case ')' =>
          pos += 1
          Token.RParen
        case '$' if pos + 1 < input.length && input(pos + 1) == '(' =>
          pos += 2
          Token.DollarParen
        case '>' =>
          pos += 1
          if (pos < input.length && input(pos) == '>') {
            pos += 1
            Token.RedirectOp(">>")
          } else Token.RedirectOp(">")
        case '<' =>
          if (input.startsWith("<<", pos)) {
            pos += 2
            val delim   = readHeredocDelimiter()
            val content = readHeredocBody(delim)
            Token.Word(content)
          } else {
            pos += 1
            Token.RedirectOp("<")
          }
        case '2' if pos + 1 < input.length && input(pos + 1) == '>' =>
          pos += 2
          if (pos < input.length && input(pos) == '&' && pos + 1 < input.length && input(pos + 1) == '1') {
            pos += 2
            Token.RedirectOp("2>&1")
          } else if (pos < input.length && input(pos) == '>') {
            pos += 1
            Token.RedirectOp("2>>")
          } else Token.RedirectOp("2>")
        case _ =>
          Token.Word(readWord())
      }
    }

    private def skipWhitespace(): Unit = {
      while (pos < input.length && (input(pos) == ' ' || input(pos) == '\t')) {
        pos += 1
      }
      if (pos + 1 < input.length && input(pos) == '\\' && input(pos + 1) == '\n') {
        pos += 2
        skipWhitespace()
      }
    }

    private def readWord(): String = {
      val sb = new StringBuilder
      while (pos < input.length) {
        input(pos) match {
          case ' ' | '\t' | '\n' | ';' | '|' | '(' | ')'      => return sb.toString
          case '&'                                            => return sb.toString
          case '>' if sb.isEmpty || !sb.last.isDigit          => return sb.toString
          case '>' if sb.nonEmpty && sb.last == '2'           => return sb.toString
          case '<'                                            => return sb.toString
          case '\'' =>
            pos += 1
            while (pos < input.length && input(pos) != '\'') {
              sb.append(input(pos))
              pos += 1
            }
            if (pos < input.length) pos += 1
          case '"' =>
            pos += 1
            while (pos < input.length && input(pos) != '"') {
              if (input(pos) == '\\' && pos + 1 < input.length) {
                // Escaped character — skip the backslash, keep the next char
                pos += 1
                sb.append(input(pos))
                pos += 1
              } else if (input(pos) == '$' && pos + 1 < input.length && input(pos + 1) == '(') {
                // Command substitution inside double quotes: "$(...)".
                // The inner content can contain its own quotes, parens,
                // heredocs, etc. — we must balance parens to find the
                // real closing ')' before resuming the outer " scan.
                // Without this, a heredoc body containing a literal "
                // (e.g. `"every simple"`) prematurely closes the outer
                // double-quoted string, leaving the rest as raw tokens
                // that the parser chews on forever — the 48 GB incident.
                sb.append("$(")
                pos += 2
                var depth = 1
                while (pos < input.length && depth > 0) {
                  if (input(pos) == '(') depth += 1
                  else if (input(pos) == ')') depth -= 1
                  if (depth > 0) { sb.append(input(pos)); pos += 1 }
                  else pos += 1
                }
                sb.append(')')
              } else if (input(pos) == '`') {
                // Backtick command substitution inside double quotes:
                // "`...`". Skip to the matching closing backtick.
                sb.append('`')
                pos += 1
                while (pos < input.length && input(pos) != '`') {
                  sb.append(input(pos))
                  pos += 1
                }
                if (pos < input.length) { sb.append('`'); pos += 1 }
              } else {
                sb.append(input(pos))
                pos += 1
              }
            }
            if (pos < input.length) pos += 1
          case '\\' if pos + 1 < input.length =>
            pos += 1
            sb.append(input(pos))
            pos += 1
          case '$' if pos + 1 < input.length && input(pos + 1) == '(' =>
            sb.append("$(")
            pos += 2
            var depth = 1
            while (pos < input.length && depth > 0) {
              if (input(pos) == '(') depth += 1
              else if (input(pos) == ')') depth -= 1
              if (depth > 0) sb.append(input(pos))
              pos += 1
            }
            sb.append(')')
          case c =>
            sb.append(c)
            pos += 1
        }
      }
      sb.toString
    }

    private def readHeredocDelimiter(): String = {
      skipWhitespace()
      val sb      = new StringBuilder
      var quoting = false
      while (pos < input.length && input(pos) != '\n') {
        input(pos) match {
          case '\'' | '"' =>
            quoting = !quoting
            pos += 1
          case c =>
            sb.append(c)
            pos += 1
        }
      }
      if (pos < input.length) pos += 1
      sb.toString.trim
    }

    private def readHeredocBody(delim: String): String = {
      val sb = new StringBuilder
      while (pos < input.length) {
        val lineStart = pos
        while (pos < input.length && input(pos) != '\n') pos += 1
        val line = input.substring(lineStart, pos).trim
        if (pos < input.length) pos += 1
        if (line == delim) return sb.toString
        sb.append(line)
        sb.append('\n')
      }
      sb.toString
    }
  }

  def parse(input: String): BashExpr = {
    if (input.trim.isEmpty) return BashExpr.Empty
    val tokenizer = new Tokenizer(input)
    parseExpr(tokenizer)
  }

  private def parseExpr(t: Tokenizer): BashExpr = {
    var left     = parsePipeline(t)
    var continue = true
    while (continue) {
      t.peek() match {
        case Token.And =>
          t.next()
          val right = parsePipeline(t)
          left = BashExpr.Chain(left, "&&", right)
        case Token.Or =>
          t.next()
          val right = parsePipeline(t)
          left = BashExpr.Chain(left, "||", right)
        case Token.Semi =>
          t.next()
          if (t.peek() != Token.Eof && t.peek() != Token.RParen) {
            val right = parsePipeline(t)
            left = BashExpr.Chain(left, ";", right)
          }
        case _ =>
          continue = false
      }
    }
    left
  }

  private def parsePipeline(t: Tokenizer): BashExpr = {
    var left = parseCommand(t)
    while (t.peek() == Token.Pipe) {
      t.next()
      val right = parseCommand(t)
      left = BashExpr.Pipe(left, right)
    }
    left
  }

  private def parseCommand(t: Tokenizer): BashExpr = {
    t.peek() match {
      case Token.DollarParen =>
        t.next()
        val inner = parseExpr(t)
        if (t.peek() == Token.RParen) t.next()
        BashExpr.Sub(inner)
      case Token.LParen =>
        t.next()
        val inner = parseExpr(t)
        if (t.peek() == Token.RParen) t.next()
        BashExpr.Sub(inner)
      case _ =>
        parseSimple(t)
    }
  }

  private def parseSimple(t: Tokenizer): BashExpr = {
    val words     = scala.collection.mutable.ListBuffer.empty[String]
    val redirects = scala.collection.mutable.ListBuffer.empty[Redirect]

    var continue = true
    while (continue) {
      t.peek() match {
        case Token.Word(w) =>
          t.next()
          words += w
        case Token.RedirectOp(op) =>
          t.next()
          t.peek() match {
            case Token.Word(target) =>
              t.next()
              redirects += Redirect(op, target)
            case _ =>
              redirects += Redirect(op, "")
          }
        case _ =>
          continue = false
      }
    }

    if (words.isEmpty) BashExpr.Empty
    else BashExpr.Simple(words.head, words.tail.toList, redirects.toList)
  }

  /** Flatten an expression to all `Simple` commands it contains. Used
    * by the rule evaluator to apply per-command rules across pipes,
    * chains, and subshells.
    */
  def allCommands(expr: BashExpr): List[BashExpr.Simple] =
    expr match {
      case s: BashExpr.Simple      => List(s)
      case BashExpr.Pipe(l, r)     => allCommands(l) ++ allCommands(r)
      case BashExpr.Chain(l, _, r) => allCommands(l) ++ allCommands(r)
      case BashExpr.Sub(inner)     => allCommands(inner)
      case BashExpr.Empty          => Nil
    }
}
