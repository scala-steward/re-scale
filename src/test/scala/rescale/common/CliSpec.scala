/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.common

import munit.FunSuite

final class CliSpec extends FunSuite {

  test("parses empty args") {
    val a = Cli.parse(Nil)
    assertEquals(a.positional, Nil)
    assertEquals(a.flags, Map.empty[String, String])
  }

  test("parses positional args") {
    val a = Cli.parse(List("foo", "bar", "baz"))
    assertEquals(a.positional, List("foo", "bar", "baz"))
    assertEquals(a.flags, Map.empty[String, String])
  }

  test("parses --flag value form") {
    val a = Cli.parse(List("--module", "ssg-md"))
    assertEquals(a.flag("module"), Some("ssg-md"))
  }

  test("parses --flag=value form") {
    val a = Cli.parse(List("--module=ssg-md"))
    assertEquals(a.flag("module"), Some("ssg-md"))
  }

  test("boolean flag without value") {
    val a = Cli.parse(List("--all"))
    assertEquals(a.hasFlag("all"), true)
    assertEquals(a.flag("all"), Some("true"))
  }

  test("mixed positional + flags") {
    val a = Cli.parse(List("set", "path/to/file", "--status", "pass", "--notes=clean"))
    assertEquals(a.positional, List("set", "path/to/file"))
    assertEquals(a.flag("status"), Some("pass"))
    assertEquals(a.flag("notes"), Some("clean"))
  }

  test("-- terminator treats following args as positional") {
    val a = Cli.parse(List("--status", "pass", "--", "--not-a-flag", "plain"))
    assertEquals(a.flag("status"), Some("pass"))
    assertEquals(a.positional, List("--not-a-flag", "plain"))
  }

  test("requirePositional throws on missing") {
    val a = Cli.parse(List("cmd"))
    intercept[IllegalArgumentException](a.requirePositional(1, "file_path"))
  }

  test("positionalAt returns None out of range") {
    val a = Cli.parse(List("one"))
    assertEquals(a.positionalAt(0), Some("one"))
    assertEquals(a.positionalAt(1), None)
  }

  test("flagOrDefault returns default when missing") {
    val a = Cli.parse(List("--present", "x"))
    assertEquals(a.flagOrDefault("present", "nope"), "x")
    assertEquals(a.flagOrDefault("missing", "fallback"), "fallback")
  }
}
