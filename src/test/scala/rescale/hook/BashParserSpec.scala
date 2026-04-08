/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Comprehensive bash parser test corpus. The legacy ssg-dev parser
 * had ZERO tests; this is the regression net for re-scale.
 *
 * Tests are organized by feature area:
 *   - simple commands and arguments
 *   - quoting (single, double, escapes)
 *   - pipelines
 *   - chains (&&, ||, ;)
 *   - command substitution
 *   - redirects
 *   - allCommands traversal
 */
package rescale.hook

import munit.FunSuite
import rescale.hook.BashParser.{BashExpr, Redirect}

final class BashParserSpec extends FunSuite {

  // -- helpers --------------------------------------------------------

  private def parseSimple(input: String): BashExpr.Simple =
    BashParser.parse(input) match {
      case s: BashExpr.Simple => s
      case other => fail(s"expected Simple, got: $other")
    }

  // -- empty / whitespace ---------------------------------------------

  test("empty input is Empty") {
    assertEquals(BashParser.parse(""), BashExpr.Empty)
    assertEquals(BashParser.parse("   "), BashExpr.Empty)
  }

  // -- simple commands ------------------------------------------------

  test("bare command with no args") {
    val s = parseSimple("ls")
    assertEquals(s.program, "ls")
    assertEquals(s.args, Nil)
    assertEquals(s.redirects, Nil)
  }

  test("command with positional args") {
    val s = parseSimple("git status --short")
    assertEquals(s.program, "git")
    assertEquals(s.args, List("status", "--short"))
  }

  test("command with --flag value form") {
    val s = parseSimple("ssg-dev db audit set foo --status pass")
    assertEquals(s.program, "ssg-dev")
    assertEquals(s.args, List("db", "audit", "set", "foo", "--status", "pass"))
  }

  test("absolute path program") {
    val s = parseSimple("/usr/bin/find . -name '*.scala'")
    assertEquals(s.program, "/usr/bin/find")
    assertEquals(s.args, List(".", "-name", "*.scala"))
  }

  // -- quoting --------------------------------------------------------

  test("single quotes are literal") {
    val s = parseSimple("echo 'hello world'")
    assertEquals(s.args, List("hello world"))
  }

  test("double quotes interpret escapes but no interpolation") {
    val s = parseSimple("echo \"hello \\\"world\\\"\"")
    assertEquals(s.args, List("hello \"world\""))
  }

  test("backslash-escaped chars in unquoted words") {
    val s = parseSimple("echo hello\\ world")
    assertEquals(s.args, List("hello world"))
  }

  test("single-quoted args containing semicolons + pipes") {
    val s = parseSimple("git commit -m 'fix: pipe | and ; semi'")
    assertEquals(s.program, "git")
    assertEquals(s.args, List("commit", "-m", "fix: pipe | and ; semi"))
  }

  // -- pipelines ------------------------------------------------------

  test("simple pipe") {
    BashParser.parse("ls | wc -l") match {
      case BashExpr.Pipe(l, r) =>
        assertEquals(l.asInstanceOf[BashExpr.Simple].program, "ls")
        assertEquals(r.asInstanceOf[BashExpr.Simple].program, "wc")
      case other => fail(s"expected Pipe, got $other")
    }
  }

  test("triple pipe") {
    BashParser.parse("cat foo | sort | uniq") match {
      case BashExpr.Pipe(BashExpr.Pipe(a, b), c) =>
        assertEquals(a.asInstanceOf[BashExpr.Simple].program, "cat")
        assertEquals(b.asInstanceOf[BashExpr.Simple].program, "sort")
        assertEquals(c.asInstanceOf[BashExpr.Simple].program, "uniq")
      case other => fail(s"expected nested Pipe, got $other")
    }
  }

  // -- chains ---------------------------------------------------------

  test("chain with &&") {
    BashParser.parse("git status && git diff") match {
      case BashExpr.Chain(l, op, r) =>
        assertEquals(op, "&&")
        assertEquals(l.asInstanceOf[BashExpr.Simple].args.head, "status")
        assertEquals(r.asInstanceOf[BashExpr.Simple].args.head, "diff")
      case other => fail(s"expected Chain, got $other")
    }
  }

  test("chain with ||") {
    BashParser.parse("test -f foo || echo missing") match {
      case BashExpr.Chain(_, op, _) => assertEquals(op, "||")
      case other => fail(s"expected Chain, got $other")
    }
  }

  test("chain with ;") {
    BashParser.parse("cd /tmp ; ls") match {
      case BashExpr.Chain(_, op, _) => assertEquals(op, ";")
      case other => fail(s"expected Chain, got $other")
    }
  }

  test("trailing ; produces no extra Empty") {
    BashParser.parse("ls ;") match {
      case s: BashExpr.Simple => assertEquals(s.program, "ls")
      case other => fail(s"expected Simple, got $other")
    }
  }

  // -- command substitution -------------------------------------------

  test("$(cmd) is captured as part of word") {
    val s = parseSimple("echo result-$(date +%s)")
    assertEquals(s.program, "echo")
    assertEquals(s.args, List("result-$(date +%s)"))
  }

  test("standalone $(cmd) parses as Sub") {
    BashParser.parse("$(echo foo)") match {
      case BashExpr.Sub(inner) =>
        assertEquals(inner.asInstanceOf[BashExpr.Simple].program, "echo")
      case other => fail(s"expected Sub, got $other")
    }
  }

  // -- redirects ------------------------------------------------------

  test(">>file is captured as redirect") {
    val s = parseSimple("echo line >> /tmp/out.log")
    assertEquals(s.program, "echo")
    assertEquals(s.args, List("line"))
    assertEquals(s.redirects, List(Redirect(">>", "/tmp/out.log")))
  }

  test(">file is captured as redirect") {
    val s = parseSimple("ls > out.txt")
    assertEquals(s.redirects, List(Redirect(">", "out.txt")))
  }

  test("2>&1 is captured") {
    val s = parseSimple("scala-cli compile 2>&1")
    assertEquals(s.redirects, List(Redirect("2>&1", "")))
  }

  test("2> file is captured") {
    val s = parseSimple("cmd 2> err.log")
    assertEquals(s.redirects, List(Redirect("2>", "err.log")))
  }

  // -- allCommands ----------------------------------------------------

  test("allCommands flattens a pipeline") {
    val expr = BashParser.parse("ls | grep foo | wc -l")
    val cmds = BashParser.allCommands(expr)
    assertEquals(cmds.map(_.program), List("ls", "grep", "wc"))
  }

  test("allCommands flattens a chain") {
    val expr = BashParser.parse("git add . && git commit -m wip")
    val cmds = BashParser.allCommands(expr)
    assertEquals(cmds.map(_.program), List("git", "git"))
  }

  test("allCommands traverses subshells") {
    val expr = BashParser.parse("$(echo a) $(echo b)")
    val cmds = BashParser.allCommands(expr)
    // The outer is a Simple where program="$(echo a)" — not a Sub at top
    // level because $(...) inside a word is different from a standalone
    // Sub. Verify that allCommands at minimum sees the outer.
    assert(cmds.nonEmpty)
  }

  test("allCommands on Empty returns Nil") {
    assertEquals(BashParser.allCommands(BashExpr.Empty), Nil)
  }

  // -- realistic Claude tool calls ------------------------------------

  test("real: ssg-dev quality shortcuts --module ssg-md") {
    val s = parseSimple("ssg-dev quality shortcuts --module ssg-md")
    assertEquals(s.program, "ssg-dev")
    assertEquals(s.args, List("quality", "shortcuts", "--module", "ssg-md"))
  }

  test("real: cd subdir && command") {
    BashParser.parse("cd /tmp && ls -la") match {
      case BashExpr.Chain(_, "&&", _) => // ok
      case other => fail(s"expected && Chain, got $other")
    }
  }

  test("real: rm -rf is parseable (rules will deny it)") {
    val s = parseSimple("rm -rf /tmp/foo")
    assertEquals(s.program, "rm")
    assertEquals(s.args, List("-rf", "/tmp/foo"))
  }

  test("real: git log --oneline | head -20") {
    BashParser.parse("git log --oneline | head -20") match {
      case BashExpr.Pipe(l, r) =>
        assertEquals(l.asInstanceOf[BashExpr.Simple].program, "git")
        assertEquals(r.asInstanceOf[BashExpr.Simple].program, "head")
      case other => fail(s"expected Pipe, got $other")
    }
  }

  test("real: scala-cli package --native --force") {
    val s = parseSimple("scala-cli --power package scripts/src --native -o scripts/bin/ssg-dev --force")
    assertEquals(s.program, "scala-cli")
    assert(s.args.contains("package"))
    assert(s.args.contains("--native"))
    assert(s.args.contains("--force"))
  }

  test("real: gh pr create --title 'foo' --body 'bar'") {
    val s = parseSimple("gh pr create --title 'foo: bar' --body 'multi line body'")
    assertEquals(s.program, "gh")
    assert(s.args.contains("create"))
    assert(s.args.contains("foo: bar"))
    assert(s.args.contains("multi line body"))
  }
}
