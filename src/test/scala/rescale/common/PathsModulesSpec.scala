/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for Paths.moduleNames — discovery of sub-module roots inside
 * a project. Both code paths (`.rescale/modules.txt` config + filesystem
 * discovery) are exercised against a synthetic temp project.
 */
package rescale.common

import munit.FunSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class PathsModulesSpec extends FunSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("paths-modules-").toFile
    d.deleteOnExit()
    d
  }

  private def mkModule(root: File, name: String): Unit = {
    val src = new File(root, s"$name/src/main/scala")
    src.mkdirs()
    src.deleteOnExit()
  }

  test("moduleNames: discovers immediate children with src/main/scala") {
    val root = tempDir()
    mkModule(root, "alpha")
    mkModule(root, "beta")
    mkModule(root, "gamma")
    new File(root, "docs").mkdirs() // not a module
    val mods = Paths.moduleNames(root)
    assertEquals(mods, List("alpha", "beta", "gamma"))
  }

  test("moduleNames: returns empty when no children have src/main/scala") {
    val root = tempDir()
    new File(root, "docs").mkdirs()
    new File(root, "scripts").mkdirs()
    assertEquals(Paths.moduleNames(root), Nil)
  }

  test("moduleNames: .rescale/modules.txt overrides discovery") {
    val root = tempDir()
    mkModule(root, "alpha")
    mkModule(root, "beta")
    val cfgDir = new File(root, ".rescale")
    cfgDir.mkdirs()
    val cfg = new File(cfgDir, "modules.txt")
    val w   = new PrintWriter(cfg)
    try {
      w.println("# explicit module list")
      w.println("alpha")
      w.println("custom-name")  // not on disk, but trusted from config
    } finally w.close()
    cfg.deleteOnExit()
    assertEquals(Paths.moduleNames(root), List("alpha", "custom-name"))
  }
}
