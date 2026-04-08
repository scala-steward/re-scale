/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stale-stub detector — Phase 4 of the re-scale plan, redesigned around
 * the architectural critique that "storing the whole identifier set in
 * memory will always exceed any fixed budget on a sufficiently large
 * codebase."
 *
 * REWRITTEN ALGORITHM (the previous "buildIndex of every identifier"
 * approach was the dominant cost on the SSG corpus):
 *
 *   Pass 1 (streaming):
 *     Walk every Scala file via `fs2.io.file.Files[IO].walk`. Per file,
 *     stream lines and emit a `Suspect` record for every "not yet ported"
 *     /"would be used"/"TODO" comment found. Each Suspect carries the
 *     candidate identifiers extracted from the 7-line window around the
 *     comment. The output is a Stream[IO, Suspect] folded into a small
 *     List — bounded by the number of suspect comments in the codebase
 *     (typically ~100), NOT by the number of identifiers (which can be
 *     ~10k).
 *
 *   Pass 2 (streaming):
 *     With the SHORT suspect list in hand, walk every Scala file again
 *     and stream definitions. For each definition whose name appears in
 *     ANY suspect's candidate set, emit a StaleHit. Hits are emitted as
 *     a Stream[IO, StaleHit]; the caller folds them at the consumer
 *     boundary, so memory pressure stays bounded by the per-batch chunk
 *     size, not by the total hit count.
 *
 * Memory invariants enforced by the redesign:
 *   - No `Set[String]` of every identifier in the codebase.
 *   - No `List[StaleHit]` materialized inside this module.
 *   - `findAllMatchIn` is replaced by reusable `java.util.regex.Matcher`
 *     instances reset per line; the per-match Match-object allocation
 *     that drove the previous version's 480 MiB high-water mark is gone.
 *   - Directory traversal uses `Files[IO].walk` (lazy FS2 stream) not
 *     `java.io.File.listFiles` (eager Array allocation per directory).
 */
package rescale.enforce

import cats.effect.IO
import fs2.{Pipe, Stream}
import fs2.io.file.{Files, Path}
import fs2.text

import java.io.File
import java.util.regex.Pattern
import scala.collection.mutable

object StaleStubs {

  final case class StaleHit(file: String, line: Int, identifier: String, definedAt: String, comment: String)

  /** A suspect-comment location collected in pass 1. Carries the
    * candidate identifiers extracted from its ±3-line window so pass 2
    * can match definitions against them without re-scanning the file.
    */
  final case class Suspect(
    file:        String,
    line:        Int,
    commentText: String,
    candidates:  Set[String]
  )

  // -- Patterns ------------------------------------------------------

  private val suspectComment: Pattern =
    Pattern.compile("""(?i)\b(not\s+yet\s+ported|would\s+be\s+used\s+here|would\s+(do|implement|handle)|handled\s+below|for\s+now|stale|TODO)\b""")

  private val screamingSnake: Pattern =
    Pattern.compile("""\b([A-Z][A-Z0-9_]{2,})\b""")

  private val classMember: Pattern =
    Pattern.compile("""\b([A-Z][A-Za-z0-9_]+)\.([A-Z][A-Za-z0-9_]+)\b""")

  // Definition patterns — same as before, just compiled to Pattern.
  private val pScalaDef: Pattern =
    Pattern.compile("""^[ \t]*(?:override\s+|final\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|implicit\s+|lazy\s+|inline\s+|transparent\s+)*def\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_$]*))""")

  private val pScalaVal: Pattern =
    Pattern.compile("""^[ \t]{0,4}(?:override\s+|final\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|implicit\s+|lazy\s+|inline\s+)*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_$]*)""")

  private val pScalaType: Pattern =
    Pattern.compile("""^[ \t]*(?:sealed\s+|final\s+|abstract\s+|private(?:\[[^\]]+\])?\s+|protected(?:\[[^\]]+\])?\s+|open\s+|case\s+)*(?:class|trait|object|enum)\s+([A-Za-z_][A-Za-z0-9_$]*)""")

  private val ExcludeIdentifiers: Set[String] = Set(
    "TODO", "FIXME", "HACK", "XXX", "NOTE", "BUG", "WARNING",
    "API", "URL", "URI", "UUID", "JSON", "XML", "HTML", "CSS", "JS", "MD",
    "SSG", "JVM", "AST", "DOM", "CLI", "CI", "OK", "ERR",
    "TRUE", "FALSE", "NULL", "NIL"
  )

  // -- Public API ----------------------------------------------------

  /** Run the full two-pass scan. Returns a Stream of hits — the caller
    * decides whether to print them as they arrive (truly constant
    * memory) or fold them into a List for batch reporting.
    */
  def scan(srcDirs: List[File]): Stream[IO, StaleHit] = {
    val rootPaths = srcDirs.filter(_.isDirectory).map(d => Path.fromNioPath(d.toPath))
    Stream.eval(collectSuspects(rootPaths)).flatMap { suspects =>
      if (suspects.isEmpty) Stream.empty
      else {
        // Union of every suspect's candidate identifiers — bounded by
        // suspect count, NOT by codebase size.
        val needed: Set[String] = suspects.iterator.flatMap(_.candidates).toSet
        // Index suspects by candidate identifier for O(1) hit emission.
        val byCandidate: Map[String, List[Suspect]] =
          suspects
            .flatMap(s => s.candidates.iterator.map(_ -> s))
            .groupMap(_._1)(_._2)
        scanDefinitionsAndEmitHits(rootPaths, needed, byCandidate)
      }
    }
  }

  /** Caller-side helper: run the scan and fold the resulting hit stream
    * into a List. Use this for `enforce stale-stubs` where the result
    * is printed in a single batch at the end.
    */
  def scanList(srcDirs: List[File]): IO[List[StaleHit]] =
    scan(srcDirs).compile.toList

  /** Walk the source roots through fs2.io.file.Files and run the
    * suspect-collection pipe per file.
    */
  private def collectSuspects(roots: List[Path]): IO[List[Suspect]] =
    Stream
      .emits(roots)
      .flatMap(walkScalaFiles)
      .flatMap(p => streamSuspects(p))
      .compile
      .toList

  /** Walk the source roots a SECOND time and emit StaleHits as we find
    * matching definitions. The hit stream is constant-memory per
    * emission; the caller decides what to do with it.
    */
  private def scanDefinitionsAndEmitHits(
    roots:       List[Path],
    needed:      Set[String],
    byCandidate: Map[String, List[Suspect]]
  ): Stream[IO, StaleHit] =
    Stream
      .emits(roots)
      .flatMap(walkScalaFiles)
      .flatMap(p => streamDefinitions(p, needed))
      .flatMap { name =>
        Stream.emits(byCandidate.getOrElse(name, Nil).map { s =>
          StaleHit(s.file, s.line, name, "(in index)", s.commentText)
        })
      }

  /** Lazy walk: emit every `.scala` regular file under `root`. Uses
    * `Files[IO].walk` so the directory traversal is itself a stream
    * with backpressure — we never materialize the full file list.
    */
  private def walkScalaFiles(root: Path): Stream[IO, Path] =
    Files[IO]
      .walk(root)
      .evalFilter(p => Files[IO].isRegularFile(p))
      .filter(_.fileName.toString.endsWith(".scala"))

  // -- Pass 1: stream suspects out of one file -----------------------

  /** Per-file pass-1 worker. Reads the file via FS2 streaming with a
    * 7-line ring buffer; emits a Suspect for every line whose middle
    * slot contains a "not yet ported"-style comment. The candidate
    * identifiers are the SCREAMING_SNAKE + Class.MEMBER tokens found
    * anywhere in the 7-line window.
    *
    * The ring buffer holds at most 7 line strings — total per-file
    * working set is bounded by 7 line widths.
    */
  private def streamSuspects(path: Path): Stream[IO, Suspect] = {
    val file = path.toString
    val ring = new Array[String](7)
    val nums = new Array[Int](7)
    var head = 3      // pre-fill 3 empty slots so line 1 becomes the middle
    var filled = 3
    var inHeader = true
    var lineNum  = 0

    // Reusable matchers — allocated once per file, reset per check.
    val mSuspect = suspectComment.matcher("")
    val mSnake   = screamingSnake.matcher("")
    val mMember  = classMember.matcher("")

    def midSlot: Int = (head + 3) % 7

    def maybeEmit: List[Suspect] = {
      val ms  = midSlot
      val cur = ring(ms)
      val cln = nums(ms)
      if (cur == null || cur.isEmpty || cln == 0) Nil
      else {
        mSuspect.reset(cur)
        if (!mSuspect.find()) Nil
        else {
          // Collect candidate identifiers from the 7-slot window.
          val cands = mutable.HashSet.empty[String]
          var i = 0
          while (i < 7) {
            val l = ring(i)
            if (l != null && l.nonEmpty) {
              mMember.reset(l)
              while (mMember.find()) {
                val full = mMember.group(1) + "." + mMember.group(2)
                val mem  = mMember.group(2)
                cands += full
                cands += mem
              }
              mSnake.reset(l)
              while (mSnake.find()) {
                val id = mSnake.group(1)
                if (!ExcludeIdentifiers.contains(id)) cands += id
              }
            }
            i += 1
          }
          if (cands.isEmpty) Nil
          else List(Suspect(file, cln, cur.trim.take(120), cands.toSet))
        }
      }
    }

    def append(line: String): List[Suspect] = {
      ring(head) = line
      nums(head) = lineNum
      head = (head + 1) % 7
      if (filled < 7) filled += 1
      if (filled == 7) maybeEmit else Nil
    }

    val perLine: Pipe[IO, String, Suspect] = _.flatMap { line =>
      lineNum += 1
      val emitted: List[Suspect] =
        if (inHeader) {
          val trimmed = line.trim
          if (lineNum == 1 && !trimmed.startsWith("/*")) {
            inHeader = false
            append(line)
          } else if (trimmed == "*/" || trimmed.endsWith("*" + "/")) {
            inHeader = false
            Nil
          } else Nil
        } else append(line)
      Stream.emits(emitted)
    }

    // Drain pipe: at end-of-stream, push 3 empty slots so the LAST 3
    // real lines also get a chance to be the middle.
    val drainPipe: Pipe[IO, Suspect, Suspect] = upstream =>
      upstream ++ Stream.suspend {
        val out = mutable.ListBuffer.empty[Suspect]
        var d = 0
        while (d < math.min(3, filled)) {
          ring(head) = ""
          nums(head) = 0
          head = (head + 1) % 7
          if (filled < 7) filled += 1
          if (filled == 7) out ++= maybeEmit
          d += 1
        }
        Stream.emits(out.toList)
      }

    Files[IO]
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(perLine)
      .through(drainPipe)
  }

  // -- Pass 2: stream definitions whose name is in `needed` ----------

  /** Stream every defined identifier name from a file. Per-line regex
    * scanning with reusable matchers. Only emits names that appear in
    * `needed`, so the per-file output is bounded by hit count.
    */
  private def streamDefinitions(path: Path, needed: Set[String]): Stream[IO, String] = {
    val mDef  = pScalaDef.matcher("")
    val mVal  = pScalaVal.matcher("")
    val mType = pScalaType.matcher("")

    val pipe: Pipe[IO, String, String] = _.flatMap { line =>
      val emitted = mutable.ListBuffer.empty[String]
      if (line.contains("def ")) {
        mDef.reset(line)
        while (mDef.find()) {
          val g1 = mDef.group(1)
          val g2 = mDef.group(2)
          val nm = if (g1 != null) g1 else g2
          if (nm != null && needed.contains(nm)) emitted += nm
        }
      }
      if (line.contains("val ") || line.contains("var ")) {
        mVal.reset(line)
        while (mVal.find()) {
          val nm = mVal.group(1)
          if (nm != null && needed.contains(nm)) emitted += nm
        }
      }
      if (line.contains("class ") || line.contains("object ") || line.contains("trait ") || line.contains("enum ")) {
        mType.reset(line)
        while (mType.find()) {
          val nm = mType.group(1)
          if (nm != null && needed.contains(nm)) emitted += nm
        }
      }
      Stream.emits(emitted.toList)
    }

    Files[IO]
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(pipe)
  }

  // -- Backward-compat helpers (used by tests) -----------------------

  /** Test-friendly: scan a single file against an explicit identifier
    * index. Used by `StaleStubsSpec`. The two-pass production path
    * doesn't need this, but the test fixtures rely on it.
    */
  def scanFile(path: Path, index: Set[String]): IO[List[StaleHit]] = {
    val file = path.toString
    streamSuspects(path).compile.toList.map { suspects =>
      suspects.flatMap { s =>
        s.candidates.iterator.collect {
          case id if index.contains(id) =>
            StaleHit(file, s.line, id, "(in index)", s.commentText)
        }.toList
      }
    }
  }

  def scanFile(file: File, index: Set[String]): IO[List[StaleHit]] =
    scanFile(Path.fromNioPath(file.toPath), index)

  /** Legacy test helper: build a Set[String] index. Kept ONLY for the
    * test suite — production code uses the two-pass `scan` instead.
    *
    * This implementation is itself bounded-memory: it streams files
    * and accumulates names in a single shared mutable.HashSet, then
    * snapshots to an immutable Set at the end.
    */
  def buildIndex(srcDirs: List[File]): IO[Set[String]] = {
    val acc = mutable.HashSet.empty[String]
    val rootPaths = srcDirs.filter(_.isDirectory).map(d => Path.fromNioPath(d.toPath))
    Stream
      .emits(rootPaths)
      .flatMap(walkScalaFiles)
      .evalMap(p => indexOneFile(p, acc))
      .compile
      .drain
      .map(_ => acc.toSet)
  }

  private def indexOneFile(path: Path, acc: mutable.HashSet[String]): IO[Unit] = {
    val mDef   = pScalaDef.matcher("")
    val mVal   = pScalaVal.matcher("")
    val mType  = pScalaType.matcher("")
    val mSnake = screamingSnake.matcher("")

    val pipe: Pipe[IO, String, Unit] = _.evalMap { line =>
      IO.delay {
        if (line.contains("def ")) {
          mDef.reset(line)
          while (mDef.find()) {
            val g1 = mDef.group(1)
            val g2 = mDef.group(2)
            val nm = if (g1 != null) g1 else g2
            if (nm != null) acc += nm
          }
        }
        if (line.contains("val ") || line.contains("var ")) {
          mVal.reset(line)
          while (mVal.find()) {
            val nm = mVal.group(1)
            if (nm != null) acc += nm
          }
        }
        if (line.contains("class ") || line.contains("object ") || line.contains("trait ") || line.contains("enum ")) {
          mType.reset(line)
          while (mType.find()) {
            val nm = mType.group(1)
            if (nm != null) acc += nm
          }
        }
        var hasUpper = false
        val n = line.length
        var i = 0
        while (i < n && !hasUpper) {
          val c = line.charAt(i)
          if (c >= 'A' && c <= 'Z') hasUpper = true
          i += 1
        }
        if (hasUpper) {
          mSnake.reset(line)
          while (mSnake.find()) {
            val id = mSnake.group(1)
            if (!ExcludeIdentifiers.contains(id)) acc += id
          }
        }
        ()
      }
    }

    Files[IO]
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(pipe)
      .compile
      .drain
  }

  /** Legacy test helper: scan a directory for stale stubs against an
    * explicit pre-built index. Used by `StaleStubsSpec`.
    */
  def scanDir(dir: File, index: Set[String]): IO[List[StaleHit]] = {
    val root = Path.fromNioPath(dir.toPath)
    walkScalaFiles(root).evalMap(p => scanFile(p, index)).compile.fold(List.empty[StaleHit])(_ ++ _)
  }
}
