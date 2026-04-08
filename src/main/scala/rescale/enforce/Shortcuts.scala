/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Shortcut / stub / shim detector — Phase 4 of the re-scale plan.
 *
 * Scans a Scala source file for textual patterns that indicate shortcut
 * implementations. The pattern set is deliberately broad: an agent
 * routing around one marker (e.g. "deferred" instead of "TODO") falls
 * into another.
 *
 * Patterns are classified by line context so legitimate identifiers are
 * not flagged. "Always" rules fire anywhere on a line. "CommentOnly"
 * rules fire only when the line is a comment.
 *
 * Streaming: every scan walks the file via FS2 `FileOps.streamLines`,
 * one line at a time. Peak memory per file is bounded by one line,
 * not the file size — the legacy `(?m)^...$` regex over the whole
 * file string is gone.
 *
 * Pattern set is the union of:
 *   - 14 originals from the legacy ssg-dev `Shortcuts.scala`
 *   - 5 Phase-1 cheat patterns from the gap-audit campaign (worktree)
 *   - 7 Phase-1 comment-only additions from the worktree
 */
package rescale.enforce

import cats.effect.IO
import fs2.io.file.Path
import rescale.common.FileOps

import java.io.File
import scala.util.matching.Regex

object Shortcuts {

  final case class Hit(file: String, line: Int, pattern: String, text: String)

  private sealed trait Rule {
    def name:  String
    def regex: Regex
  }
  private final case class Always(name: String, regex: Regex)      extends Rule
  private final case class CommentOnly(name: String, regex: Regex) extends Rule

  /** Distinctive markers that are rare as legitimate identifiers and
    * therefore safe to match anywhere on a line.
    */
  private val alwaysRules: List[Always] = List(
    Always("todo",            """\bTODO\b""".r),
    Always("fixme",           """\bFIXME\b""".r),
    Always("hack",            """\bHACK\b""".r),
    Always("xxx",             """\bXXX\b""".r),
    Always("unsupported-op",  """UnsupportedOperationException""".r),
    Always("not-implemented", """throw\s+new\s+NotImplementedError""".r),
    Always("scala-unimpl",    """\?\?\?""".r),
    Always("catch-throwable", """catch\s*\{[^}]*case\s+_?\s*:?\s*Throwable\b""".r),
    Always("cssStub-fn",      """\bcssStub\s*\(""".r),
    // -- Phase 1 anti-cheat additions ---------------------------------
    Always("null-cast",                """null\.asInstanceOf\[""".r),
    Always("nullable-null-fallback",   """Nullable\.empty\.getOrElse\(null\)""".r),
    Always("get-or-else-null",         """\.getOrElse\(null\)""".r),
    Always("this-null-arg",            """\bthis\(\s*null\s*[,)]""".r),
    Always("flag-break-var",
      """var\s+(done|continue|finished|stop|canBreak|shouldBreak|shouldContinue)\s*[:=]""".r),
    Always("scalastyle-ignore-return", """return\s+.*//\s*scalastyle:ignore""".r),
    Always("throw-not-yet",
      """throw\s+new\s+(Runtime|IllegalState)Exception\(\s*"[^"]*not\s+yet""".r),
    Always("nowarn-stub",
      """@nowarn\("msg=unused[^"]*"\)\s*//.*\bstub\b""".r)
  )

  /** Softer markers that appear in legitimate code as variable names
    * (`PlaceholderSelector`, `val simplified = …`). These count only
    * inside a comment line.
    */
  private val commentRules: List[CommentOnly] = List(
    CommentOnly("stub-comment",          """(?i)\bstub\b""".r),
    CommentOnly("simplified-comment",    """(?i)\bsimplified\b""".r),
    CommentOnly("minimal-comment",       """(?i)\bminimal(?:\s+viable)?\b""".r),
    CommentOnly("placeholder-comment",   """(?i)\bplaceholder\b""".r),
    CommentOnly("tbd-comment",           """(?i)\bTBD\b""".r),
    CommentOnly("pending-comment",       """(?i)\bpending\b""".r),
    CommentOnly("shim-comment",          """(?i)\bshim\b""".r),
    CommentOnly("best-effort-comment",   """(?i)\bbest[-\s]effort\b""".r),
    CommentOnly("approximation-comment", """(?i)\bapproximation\b""".r),
    CommentOnly("deferred-comment",      """(?i)\bdeferred\b""".r),
    CommentOnly("phase-n-comment",       """(?i)Phase\s*\d+""".r),
    CommentOnly("not-yet-comment",       """(?i)\bnot\s+yet\b""".r),
    // -- Phase 1 anti-cheat additions ---------------------------------
    CommentOnly("would-be-used",   """(?i)would\s+(be\s+)?used\s+here""".r),
    CommentOnly("handled-below",   """(?i)handled\s+below""".r),
    CommentOnly("for-now-comment", """(?i)\bfor\s+now\b""".r),
    CommentOnly("break-comment",   """^\s*//\s*break\b""".r),
    CommentOnly("return-comment",  """^\s*//\s*return\b""".r),
    CommentOnly("would-do-comment",  """(?i)would\s+(do|implement|handle)\b""".r),
    CommentOnly("aspirational-com",  """(?i)\baspirational\b""".r)
  )

  /** Per-file streaming state. Tracks Apache header skip + nested
    * block-comment depth so multi-line `/* … */` regions still match
    * comment-only rules.
    */
  private final case class ScanState(
    inHeader:       Boolean,
    inBlockComment: Boolean,
    hits:           List[Hit]
  )

  private val Open  = "/" + "*"
  private val Close = "*" + "/"

  /** Scan a single file via streaming. Bounded memory per file. */
  def scanFile(path: Path): IO[List[Hit]] = {
    val file = path.toString
    FileOps
      .streamLines(path)
      .compile
      .fold(ScanState(inHeader = true, inBlockComment = false, hits = Nil)) { case (state, (lineNum, line)) =>
        stepLine(file, state, lineNum, line)
      }
      .map(_.hits.reverse)
  }

  def scanFile(file: File): IO[List[Hit]] = scanFile(Path.fromNioPath(file.toPath))

  private def stepLine(file: String, state: ScanState, lineNum: Int, line: String): ScanState = {
    val trimmed = line.trim
    var s       = state

    // Skip the Apache header — first lines until we exit the leading block.
    if (s.inHeader) {
      if (lineNum == 1 && !trimmed.startsWith("/*") && !trimmed.startsWith("/**"))
        s = s.copy(inHeader = false)
      else if (trimmed == Close || trimmed.endsWith(Close))
        s = s.copy(inHeader = false)
      // Skip rule evaluation for header lines.
      if (s.inHeader) return s
      // After exit, fall through with inHeader = false.
    }

    val lineIsComment = isCommentLine(trimmed) || s.inBlockComment

    // Update block-comment state for subsequent lines.
    if (trimmed.contains(Open) && !trimmed.contains(Close)) s = s.copy(inBlockComment = true)
    else if (s.inBlockComment && trimmed.contains(Close))   s = s.copy(inBlockComment = false)

    var newHits: List[Hit] = s.hits

    // Always rules
    var i = 0
    while (i < alwaysRules.length) {
      val r = alwaysRules(i)
      if (r.regex.findFirstIn(line).isDefined)
        newHits = Hit(file, lineNum, r.name, trimmed.take(120)) :: newHits
      i += 1
    }

    // CommentOnly rules
    if (lineIsComment) {
      var j = 0
      while (j < commentRules.length) {
        val r = commentRules(j)
        if (r.regex.findFirstIn(line).isDefined)
          newHits = Hit(file, lineNum, r.name, trimmed.take(120)) :: newHits
        j += 1
      }
    }

    s.copy(hits = newHits)
  }

  private def isCommentLine(trimmed: String): Boolean = {
    if (trimmed.startsWith("//")) return true
    if (trimmed.startsWith(Open)) return true
    if (trimmed.startsWith(Close)) return true
    if (trimmed.startsWith("*") && !trimmed.startsWith(Close)) return true
    false
  }

  /** Scan every `.scala` file under a directory tree. Streaming both
    * the directory walk (lazy via FS2 Files.walk) and per-file lines.
    */
  def scanDir(dir: Path): IO[List[Hit]] = {
    FileOps
      .streamFiles(dir, ".scala")
      .evalMap(p => scanFile(p))
      .compile
      .fold(List.empty[Hit])(_ ++ _)
  }

  def scanDir(dir: File): IO[List[Hit]] = scanDir(Path.fromNioPath(dir.toPath))
}
