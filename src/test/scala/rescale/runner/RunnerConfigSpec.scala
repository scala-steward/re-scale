/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the .rescale/runners.yaml schema + loader.
 */
package rescale.runner

import munit.FunSuite

final class RunnerConfigSpec extends FunSuite {

  test("parse: minimal runner with just invoke") {
    val yaml =
      """runners:
        |  hello:
        |    invoke:
        |      command: echo
        |      args: [hi]
        |""".stripMargin
    val cfg = RunnersConfig.parse(yaml) match {
      case Right(c)  => c
      case Left(err) => fail(s"parse error: $err")
    }
    assertEquals(cfg.runners.size, 1)
    val r = cfg.runners("hello")
    assertEquals(r.invoke.command, "echo")
    assertEquals(r.invoke.args, Some(List("hi")))
  }

  test("parse: full runner with mode-file + output + modes") {
    val yaml =
      """runners:
        |  sass-spec:
        |    description: "dart-sass spec compatibility test harness"
        |    invoke:
        |      command: sbt
        |      args: [--client, "ssg-sass/testOnly ssg.sass.SassSpecRunner"]
        |    mode-file:
        |      path: ssg-sass/target/sass-spec-mode.tsv
        |      format: kv
        |    output:
        |      success:
        |        regex: 'sass-spec:\s+Total=(\d+)\s+Passing=(\d+)'
        |        capture:
        |          total: 1
        |          passing: 2
        |      failure:
        |        keep-lines-matching: ['sass-spec:', 'FAIL']
        |        max-lines: 10
        |    modes:
        |      regression: {}
        |      strict: { strict: "1" }
        |      snapshot: { snapshot: "1" }
        |      subdir: { subdir: "$1" }
        |""".stripMargin
    val cfg = RunnersConfig.parse(yaml) match {
      case Right(c)  => c
      case Left(err) => fail(s"parse error: $err")
    }
    val r = cfg.runners("sass-spec")
    assertEquals(r.description, Some("dart-sass spec compatibility test harness"))
    assertEquals(r.`mode-file`.map(_.path), Some("ssg-sass/target/sass-spec-mode.tsv"))
    assertEquals(r.output.flatMap(_.success).map(_.capture), Some(Map("total" -> 1, "passing" -> 2)))
    assertEquals(r.output.flatMap(_.failure).map(_.`max-lines`), Some(10))
    // modes
    assertEquals(r.modes.keys.toSet, Set("regression", "strict", "snapshot", "subdir"))
    assertEquals(r.modes("strict"), Map("strict" -> "1"))
    assertEquals(r.modes("subdir"), Map("subdir" -> "$1"))
    assertEquals(r.modes("regression"), Map.empty[String, String])
  }

  test("parse: empty runners yields empty map") {
    val cfg = RunnersConfig.parse("runners: {}").toOption.get
    assertEquals(cfg.runners, Map.empty[String, RunnersConfig.Runner])
  }

  test("parse: malformed YAML returns Left") {
    val r = RunnersConfig.parse("runners:\n  - bad")
    assert(r.isLeft, s"expected Left, got $r")
  }
}
