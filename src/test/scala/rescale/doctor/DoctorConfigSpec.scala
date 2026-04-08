/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the .rescale/doctor.yaml schema + loader.
 */
package rescale.doctor

import munit.FunSuite

final class DoctorConfigSpec extends FunSuite {

  test("parse: minimal valid config") {
    val yaml =
      """steps:
        |  - id: jdk
        |    name: "JDK 22+"
        |    check:
        |      command: java
        |      args: [-version]
        |""".stripMargin
    val cfg = DoctorConfig.parse(yaml) match {
      case Right(c)  => c
      case Left(err) => fail(s"parse error: $err")
    }
    assertEquals(cfg.steps.size, 1)
    val s = cfg.steps.head
    assertEquals(s.id, "jdk")
    assertEquals(s.name, Some("JDK 22+"))
    assertEquals(s.check.command, "java")
    assertEquals(s.check.args, Some(List("-version")))
  }

  test("parse: full step with install + success-when + hint") {
    val yaml =
      """steps:
        |  - id: rust-targets
        |    name: "Rust cross-compile targets"
        |    check:
        |      command: rustup
        |      args: [target, list, --installed]
        |      success-when:
        |        stdout-contains: aarch64-apple-darwin
        |    install:
        |      command: rustup
        |      args: [target, add, aarch64-apple-darwin]
        |    hint: "Run: rustup target add aarch64-apple-darwin"
        |""".stripMargin
    val cfg = DoctorConfig.parse(yaml) match {
      case Right(c)  => c
      case Left(err) => fail(s"parse error: $err")
    }
    val s = cfg.steps.head
    assertEquals(s.check.`success-when`.flatMap(_.`stdout-contains`), Some("aarch64-apple-darwin"))
    assertEquals(s.install.map(_.command), Some("rustup"))
    assertEquals(s.install.flatMap(_.args).map(_.head), Some("target"))
    assertEquals(s.hint, Some("Run: rustup target add aarch64-apple-darwin"))
  }

  test("parse: interactive flag on install") {
    val yaml =
      """steps:
        |  - id: jdk
        |    check:
        |      command: java
        |      args: [-version]
        |    install:
        |      command: sdk
        |      args: [install, java, 25.0.2-zulu]
        |      interactive: true
        |""".stripMargin
    val cfg = DoctorConfig.parse(yaml) match {
      case Right(c)  => c
      case Left(err) => fail(s"parse error: $err")
    }
    assertEquals(cfg.steps.head.install.flatMap(_.interactive), Some(true))
  }

  test("parse: every success-when sub-check is optional") {
    val yaml =
      """steps:
        |  - id: x
        |    check:
        |      command: foo
        |      success-when:
        |        exit-code: 0
        |        stderr-matches: 'version "([0-9]+)\.'
        |""".stripMargin
    val cfg = DoctorConfig.parse(yaml).toOption.get
    val sw  = cfg.steps.head.check.`success-when`.get
    assertEquals(sw.`exit-code`, Some(0))
    assertEquals(sw.`stdout-contains`, None)
    assert(sw.`stderr-matches`.exists(_.contains("version")))
  }

  test("parse: empty steps: yields empty list") {
    val cfg = DoctorConfig.parse("steps: []").toOption.get
    assertEquals(cfg.steps, Nil)
  }

  test("parse: malformed YAML returns Left") {
    val r = DoctorConfig.parse("steps:\n  - id: [unbalanced")
    assert(r.isLeft, s"expected Left, got $r")
  }
}
