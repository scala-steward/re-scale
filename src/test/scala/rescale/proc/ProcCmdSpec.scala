/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for ProcCmd's pure helpers — `parsePsOutput`, `classifyCommand`,
 * `parseKindFilter`. The kill / discover paths shell out and aren't
 * unit-tested here; they're covered by manual smoke testing against
 * real sbt servers.
 */
package rescale.proc

import munit.FunSuite

final class ProcCmdSpec extends FunSuite {

  // -- parsePsOutput --------------------------------------------------

  test("parsePsOutput: parses a typical macOS ps row") {
    val raw =
      """  PID %CPU %MEM ARGS
        | 1234  3.4  1.2 /usr/bin/java -Dfoo=bar -jar /opt/sbt/sbt-launch.jar shell
        | 5678  0.1  0.5 /usr/bin/something else
        |""".stripMargin
    val procs = ProcCmd.parsePsOutput(raw)
    assertEquals(procs.size, 2)
    assertEquals(procs.head.pid, 1234L)
    assertEquals(procs.head.kind, ProcCmd.Kind.Sbt)
    assertEquals(procs.head.pcpu, 3.4)
    assertEquals(procs.head.pmem, 1.2)
    assertEquals(procs(1).pid, 5678L)
  }

  test("parsePsOutput: skips header + blank lines") {
    val raw = """PID %CPU %MEM ARGS
                |
                |""".stripMargin
    assertEquals(ProcCmd.parsePsOutput(raw), Nil)
  }

  // -- classifyCommand -----------------------------------------------

  test("classifyCommand: sbt-launch.jar → Sbt") {
    assertEquals(
      ProcCmd.classifyCommand("/usr/bin/java -jar /opt/sbt/sbt-launch.jar"),
      ProcCmd.Kind.Sbt
    )
  }

  test("classifyCommand: metals → Metals") {
    assertEquals(
      ProcCmd.classifyCommand("/opt/java/bin/java -cp ... org.eclipse.lsp4j.scalameta.metals.Main"),
      ProcCmd.Kind.Metals
    )
  }

  test("classifyCommand: plain java → Java") {
    assertEquals(
      ProcCmd.classifyCommand("/usr/bin/java -jar foo.jar"),
      ProcCmd.Kind.Java
    )
  }

  test("classifyCommand: unrelated → Other") {
    assertEquals(
      ProcCmd.classifyCommand("/bin/zsh -l"),
      ProcCmd.Kind.Other
    )
  }

  // -- parseKindFilter -----------------------------------------------

  test("parseKindFilter: 'all' → empty (no filter)") {
    assertEquals(ProcCmd.parseKindFilter("all"), Set.empty[ProcCmd.Kind])
  }

  test("parseKindFilter: single kind") {
    assertEquals(ProcCmd.parseKindFilter("sbt"), Set(ProcCmd.Kind.Sbt))
  }

  test("parseKindFilter: comma-separated") {
    assertEquals(
      ProcCmd.parseKindFilter("sbt,metals"),
      Set(ProcCmd.Kind.Sbt, ProcCmd.Kind.Metals)
    )
  }

  test("parseKindFilter: unknown kinds are dropped") {
    assertEquals(ProcCmd.parseKindFilter("sbt,bogus"), Set(ProcCmd.Kind.Sbt))
  }
}
