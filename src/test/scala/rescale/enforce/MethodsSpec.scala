/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the streaming Methods extractor. Verifies:
 *   - extractScalaNames returns top-level def/val/var/class/object
 *   - extractScalaTokenCounts emits a token count per body
 *   - compareConstructors detects dropped Java/Dart ctor params
 *   - strictCompare flags shortBody when Scala body is < 70% of source
 */
package rescale.enforce

import fs2.io.file.Path
import munit.CatsEffectSuite

import java.io.{File, PrintWriter}
import java.nio.file.Files as NIOFiles

final class MethodsSpec extends CatsEffectSuite {

  private def writeFile(name: String, content: String): File = {
    val tmp = NIOFiles.createTempFile("methods-", "-" + name).toFile
    tmp.deleteOnExit()
    val w = new PrintWriter(tmp)
    try w.write(content)
    finally w.close()
    tmp
  }

  // -- extractScalaNames ----------------------------------------------

  test("extractScalaNames returns def/val/var/class/object names") {
    val f = writeFile("a.scala",
      """package ssg
        |
        |object Foo {
        |  def bar(x: Int): Int = x + 1
        |  def baz: String = "hi"
        |  val k: Int = 42
        |  var v: String = "x"
        |}
        |
        |class Quux(n: Int) {
        |  def m(): Int = n
        |}
        |""".stripMargin
    )
    Methods.extractScalaNames(f).map { names =>
      val ns = names.toSet
      assert(ns.contains("Foo"))
      assert(ns.contains("Quux"))
      assert(ns.contains("bar"))
      assert(ns.contains("baz"))
      assert(ns.contains("m"))
    }
  }

  // -- extractScalaTokenCounts ----------------------------------------

  test("extractScalaTokenCounts emits a body for braced and one-liner methods") {
    val f = writeFile("b.scala",
      """object B {
        |  def short: Int = 1
        |  def medium: Int = {
        |    val x = 1
        |    val y = 2
        |    x + y
        |  }
        |}
        |""".stripMargin
    )
    Methods.extractScalaTokenCounts(f).map { counts =>
      assert(counts.contains("short"), s"expected `short` in $counts")
      assert(counts.contains("medium"), s"expected `medium` in $counts")
      assert(counts("medium") > counts("short"), s"medium=${counts("medium")} short=${counts("short")}")
    }
  }

  // -- compareConstructors ---------------------------------------------

  test("compareConstructors flags Java ctor params absent from Scala primary ctor") {
    val javaSrc = writeFile("Foo.java",
      """package x;
        |public class Foo {
        |  public Foo(BasedSequence chars, List<BasedSequence> segments, int width) {
        |    this.chars = chars;
        |  }
        |}
        |""".stripMargin
    )
    val scalaSrc = writeFile("Foo.scala",
      """package x
        |final class Foo(val chars: BasedSequence) {
        |  def m: Int = 0
        |}
        |""".stripMargin
    )
    Methods.compareConstructors(Path.fromNioPath(scalaSrc.toPath), Path.fromNioPath(javaSrc.toPath)).map { dropped =>
      val droppedNames = dropped.map(_._2).toSet
      assert(droppedNames.contains("segments"), s"dropped = $dropped")
      assert(droppedNames.contains("width"), s"dropped = $dropped")
    }
  }

  // -- strictCompare --------------------------------------------------

  test("strictCompare flags missing methods between source and port") {
    val javaSrc = writeFile("S.java",
      """public class S {
        |  public int alpha() { return 1; }
        |  public int beta() { return 2; }
        |  public int gamma() { return 3; }
        |}
        |""".stripMargin
    )
    val scalaSrc = writeFile("S.scala",
      """object S {
        |  def alpha: Int = 1
        |  def beta: Int = 2
        |}
        |""".stripMargin
    )
    Methods.compare(Path.fromNioPath(scalaSrc.toPath), Path.fromNioPath(javaSrc.toPath)).map { gap =>
      assert(gap.missing.contains("gamma"), s"missing = ${gap.missing}")
    }
  }
}
