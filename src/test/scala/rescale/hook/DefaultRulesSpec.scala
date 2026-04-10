/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Parity tests: every rule from the legacy ssg-dev RuleEngine.scala is
 * covered by at least one test here that confirms re-scale's default
 * rule set produces the same Decision. If a test in this file fails,
 * the default rule set has drifted from the legacy behavior and needs
 * to be reconciled.
 */
package rescale.hook

import munit.FunSuite
import rescale.hook.Rule.Decision

final class DefaultRulesSpec extends FunSuite {

  private def decide(input: String): Decision =
    RuleEvaluator.evaluate(BashParser.parse(input), DefaultRules.ruleSet)

  // -- Self-tool always allowed --------------------------------------

  test("re-scale, ssg-dev, sge-dev: always allow") {
    assertEquals(decide("re-scale db audit list"), Decision.Allow)
    assertEquals(decide("ssg-dev db audit list"), Decision.Allow)
    assertEquals(decide("sge-dev test unit"), Decision.Allow)
  }

  // -- Trusted programs ----------------------------------------------

  test("scala-cli, cs, npm, npx, cargo: allow") {
    assertEquals(decide("scala-cli compile"), Decision.Allow)
    assertEquals(decide("cs install --native re-scale"), Decision.Allow)
    assertEquals(decide("coursier launch foo"), Decision.Allow)
    assertEquals(decide("npm install"), Decision.Allow)
    assertEquals(decide("npx eslint"), Decision.Allow)
    assertEquals(decide("cargo build"), Decision.Allow)
  }

  test("java -version: allow") {
    assertEquals(decide("java -version"), Decision.Allow)
  }

  test("sbt --client: allow") {
    assertEquals(decide("sbt --client compile"), Decision.Allow)
  }

  test("bare sbt: deny with --client suggestion") {
    decide("sbt compile") match {
      case Decision.Deny(r) => assert(r.contains("--client"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  // -- adb / fastboot -------------------------------------------------

  test("adb: deny") {
    decide("adb shell ls") match {
      case Decision.Deny(r) => assert(r.contains("adb"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("fastboot: deny") {
    decide("fastboot flash boot") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  // -- Destructive: rm ------------------------------------------------

  test("rm without flags: deny") {
    decide("rm /tmp/foo") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("rm -rf: deny") {
    decide("rm -rf /tmp/foo") match {
      case Decision.Deny(r) => assert(r.contains("-r") || r.contains("Destructive"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("rm -f: deny") {
    decide("rm -f file") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  // -- Suboptimal tools ----------------------------------------------

  test("grep: deny → use Grep tool") {
    decide("grep foo bar.scala") match {
      case Decision.Deny(r) => assert(r.contains("Grep"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("rg: deny → use Grep tool") {
    decide("rg foo") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("find: deny → use Glob tool") {
    decide("find . -name '*.scala'") match {
      case Decision.Deny(r) => assert(r.contains("Glob"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("ls: deny → use Glob") {
    decide("ls -la") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("cat foo.txt: ALLOW (cat is universally safe; secret-file rule covers the dangerous case)") {
    // The earlier rule denied every cat / head / tail / less / more
    // invocation. That was over-broad — `cat foo.txt` is the cleanest
    // way to display a file's contents and the agent should be allowed
    // to use it. The dangerous case (`cat secret.env`, `cat .pem`) is
    // covered by the secret-file rule above.
    assertEquals(decide("cat foo.txt"), Decision.Allow)
    assertEquals(decide("head foo.txt"), Decision.Allow)
    assertEquals(decide("tail foo.txt"), Decision.Allow)
    assertEquals(decide("less foo.txt"), Decision.Allow)
    assertEquals(decide("more foo.txt"), Decision.Allow)
    // Bare cat (heredoc passthrough scenarios) is also fine.
    assertEquals(decide("cat"), Decision.Allow)
  }

  test("cat secret.env: deny (secret-file rule wins)") {
    decide("cat secret.env") match {
      case Decision.Deny(r) => assert(r.contains("secret"), s"reason='$r'")
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("pipe to head/tail/wc/grep: deny → write to file + Read tool") {
    // The anti-pattern is `command | filter` — push the agent toward
    // intermediate files so they can re-inspect via Read.
    val cases = List(
      "find . | head",
      "ls -la | head -20",
      "cat foo.txt | tail",
      "echo hello | wc -l",
      "re-scale db audit list | grep open",
      "git log | head"
    )
    cases.foreach { c =>
      decide(c) match {
        case Decision.Deny(r) => assert(r.contains("pipe") || r.contains("Read") || r.contains("Grep"),
          s"$c → '$r'")
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("standalone head/tail without pipe: allow") {
    // A standalone `head -10 < file.txt` doesn't fit the pipe-to-filter
    // anti-pattern. Allow it. The Read tool with offset/limit is still
    // the better choice but we don't force it.
    assertEquals(decide("head -10 foo.txt"), Decision.Allow)
    assertEquals(decide("tail -f foo.log"), Decision.Allow)
    assertEquals(decide("wc -l foo.txt"), Decision.Allow)
  }

  test("echo with > redirect: deny → use Write/Edit") {
    decide("echo hello > foo.txt") match {
      case Decision.Deny(r) => assert(r.contains("Write") || r.contains("Edit"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("plain echo without redirect: allow") {
    assertEquals(decide("echo hello"), Decision.Allow)
  }

  test("sed / awk / perl: deny → use Edit") {
    List("sed -i s/x/y/ foo", "awk '{print}'", "perl -e 'print 1'").foreach { c =>
      decide(c) match {
        case Decision.Deny(r) => assert(r.contains("Edit"), s"$c → '$r'")
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("sort / uniq / cut / tr / xargs: deny") {
    // wc was removed from this list — see "standalone head/tail without pipe: allow"
    // and the pipe-to-filter rule.
    List("sort", "uniq -c", "cut -f 1", "tr A B", "xargs ls").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("python / python3 / ruby / node: deny ad-hoc scripting") {
    List("python foo.py", "python3 -c 'print(1)'", "ruby -e '1'", "node script.js").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  // -- Process killers ------------------------------------------------

  test("kill / killall / pkill: deny") {
    List("kill 1234", "killall foo", "pkill bar").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  // -- HTTP mutation --------------------------------------------------

  test("curl -X POST: deny") {
    decide("curl -X POST https://example.com/api") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("plain curl GET: allow") {
    assertEquals(decide("curl https://example.com"), Decision.Allow)
  }

  // -- JAR / archive grep evasion ------------------------------------

  test("curl downloading a .jar: ask") {
    decide("curl https://repo/foo.jar -o foo.jar") match {
      case Decision.Ask(r) => assert(r.contains("Downloading"), s"reason='$r'")
      case other => fail(s"expected Ask, got $other")
    }
  }

  // -- git granular --------------------------------------------------

  test("git status / diff / log / show / blame: allow") {
    List("git status", "git diff", "git log --oneline", "git show HEAD", "git blame foo.scala").foreach { c =>
      assertEquals(decide(c), Decision.Allow, c)
    }
  }

  test("git add / commit / fetch / pull / clone / merge / push: allow") {
    List(
      "git add .",
      "git commit -m wip",
      "git fetch origin",
      "git pull",
      "git clone https://github.com/foo/bar",
      "git merge feature",
      "git push origin main"
    ).foreach { c => assertEquals(decide(c), Decision.Allow, c) }
  }

  test("git reset / restore / clean / rebase: deny") {
    List("git reset --hard", "git restore foo", "git clean -fd", "git rebase main").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("git commit --amend: deny") {
    decide("git commit --amend") match {
      case Decision.Deny(r) => assert(r.contains("amend"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("git push --force: deny") {
    decide("git push --force") match {
      case Decision.Deny(r) => assert(r.contains("Force"), s"reason='$r'")
      case other => fail(s"expected Deny, got $other")
    }
  }

  test("git branch -D: deny") {
    decide("git branch -D feature") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }

  // -- gh granular ---------------------------------------------------

  test("gh pr list / view / diff / checks / status: allow") {
    List("gh pr list", "gh pr view 123", "gh pr diff 123", "gh pr checks", "gh pr status").foreach { c =>
      assertEquals(decide(c), Decision.Allow, c)
    }
  }

  test("gh issue list / view: allow") {
    assertEquals(decide("gh issue list"), Decision.Allow)
    assertEquals(decide("gh issue view 42"), Decision.Allow)
  }

  test("gh api: allow") {
    assertEquals(decide("gh api repos/foo/bar"), Decision.Allow)
  }

  test("gh pr create / merge / close: ask") {
    List("gh pr create --title foo", "gh pr merge 1", "gh pr close 1").foreach { c =>
      decide(c) match {
        case Decision.Ask(_) => // ok
        case other => fail(s"$c → expected Ask, got $other")
      }
    }
  }

  test("gh issue create / close: ask") {
    List("gh issue create --title foo", "gh issue close 1").foreach { c =>
      decide(c) match {
        case Decision.Ask(_) => // ok
        case other => fail(s"$c → expected Ask, got $other")
      }
    }
  }

  // -- Safe utilities ------------------------------------------------

  test("safe utilities all allow") {
    List(
      "cd /tmp",
      "pwd",
      "mkdir foo",
      "env",
      "date",
      "uname -a",
      "tar tf x.tar",
      "mv a b",
      "cp a b",
      "chmod +x foo",
      "sleep 1"
    ).foreach { c => assertEquals(decide(c), Decision.Allow, c) }
  }

  // -- Phase 10 backports: safety rails from sge/main ssg ------------

  test("redirect to /etc/: deny") {
    decide("echo foo > /etc/passwd") match {
      case Decision.Deny(r) => assert(r.contains("system directory"), s"reason='$r'")
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("redirect to /usr/local/: deny") {
    decide("cat foo > /usr/local/bin/x") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("redirect to /System/ and /Library/: deny") {
    List("echo x > /System/foo", "echo x > /Library/Preferences/y").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other            => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("secret-file access: cp creds.env: deny") {
    decide("cp creds.env target.txt") match {
      case Decision.Deny(r) => assert(r.contains("secret"), s"reason='$r'")
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("secret-file access: .pem and .key: deny") {
    List("mv private.pem backup/", "ln -s server.key /tmp/x").foreach { c =>
      decide(c) match {
        case Decision.Deny(_) => // ok
        case other            => fail(s"$c → expected Deny, got $other")
      }
    }
  }

  test("secret-file access: credentials. substring: deny") {
    decide("mv aws.credentials.json /tmp/") match {
      case Decision.Deny(_) => // ok
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("re-scale itself is exempt from secret-file rule") {
    // re-scale manages skip-policy.tsv and may legitimately reference
    // secret-like patterns in audit data.
    assertEquals(decide("re-scale db audit list --file foo.env"), Decision.Allow)
  }

  test("git config write: deny") {
    decide("git config user.email me@example.com") match {
      case Decision.Deny(r) => assert(r.contains("git config"), s"reason='$r'")
      case other            => fail(s"expected Deny, got $other")
    }
  }

  test("git config --get: allow") {
    assertEquals(decide("git config --get user.email"), Decision.Allow)
  }

  test("git config --list: allow") {
    assertEquals(decide("git config --list"), Decision.Allow)
  }

  // -- Composition smoke tests ---------------------------------------

  test("safe pipeline: allow") {
    assertEquals(decide("re-scale db audit stats && re-scale port verify"), Decision.Allow)
  }

  test("dangerous pipeline: deny wins") {
    decide("re-scale db audit list && rm -rf /tmp/foo") match {
      case Decision.Deny(_) => // ok
      case other => fail(s"expected Deny, got $other")
    }
  }
}
