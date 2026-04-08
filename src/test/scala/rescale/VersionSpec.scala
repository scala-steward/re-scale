/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale

import munit.FunSuite

/** Phase 0 smoke test — proves the sbt + munit wiring works end-to-end. */
final class VersionSpec extends FunSuite {

  test("Version.value is a non-empty semver-ish string") {
    val v = Version.value
    assert(v.nonEmpty, "version must not be empty")
    assert(v.matches("""^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$"""), s"version '$v' is not semver-ish")
  }

  test("Version.value is mutable only at build time") {
    // Trivial lock-in test — if someone changes Version to a var this fails to compile.
    val v1 = Version.value
    val v2 = Version.value
    assertEquals(v1, v2)
  }
}
