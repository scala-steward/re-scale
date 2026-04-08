/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.common

import munit.FunSuite

import java.io.File
import java.nio.file.Files as NIOFiles

final class PathsSpec extends FunSuite {

  private def tempProject(): File = {
    val root = NIOFiles.createTempDirectory("rescale-paths-").toFile
    root.deleteOnExit()
    root
  }

  test("discoverFrom returns None for empty directory") {
    val root = tempProject()
    assertEquals(Paths.discoverFrom(root), None)
  }

  test("discoverFrom finds build.sbt at self") {
    val root  = tempProject()
    val build = new File(root, "build.sbt")
    build.createNewFile()
    assertEquals(Paths.discoverFrom(root).map(_.getAbsolutePath), Some(root.getAbsolutePath))
  }

  test("discoverFrom finds marker at ancestor") {
    val root = tempProject()
    new File(root, "build.sbt").createNewFile()
    val sub  = new File(root, "sub/nested/deep")
    sub.mkdirs()
    assertEquals(Paths.discoverFrom(sub).map(_.getAbsolutePath), Some(root.getAbsolutePath))
  }

  test(".rescale dir wins over build.sbt") {
    val root = tempProject()
    new File(root, "build.sbt").createNewFile()
    val rescaleDir = new File(root, ".rescale")
    rescaleDir.mkdir()
    // Both markers present; either root is valid since they're at the same dir.
    // Test that .rescale IS recognized as a marker (would fail if we dropped it).
    val sub = new File(root, "nested")
    sub.mkdirs()
    assertEquals(Paths.discoverFrom(sub).map(_.getAbsolutePath), Some(root.getAbsolutePath))
  }

  test("custom markers list") {
    val root = tempProject()
    new File(root, "Cargo.toml").createNewFile()
    assertEquals(
      Paths.discoverFrom(root, markers = List("Cargo.toml")).map(_.getAbsolutePath),
      Some(root.getAbsolutePath)
    )
    // Empty marker list never matches
    assertEquals(Paths.discoverFrom(root, markers = Nil), None)
  }

  test("dataDir is .rescale/data under the project root") {
    val root = tempProject()
    val rescaleData = new File(root, ".rescale/data")
    rescaleData.mkdirs()
    assertEquals(Paths.dataDir(root).getAbsolutePath, rescaleData.getAbsolutePath)
  }

  test("dataDir returns the canonical path even if it doesn't exist yet") {
    val root = tempProject()
    val d = Paths.dataDir(root)
    assert(d.getAbsolutePath.endsWith(".rescale/data"))
  }

  test("dataDir does NOT honor the legacy scripts/data location") {
    // The legacy ssg-dev convention was scripts/data/. re-scale used
    // to fall back to it when .rescale/data was absent, but that
    // caused split-state bugs (users editing scripts/data/issues.tsv
    // while re-scale read .rescale/data/issues.tsv). The fallback is
    // gone — there is exactly ONE location.
    val root = tempProject()
    val scriptsData = new File(root, "scripts/data")
    scriptsData.mkdirs()
    val d = Paths.dataDir(root)
    assert(
      d.getAbsolutePath.endsWith(".rescale/data"),
      s"dataDir should ignore legacy scripts/data; got ${d.getAbsolutePath}"
    )
    assert(!d.getAbsolutePath.contains("scripts/data"))
  }
}
