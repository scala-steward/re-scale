/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale

/** Compile-time version constant. Kept as a plain Scala val so the
  * memory-bound tests can assert on it without invoking any FS2 or
  * Cats Effect machinery.
  */
object Version {
  val value: String = "0.1.5"
}
