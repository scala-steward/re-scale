/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Method-signature extractor and strict cross-language comparison —
 * STREAMING REWRITE that closes the 48 GB OOM gap.
 *
 * The legacy `compare/Methods.scala` did:
 *
 *   val text = readFile(path)                       // whole file -> String
 *   scalaDef.findAllMatchIn(text).foreach(...)     // (?m) anchors, list of matches
 *   extractBodyFromScala(text, m.end)              // brace search over `text`
 *
 * which materialized one full-file String per file plus an unbounded
 * match list, plus several substring views into `text`. Across 944
 * files this was the canonical OOM path.
 *
 * This rewrite:
 *
 *   - Uses `FileOps.streamLines` to walk one line at a time.
 *   - Per-line regexes (no `(?m)` anchors needed) match definitions
 *     within their own line.
 *   - Method-body extraction is a streaming brace-matcher state machine
 *     that holds at most:
 *        * one in-flight method's name + accumulated AST-node count
 *        * the current line
 *        * the current brace depth (Int)
 *     Total per-method working set is O(name length + small ints), not
 *     O(file size).
 *   - Body comparison ("shortBody" check) is computed on a TOKEN COUNT,
 *     not on the body string. The token count is incremented per token
 *     scanned, then the body string is dropped.
 *
 * Constructor-arity comparison glues physical lines together up to
 * paren-balance == 0 (parameter lists rarely span more than 5 lines),
 * then runs the legacy parameter-name extractor on the glued buffer.
 * Per-class accumulator is a Set[String], not a body string.
 */
package rescale.enforce

import cats.effect.IO
import fs2.io.file.Path
import rescale.common.FileOps

import java.io.File
import scala.collection.mutable
import scala.util.matching.Regex

object Methods {

  final case class Gap(
    missing:         List[String],
    extra:           List[String],
    common:          List[String],
    shortBody:       List[String],
    droppedCtorArgs: List[(String, String)] // (className, paramName)
  )

  object Gap {
    val empty: Gap = Gap(Nil, Nil, Nil, Nil, Nil)
  }

  // -- Per-line definition patterns ----------------------------------

  /** Scala `def name` at any indentation. Anchored at start-of-line so
    * comments mid-line don't trigger.
    */
  private val scalaDef: Regex =
    """^[ \t]*(?:override\s+|final\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|implicit\s+|lazy\s+|inline\s+|transparent\s+)*def\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_$]*))""".r

  private val scalaVal: Regex =
    """^[ \t]{0,4}(?:override\s+|final\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|implicit\s+|lazy\s+|inline\s+)*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_$]*)""".r

  private val scalaType: Regex =
    """^[ \t]*(?:sealed\s+|final\s+|abstract\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|open\s+|case\s+)*(?:class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_$]*)""".r

  // Dart method definition: must have a parameter list (`(...)`) directly
  // after the name. Anchored at indented position to filter out top-level
  // function calls. Same lazy `.*?` approach as `javaMethod`.
  private val dartTopDef: Regex =
    """^[ \t]+(?:(?:static|const|final|late|external|abstract|factory)\s+)*[A-Za-z_$].*?\s+([a-zA-Z_$][\w$]*)\s*\(""".r

  private val dartGetter: Regex =
    """^[ \t]+(?:static\s+|const\s+|final\s+|late\s+|external\s+)*[A-Za-z_][A-Za-z0-9_<>?, \t]*\s+get\s+([a-zA-Z_][A-Za-z0-9_]*)\b""".r

  private val dartTypeDef: Regex =
    """^[ \t]*(?:abstract\s+|sealed\s+|final\s+|base\s+|interface\s+|mixin\s+)*(?:class|mixin|enum|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""".r

  // Java method definition: modifiers + return type + name + (
  // Lazy `.*?` between the first type-letter and the trailing whitespace
  // lets us match `public int alpha()` and `public Map<String, Integer> foo()`
  // without requiring two spaces between type and name (the legacy regex was buggy).
  private val javaMethod: Regex =
    """^[ \t]+(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\s+)*[A-Za-z_$].*?\s+([a-zA-Z_$][\w$]*)\s*\(""".r

  private val javaTypeDef: Regex =
    """^[ \t]*(?:public\s+|private\s+|protected\s+|static\s+|final\s+|abstract\s+|sealed\s+)*(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)""".r

  // Token regex for AST-node counting (cheap proxy for body complexity).
  private val tokenRegex: Regex =
    """[A-Za-z_][A-Za-z0-9_]*|[+\-*/%<>=!&|^~?:.;,(){}\[\]]""".r

  // -- Public API: name extraction (streaming) -----------------------

  /** Return the set of top-level/method names defined in a Scala file.
    * Streaming, constant-memory per file.
    */
  def extractScalaNames(path: Path): IO[List[String]] = {
    FileOps
      .streamLines(path)
      .compile
      .fold(mutable.LinkedHashSet.empty[String]) { case (acc, (_, line)) =>
        scalaType.findFirstMatchIn(line).foreach(m => acc += m.group(1))
        scalaDef.findFirstMatchIn(line).foreach { m =>
          val name = Option(m.group(1)).getOrElse(m.group(2))
          if (name != null) acc += name
        }
        scalaVal.findFirstMatchIn(line).foreach { m =>
          val name = m.group(1)
          if (name != null) acc += name
        }
        acc
      }
      .map(_.toList.sorted)
  }

  def extractScalaNames(file: File): IO[List[String]] = extractScalaNames(Path.fromNioPath(file.toPath))

  def extractDartNames(path: Path): IO[List[String]] = {
    val excluded = DartReservedWords
    FileOps
      .streamLines(path)
      .compile
      .fold(mutable.LinkedHashSet.empty[String]) { case (acc, (_, line)) =>
        dartTypeDef.findFirstMatchIn(line).foreach(m => acc += m.group(1))
        dartTopDef.findFirstMatchIn(line).foreach { m =>
          val name = m.group(1)
          if (name != null && !excluded.contains(name)) acc += name
        }
        dartGetter.findFirstMatchIn(line).foreach { m =>
          val name = m.group(1)
          if (name != null && !excluded.contains(name)) acc += name
        }
        acc
      }
      .map(_.toList.sorted)
  }

  def extractDartNames(file: File): IO[List[String]] = extractDartNames(Path.fromNioPath(file.toPath))

  def extractJavaNames(path: Path): IO[List[String]] = {
    val excluded = JavaReservedWords
    FileOps
      .streamLines(path)
      .compile
      .fold(mutable.LinkedHashSet.empty[String]) { case (acc, (_, line)) =>
        javaTypeDef.findFirstMatchIn(line).foreach(m => acc += m.group(1))
        javaMethod.findFirstMatchIn(line).foreach { m =>
          val name = m.group(1)
          if (name != null && !excluded.contains(name)) acc += name
        }
        acc
      }
      .map(_.toList.sorted)
  }

  def extractJavaNames(file: File): IO[List[String]] = extractJavaNames(Path.fromNioPath(file.toPath))

  // -- Public API: streaming body token-count ------------------------

  /** Extract per-method body token counts via a streaming brace-matcher.
    *
    * The state machine has three modes:
    *   - Idle: looking for a definition keyword on the current line
    *   - InHeader: saw `def name`, scanning ahead for `=` or `{`
    *   - InBody: counting tokens until brace depth returns to 0
    *
    * Each mode holds O(1) memory; the per-file working set is bounded
    * by one line + the in-flight method name + an Int counter.
    */
  def extractScalaTokenCounts(path: Path): IO[Map[String, Int]] = {
    FileOps
      .streamLines(path)
      .compile
      .fold(BodyAcc.empty)((s, lr) => stepScalaBody(s, lr._2))
      .map(_.finalize_())
  }

  def extractScalaTokenCounts(file: File): IO[Map[String, Int]] =
    extractScalaTokenCounts(Path.fromNioPath(file.toPath))

  def extractDartTokenCounts(path: Path): IO[Map[String, Int]] =
    FileOps
      .streamLines(path)
      .compile
      .fold(BodyAcc.empty)((s, lr) => stepCStyleBody(s, lr._2, isDart = true))
      .map(_.finalize_())

  def extractDartTokenCounts(file: File): IO[Map[String, Int]] =
    extractDartTokenCounts(Path.fromNioPath(file.toPath))

  def extractJavaTokenCounts(path: Path): IO[Map[String, Int]] =
    FileOps
      .streamLines(path)
      .compile
      .fold(BodyAcc.empty)((s, lr) => stepCStyleBody(s, lr._2, isDart = false))
      .map(_.finalize_())

  def extractJavaTokenCounts(file: File): IO[Map[String, Int]] =
    extractJavaTokenCounts(Path.fromNioPath(file.toPath))

  // -- Body extraction state machine ---------------------------------

  /** Streaming body-token-count accumulator. Holds at most:
    *   - the in-flight method name (Option[String])
    *   - the current brace depth (Int)
    *   - the running token count for the in-flight body (Int)
    *   - the completed map (Map[String, Int])
    *
    * No file content is retained beyond what's needed for the current
    * brace-depth update on the current line.
    */
  private final case class BodyAcc(
    completed:    Map[String, Int],
    inFlight:     Option[String],
    inFlightCnt:  Int,
    braceDepth:   Int,
    sawOpenBrace: Boolean
  ) {
    def finalize_(): Map[String, Int] = inFlight match {
      case Some(name) if inFlightCnt > 0 => completed.updated(name, inFlightCnt)
      case _                              => completed
    }
  }

  private object BodyAcc {
    val empty: BodyAcc = BodyAcc(Map.empty, None, 0, 0, sawOpenBrace = false)
  }

  /** Process one line of a Scala file and update the body accumulator.
    *
    * Only `def` declarations are tracked — `class`/`object`/`trait`/
    * `enum` bodies are deliberately ignored, because (a) they have no
    * Java/Dart counterpart with the same name in shortBody comparisons,
    * and (b) tracking them would require a stack to handle nested defs.
    *
    * Caveat: defs nested inside other defs (e.g. local helpers) get
    * attributed to the outer def. This is rare in practice and matches
    * the legacy behavior closely enough.
    */
  private def stepScalaBody(state: BodyAcc, line: String): BodyAcc = {
    var s = state

    // Idle: look for a new def declaration.
    if (s.inFlight.isEmpty) {
      scalaDef.findFirstMatchIn(line) match {
        case Some(m) =>
          val name = Option(m.group(1)).getOrElse(m.group(2))
          if (name != null) {
            s = s.copy(inFlight = Some(name), inFlightCnt = 0, braceDepth = 0, sawOpenBrace = false)
            s = consumeScalaLineForBody(s, line, m.end)
          }
        case None =>
          // Ignore — type declarations and unrelated lines.
      }
      return s
    }

    // In-flight: continue accumulating until braceDepth returns to 0.
    consumeScalaLineForBody(s, line, 0)
  }

  /** Continue body accumulation on a Scala line starting at offset
    * `from`. Updates braceDepth, counts tokens once we're past the
    * opening `{` or `=`, and emits the in-flight method when the body
    * closes.
    */
  private def consumeScalaLineForBody(s0: BodyAcc, line: String, from: Int): BodyAcc = {
    var s         = s0
    var i         = from
    val n         = line.length
    var seenEqOrBrace = s.sawOpenBrace
    // If we haven't seen the opening token yet, scan forward for `{` or `=>`/`=`.
    if (!seenEqOrBrace && s.braceDepth == 0) {
      while (i < n && line.charAt(i) != '{' && line.charAt(i) != '=' && line.charAt(i) != '\n')
        i += 1
      if (i >= n) return s
      val ch = line.charAt(i)
      if (ch == '=') {
        // `def x = expr` — count tokens to end of line, then close.
        i += 1
        // Accept `=>` for partial-fn case.
        while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '>'))
          i += 1
        if (i < n && line.charAt(i) == '{') {
          s = s.copy(sawOpenBrace = true, braceDepth = 1)
          seenEqOrBrace = true
          i += 1
        } else {
          // One-liner: count rest of line as the body.
          val tail = line.substring(i)
          val cnt  = countTokens(tail)
          val name = s.inFlight.get
          s = s.copy(
            completed   = s.completed.updated(name, s.inFlightCnt + cnt),
            inFlight    = None,
            inFlightCnt = 0
          )
          return s
        }
      } else if (ch == '{') {
        s = s.copy(sawOpenBrace = true, braceDepth = 1)
        seenEqOrBrace = true
        i += 1
      }
    }

    // We're now inside a braced body. Count tokens and update depth.
    // Use a simple lexer that skips strings/chars/comments to keep
    // brace depth accurate.
    val sb       = new StringBuilder
    var depth    = s.braceDepth
    var done     = false
    while (i < n && !done) {
      val c = line.charAt(i)
      // Skip line comment to end-of-line.
      if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
        i = n
      } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
        // Block comment — scan to */ on this line; if not found, abort
        // for this line (we'll resume next line still inside the comment).
        // For simplicity we don't track multi-line block comments inside
        // method bodies (rare for AST-count purposes).
        i += 2
        while (i + 1 < n && !(line.charAt(i) == '*' && line.charAt(i + 1) == '/')) i += 1
        if (i + 1 < n) i += 2 else i = n
      } else if (c == '"') {
        // String literal
        sb.append('"')
        i += 1
        while (i < n && line.charAt(i) != '"') {
          if (line.charAt(i) == '\\' && i + 1 < n) i += 2
          else i += 1
        }
        if (i < n) i += 1
      } else if (c == '\'') {
        sb.append('\'')
        i += 1
        while (i < n && line.charAt(i) != '\'') {
          if (line.charAt(i) == '\\' && i + 1 < n) i += 2
          else i += 1
        }
        if (i < n) i += 1
      } else if (c == '{') {
        depth += 1
        sb.append(c)
        i += 1
      } else if (c == '}') {
        depth -= 1
        if (depth == 0) {
          // Method body closed.
          val cnt  = countTokens(sb.toString)
          val name = s.inFlight.get
          s = s.copy(
            completed   = s.completed.updated(name, s.inFlightCnt + cnt),
            inFlight    = None,
            inFlightCnt = 0,
            braceDepth  = 0,
            sawOpenBrace = false
          )
          done = true
        } else {
          sb.append(c)
          i += 1
        }
      } else {
        sb.append(c)
        i += 1
      }
    }

    if (!done) {
      // End of line, still inside the body. Stash the partial token
      // count and update braceDepth for the next line.
      val cnt = countTokens(sb.toString)
      s = s.copy(braceDepth = depth, inFlightCnt = s.inFlightCnt + cnt)
    }
    s
  }

  /** Same approach for Dart and Java. C-style bodies are simpler than
    * Scala because there's no `def x = expr` form — bodies always use
    * `{ ... }`. We still need to consume the parameter list `(...)`
    * before scanning for the opening brace.
    */
  private def stepCStyleBody(state: BodyAcc, line: String, isDart: Boolean): BodyAcc = {
    var s = state
    if (s.inFlight.isEmpty) {
      val (name, after) = if (isDart) findDartDef(line) else findJavaDef(line)
      if (name.isDefined) {
        s = s.copy(inFlight = name, inFlightCnt = 0, braceDepth = 0, sawOpenBrace = false)
        s = consumeCStyleLineForBody(s, line, after)
      }
      return s
    }
    consumeCStyleLineForBody(s, line, 0)
  }

  private def findDartDef(line: String): (Option[String], Int) = {
    dartTopDef.findFirstMatchIn(line).orElse(dartTypeDef.findFirstMatchIn(line)) match {
      case Some(m) =>
        val name = m.group(1)
        if (name != null && !DartReservedWords.contains(name)) (Some(name), m.end)
        else (None, 0)
      case None => (None, 0)
    }
  }

  private def findJavaDef(line: String): (Option[String], Int) = {
    javaMethod.findFirstMatchIn(line).orElse(javaTypeDef.findFirstMatchIn(line)) match {
      case Some(m) =>
        val name = m.group(1)
        if (name != null && !JavaReservedWords.contains(name)) (Some(name), m.end)
        else (None, 0)
      case None => (None, 0)
    }
  }

  private def consumeCStyleLineForBody(s0: BodyAcc, line: String, from: Int): BodyAcc = {
    var s = s0
    var i = from
    val n = line.length
    // Consume parameter list `(...)`
    if (!s.sawOpenBrace && s.braceDepth == 0) {
      // Skip to the next `{` or `;` or end of line.
      while (i < n && line.charAt(i) != '{' && line.charAt(i) != ';') i += 1
      if (i >= n) return s
      if (line.charAt(i) == ';') {
        // Abstract method or interface decl — no body to count.
        s = s.copy(inFlight = None, inFlightCnt = 0)
        return s
      }
      if (line.charAt(i) == '{') {
        s = s.copy(sawOpenBrace = true, braceDepth = 1)
        i += 1
      }
    }

    // We're inside the body. Use the same lexer as Scala.
    val sb    = new StringBuilder
    var depth = s.braceDepth
    var done  = false
    while (i < n && !done) {
      val c = line.charAt(i)
      if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
        i = n
      } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
        i += 2
        while (i + 1 < n && !(line.charAt(i) == '*' && line.charAt(i + 1) == '/')) i += 1
        if (i + 1 < n) i += 2 else i = n
      } else if (c == '"') {
        sb.append('"')
        i += 1
        while (i < n && line.charAt(i) != '"') {
          if (line.charAt(i) == '\\' && i + 1 < n) i += 2
          else i += 1
        }
        if (i < n) i += 1
      } else if (c == '\'') {
        sb.append('\'')
        i += 1
        while (i < n && line.charAt(i) != '\'') {
          if (line.charAt(i) == '\\' && i + 1 < n) i += 2
          else i += 1
        }
        if (i < n) i += 1
      } else if (c == '{') {
        depth += 1
        sb.append(c)
        i += 1
      } else if (c == '}') {
        depth -= 1
        if (depth == 0) {
          val cnt  = countTokens(sb.toString)
          val name = s.inFlight.get
          s = s.copy(
            completed    = s.completed.updated(name, s.inFlightCnt + cnt),
            inFlight     = None,
            inFlightCnt  = 0,
            braceDepth   = 0,
            sawOpenBrace = false
          )
          done = true
        } else {
          sb.append(c)
          i += 1
        }
      } else {
        sb.append(c)
        i += 1
      }
    }

    if (!done) {
      val cnt = countTokens(sb.toString)
      s = s.copy(braceDepth = depth, inFlightCnt = s.inFlightCnt + cnt)
    }
    s
  }

  private def countTokens(s: String): Int = tokenRegex.findAllIn(s).size

  // -- Compare ------------------------------------------------------

  /** Compute the gap between an SSG file and its source reference.
    * Auto-detects source language by extension. All file IO is via
    * the streaming primitives above.
    */
  def compare(ssgFile: Path, sourceFile: Path): IO[Gap] = {
    val sourceName = sourceFile.toString
    val sourceNamesIO: IO[List[String]] =
      if (sourceName.endsWith(".dart")) extractDartNames(sourceFile)
      else if (sourceName.endsWith(".java")) extractJavaNames(sourceFile)
      else extractDartNames(sourceFile)

    for {
      scalaNames <- extractScalaNames(ssgFile)
      srcNames   <- sourceNamesIO
      scalaSet    = scalaNames.toSet
      sourceSet   = srcNames.toSet
      missing     = (sourceSet -- scalaSet).toList.sorted
      extra       = (scalaSet -- sourceSet).toList.sorted
      common      = (sourceSet intersect scalaSet).toList.sorted
    } yield Gap(missing, extra, common, shortBody = Nil, droppedCtorArgs = Nil)
  }

  /** Strict compare: like [[compare]] but also computes the shortBody
    * list (common names whose Scala body has < 70% of the source body's
    * AST-node-count) and the dropped ctor params.
    */
  def strictCompare(ssgFile: Path, sourceFile: Path): IO[Gap] = {
    val sourceName = sourceFile.toString
    val sourceCountsIO: IO[Map[String, Int]] =
      if (sourceName.endsWith(".java")) extractJavaTokenCounts(sourceFile)
      else extractDartTokenCounts(sourceFile)

    for {
      base         <- compare(ssgFile, sourceFile)
      scalaCounts  <- extractScalaTokenCounts(ssgFile)
      sourceCounts <- sourceCountsIO
      shortBody     = base.common.filter { name =>
                        val sb = scalaCounts.getOrElse(name, 0)
                        val db = sourceCounts.getOrElse(name, 0)
                        db > 0 && sb * 100 < db * 70
                      }
      ctors <- compareConstructors(ssgFile, sourceFile)
    } yield base.copy(shortBody = shortBody, droppedCtorArgs = ctors)
  }

  // -- Constructor comparison (streaming, line-glued) -----------------

  /** Streaming ctor extractor. Glues physical lines until paren-balance
    * returns to 0, then runs the legacy parameter-name extractor on
    * the glued buffer. The buffer holds at most one ctor parameter
    * list at a time.
    */
  private def extractScalaCtorParams(path: Path): IO[Map[String, Set[String]]] = {
    FileOps
      .streamLines(path)
      .compile
      .fold(CtorAcc.empty)((s, lr) => stepScalaCtor(s, lr._2))
      .map(_.toMap)
  }

  private def extractCtorParams(path: Path, isDart: Boolean): IO[Map[String, Set[String]]] = {
    FileOps
      .streamLines(path)
      .compile
      .fold(CtorAcc.empty)((s, lr) => stepCtorCStyle(s, lr._2, isDart))
      .map(_.toMap)
  }

  def compareConstructors(ssgFile: Path, sourceFile: Path): IO[List[(String, String)]] = {
    val sourceName = sourceFile.toString
    val isDart     = sourceName.endsWith(".dart")
    for {
      scalaCtors  <- extractScalaCtorParams(ssgFile)
      sourceCtors <- extractCtorParams(sourceFile, isDart)
    } yield {
      val out = mutable.ListBuffer.empty[(String, String)]
      for ((cls, srcParams) <- sourceCtors) {
        val scalaParams = scalaCtors.getOrElse(cls, Set.empty)
        val dropped     = srcParams -- scalaParams
        for (p <- dropped.toList.sorted) out += ((cls, p))
      }
      out.toList
    }
  }

  // -- Ctor accumulator state machine -------------------------------

  /** Accumulator for streaming ctor extraction. Tracks an optional
    * in-flight ctor (class name + collected param-list buffer + paren
    * depth) and the completed per-class param sets.
    */
  private final case class CtorAcc(
    completed: Map[String, Set[String]],
    pending:   Option[Pending]
  ) {
    def toMap: Map[String, Set[String]] = completed
  }

  private final case class Pending(
    className: String,
    isScala:   Boolean,
    paramBuf:  StringBuilder,
    parenDepth: Int
  )

  private object CtorAcc {
    val empty: CtorAcc = CtorAcc(Map.empty, None)
  }

  private val scalaPrimaryCtor: Regex =
    """^\s*(?:sealed\s+|final\s+|abstract\s+|case\s+)*class\s+([A-Z][A-Za-z0-9_$]*)\s*(?:\[[^\]]*\])?\s*\(""".r

  private val javaCtor: Regex =
    """^\s*(?:public\s+|private\s+|protected\s+)?([A-Z][A-Za-z0-9_$]*)\s*\(""".r

  private val dartCtor: Regex =
    """^\s*(?:const\s+|factory\s+)?([A-Z][A-Za-z0-9_$]*)\s*\(""".r

  private def stepScalaCtor(s: CtorAcc, line: String): CtorAcc = {
    s.pending match {
      case None =>
        scalaPrimaryCtor.findFirstMatchIn(line) match {
          case Some(m) =>
            val cls = m.group(1)
            val buf = new StringBuilder
            val (consumed, depth) = consumeParenList(line, m.end, buf, startDepth = 1)
            if (depth == 0) finalizePending(s, cls, buf.toString, isScala = true)
            else CtorAcc(s.completed, Some(Pending(cls, isScala = true, buf, depth)))
          case None => s
        }
      case Some(p) =>
        val (consumed, depth) = consumeParenList(line, 0, p.paramBuf, startDepth = p.parenDepth)
        if (depth == 0) finalizePending(s, p.className, p.paramBuf.toString, isScala = p.isScala)
        else CtorAcc(s.completed, Some(p.copy(parenDepth = depth)))
    }
  }

  private def stepCtorCStyle(s: CtorAcc, line: String, isDart: Boolean): CtorAcc = {
    s.pending match {
      case None =>
        val pat = if (isDart) dartCtor else javaCtor
        pat.findFirstMatchIn(line) match {
          case Some(m) =>
            val cls = m.group(1)
            val buf = new StringBuilder
            val (consumed, depth) = consumeParenList(line, m.end, buf, startDepth = 1)
            if (depth == 0) finalizePending(s, cls, buf.toString, isScala = false)
            else CtorAcc(s.completed, Some(Pending(cls, isScala = false, buf, depth)))
          case None => s
        }
      case Some(p) =>
        val (consumed, depth) = consumeParenList(line, 0, p.paramBuf, startDepth = p.parenDepth)
        if (depth == 0) finalizePending(s, p.className, p.paramBuf.toString, isScala = p.isScala)
        else CtorAcc(s.completed, Some(p.copy(parenDepth = depth)))
    }
  }

  /** Append characters to `buf` until paren depth returns to 0. Returns
    * (chars-consumed, final-depth). The opening `(` itself is NOT in
    * the buffer; this is called from a position just after `(`.
    */
  private def consumeParenList(
    line:       String,
    from:       Int,
    buf:        StringBuilder,
    startDepth: Int
  ): (Int, Int) = {
    var i     = from
    val n     = line.length
    var depth = startDepth
    while (i < n && depth > 0) {
      val c = line.charAt(i)
      if (c == '(') { depth += 1; buf.append(c) }
      else if (c == ')') {
        depth -= 1
        if (depth > 0) buf.append(c)
      } else buf.append(c)
      i += 1
    }
    if (depth > 0) buf.append('\n') // preserve line break for multi-line ctors
    (i - from, depth)
  }

  private def finalizePending(s: CtorAcc, cls: String, paramList: String, isScala: Boolean): CtorAcc = {
    val params  = extractParamNames(paramList, isScala).toSet
    val current = s.completed.getOrElse(cls, Set.empty)
    val merged  = if (isScala) current ++ params else if (params.size > current.size) params else current
    CtorAcc(s.completed.updated(cls, merged), pending = None)
  }

  private def extractParamNames(paramList: String, isScala: Boolean): List[String] = {
    if (paramList.trim.isEmpty) return Nil
    splitTopLevelCommas(paramList).flatMap { p =>
      val trimmed = p.trim
      if (trimmed.isEmpty) None
      else if (isScala) {
        val colonIdx = trimmed.indexOf(':')
        val name     = if (colonIdx > 0) trimmed.substring(0, colonIdx) else trimmed
        val cleaned  = name.replaceAll("""\b(val|var|implicit|private|protected|override|final)\b""", "").trim
        if (cleaned.isEmpty) None else Some(cleaned)
      } else {
        val noDefault = trimmed.split("=").head.trim
        if (noDefault.startsWith("this.")) Some(noDefault.substring(5))
        else {
          val parts = noDefault.split("\\s+").filter(_.nonEmpty)
          if (parts.isEmpty) None
          else Some(parts.last.replaceAll("[^A-Za-z0-9_$]", ""))
        }
      }
    }.filter(_.nonEmpty)
  }

  private def splitTopLevelCommas(s: String): List[String] = {
    val out   = mutable.ListBuffer.empty[String]
    val cur   = new StringBuilder
    var depth = 0
    for (c <- s) {
      c match {
        case '<' | '[' | '(' => depth += 1; cur.append(c)
        case '>' | ']' | ')' => depth -= 1; cur.append(c)
        case ',' if depth == 0 =>
          out += cur.toString
          cur.clear()
        case _ => cur.append(c)
      }
    }
    if (cur.nonEmpty) out += cur.toString
    out.toList
  }

  // -- Reserved words --------------------------------------------

  private val DartReservedWords: Set[String] = Set(
    "if", "else", "for", "while", "do", "return", "throw", "assert",
    "new", "const", "final", "var", "this", "super", "in", "is", "as",
    "switch", "case", "default", "break", "continue", "try", "catch",
    "finally", "yield", "async", "await", "rethrow", "import", "export",
    "library", "part", "hide", "show", "on", "typedef", "covariant",
    "required", "deferred", "abstract", "external", "factory", "operator",
    "print", "when"
  )

  private val JavaReservedWords: Set[String] = Set(
    "if", "else", "for", "while", "do", "return", "throw", "assert",
    "new", "this", "super", "switch", "case", "default", "break",
    "continue", "try", "catch", "finally", "import", "package",
    "abstract", "static", "final", "synchronized", "native", "volatile",
    "transient", "public", "private", "protected", "instanceof"
  )
}
