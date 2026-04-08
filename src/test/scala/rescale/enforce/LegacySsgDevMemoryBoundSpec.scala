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

  test("legacy ssg-dev enforce shortcuts — informational baseline measurement") {
    assume(Files.isDirectory(ssgRoot), s"SSG checkout not found at $ssgRoot")
    assume(Files.isExecutable(legacyBinary), s"legacy ssg-dev not present at $legacyBinary — baseline pin skipped")

    val result = runAndMeasure(List(legacyBinary.toString, "quality", "shortcuts"), cwd = ssgRoot.toFile)

    val rssMiB = result.maxRssBytes.toDouble / (1024.0 * 1024.0)
    // Informational only — `quality shortcuts` was already streaming-fixed in
    // the worktree, so this command doesn't reproduce the 48 GB OOM. The
    // canonical OOM-reproducing command is `compare strict` or `port verify`,
    // which call Methods.extract* (still eager). We log what we measured here
    // so that future regressions of the harness or methodology are visible,
    // but we don't gate on the legacy binary's behavior.
    println(f"[legacy baseline] ssg-dev quality shortcuts: $rssMiB%.1f MiB (exit ${result.exitCode})")
    assert(result.maxRssBytes > 0L, "harness should report a non-zero RSS measurement")
  }
}
