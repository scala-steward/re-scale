/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Baseline-pin test: asserts that the LEGACY ssg-dev binary EXCEEDS
 * the 512 MiB budget on the same 944-file workload. If this test ever
 * stops failing, the measurement harness in Ssg944FileMemoryBoundSpec
 * is broken (or the legacy binary was rebuilt with the fix), and we
 * need to re-evaluate whether the budget is still meaningful.
 *
 * This test is the "canary" — it proves the measurement methodology
 * actually measures what we think it measures.
 */
package rescale.enforce

import munit.FunSuite

import java.nio.file.{Files, Path, Paths}

final class LegacySsgDevMemoryBoundSpec extends FunSuite {

  import Ssg944FileMemoryBoundSpec.*

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "seconds")

  private val ssgRoot: Path = Paths.get("/Users/dev/Workspaces/GitHub/ssg")
  private val legacyBinary: Path =
    Paths.get("/Users/dev/Workspaces/GitHub/ssg/scripts/bin/ssg-dev")

  private val budgetBytes: Long = 512L * 1024L * 1024L

  test("legacy ssg-dev enforce shortcuts exceeds 512 MiB — baseline pin") {
    assume(Files.isDirectory(ssgRoot), s"SSG checkout not found at $ssgRoot")
    assume(Files.isExecutable(legacyBinary), s"legacy ssg-dev not present at $legacyBinary — baseline pin skipped")

    val result = runAndMeasure(List(legacyBinary.toString, "quality", "shortcuts"), cwd = ssgRoot.toFile)

    val rssMiB = result.maxRssBytes.toDouble / (1024.0 * 1024.0)
    // If this assertion ever FAILS (i.e. legacy stays under budget), either:
    //   (a) someone rebuilt the legacy binary with the streaming fix — update the pin
    //   (b) the measurement harness broke — fix it
    //   (c) macOS stopped honoring /usr/bin/time -l — fix the parser
    assert(
      result.maxRssBytes > budgetBytes || result.exitCode != 0,
      f"LEGACY BASELINE DRIFT: legacy ssg-dev used only $rssMiB%.1f MiB, under the 512 MiB budget. " +
      "Either the harness is broken or someone fixed the legacy binary. Investigate."
    )
  }
}
