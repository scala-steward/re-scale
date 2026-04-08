/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the streaming StaleStubs detector. Builds a small synthetic
 * codebase + an index, then asserts that suspect comments mentioning
 * indexed identifiers are flagged.
 */
package rescale.enforce

import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class StaleStubsSpec extends CatsEffectSuite {

  private def tempDir(): File = {
    val d = NIOFiles.createTempDirectory("stalestubs-").toFile
    d.deleteOnExit()
    d
  }

  private def writeFile(parent: File, name: String, content: String): File = {
    val f = new File(parent, name)
    val w = new PrintWriter(f)
    try w.write(content)
    finally w.close()
    f.deleteOnExit()
    f
  }

  test("buildIndex collects def/val/class names + SCREAMING_SNAKE constants") {
    val dir = tempDir()
    writeFile(dir, "A.scala",
      """package x
        |object Parser {
        |  val REFERENCES = "ref"
        |  def parseAll: Int = 0
        |}
        |class Helper {
        |  def assist: Int = 0
        |}
        |""".stripMargin
    )
    StaleStubs.buildIndex(List(dir)).map { idx =>
      assert(idx.contains("Parser"), s"idx = $idx")
      assert(idx.contains("REFERENCES"))
      assert(idx.contains("parseAll"))
      assert(idx.contains("Helper"))
      assert(idx.contains("assist"))
    }
  }

  test("scanFile flags a 'not yet ported' comment when the identifier is in the index") {
    val dir = tempDir()
    val mainFile = writeFile(dir, "Main.scala",
      """package x
        |object Main {
        |  def doStuff(): Int = {
        |    // Parser.REFERENCES is not yet ported
        |    0
        |  }
        |}
        |""".stripMargin
    )
    val index = Set("Parser.REFERENCES", "REFERENCES", "Parser")
    StaleStubs.scanFile(mainFile, index).map { hits =>
      assert(hits.nonEmpty, s"expected stale hit; got $hits")
      assert(hits.exists(_.identifier.contains("REFERENCES")))
    }
  }

  test("scanFile does NOT flag when the suspect identifier is unknown") {
    val dir = tempDir()
    val f = writeFile(dir, "X.scala",
      """package x
        |object X {
        |  // SOMETHING_NOT_DEFINED is not yet ported
        |  def y: Int = 0
        |}
        |""".stripMargin
    )
    val emptyIdx = Set.empty[String]
    StaleStubs.scanFile(f, emptyIdx).map { hits =>
      assertEquals(hits, Nil)
    }
  }

  test("ring buffer: identifier on adjacent line still triggers a hit") {
    val dir = tempDir()
    val f = writeFile(dir, "M.scala",
      """package x
        |object M {
        |  // not yet ported
        |  // Parser.REFERENCES would go here
        |  def n: Int = 0
        |}
        |""".stripMargin
    )
    val index = Set("Parser.REFERENCES", "REFERENCES")
    StaleStubs.scanFile(f, index).map { hits =>
      assert(hits.nonEmpty, s"expected hit when identifier is on the next line; got $hits")
    }
  }
}
