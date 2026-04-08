/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * TSV reader/writer with typed headers, comment support, CSV-aware
 * tab splitting, atomic writes, and cross-process file locking.
 *
 * Ported from ssg-dev scripts/src/common/Tsv.scala but rewritten to
 * use Cats Effect `Resource` for lifecycle management. The hot read
 * path uses FS2 streaming so parsing a 100 MB TSV doesn't materialize
 * the whole file as a String (ssg-dev's old implementation did, which
 * was a latent OOM).
 *
 * Key design:
 *   - [[read]]     — streams lines through a state machine, builds a Table
 *   - [[write]]    — writes to <path>.tmp, atomic-moves into place
 *   - [[modify]]   — acquires an exclusive file lock via Resource,
 *                    reads, mutates, writes, releases. Lock release is
 *                    guaranteed even on cancellation or error.
 *   - splitFields  — CSV-aware; respects "..." wrapping with ""
 *                    double-quote escapes
 *
 * The blocking `channel.lock()` call replaces the earlier tryLock+retry
 * loop that caused an Immix GC marker death spiral in commit 0f08ac0.
 * On Scala Native it sleeps in `fcntl(F_SETLKW)` without allocating
 * exception objects per retry.
 */
package rescale.common

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import fs2.Stream
import fs2.io.file.{Files, Path, CopyFlag, CopyFlags}

import java.io.{File, RandomAccessFile}
import java.nio.channels.{FileChannel, FileLock}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer

object Tsv {

  final case class Table(
    headers:  List[String],
    rows:     List[Map[String, String]],
    comments: List[String] = Nil
  ) {
    def filter(pred: Map[String, String] => Boolean): Table           = copy(rows = rows.filter(pred))
    def find(pred: Map[String, String] => Boolean): Option[Map[String, String]] = rows.find(pred)
    def sortBy(key: String): Table                                     = copy(rows = rows.sortBy(_.getOrElse(key, "")))
    def size: Int                                                      = rows.size

    def paginate(limit: Option[Int], offset: Option[Int]): Table = {
      var r = rows
      offset.foreach(o => r = r.drop(o))
      limit.foreach(l => r = r.take(l))
      copy(rows = r)
    }

    def addRow(row: Map[String, String]): Table = copy(rows = rows :+ row)

    def updateRow(pred: Map[String, String] => Boolean, updates: Map[String, String]): Table =
      copy(rows = rows.map(row => if (pred(row)) row ++ updates else row))

    def stats(key: String): Map[String, Int] =
      rows.groupBy(_.getOrElse(key, "(empty)")).map { case (k, v) => k -> v.size }
  }

  object Table {
    val empty: Table = Table(Nil, Nil, Nil)
  }

  /** Intermediate state for the streaming parser. The table is built
    * one line at a time, so peak memory is proportional to the
    * in-flight Table, not to the file text itself.
    */
  private final case class ParseState(
    headers:     List[String],
    rows:        ListBuffer[List[String]],
    comments:    ListBuffer[String],
    headerFound: Boolean
  )

  /** Read a TSV file into a Table via FS2 streaming. Throws on a data
    * row that appears before the header comment line — we want loud
    * failure rather than silent schema drift.
    */
  def read(path: Path): IO[Table] = {
    val initial = ParseState(
      headers     = Nil,
      rows        = ListBuffer.empty,
      comments    = ListBuffer.empty,
      headerFound = false
    )
    FileOps
      .streamLines(path)
      .compile
      .fold(initial) { case (state, (_, line)) =>
        if (!state.headerFound && line.startsWith("# ") && line.contains("\t")) {
          state.copy(
            headers     = splitFields(line.drop(2)),
            headerFound = true
          )
        } else if (line.startsWith("#")) {
          state.comments += line
          state
        } else if (line.nonEmpty) {
          if (!state.headerFound) {
            throw new RuntimeException(s"TSV $path: data row before header line: $line")
          }
          state.rows += splitFields(line)
          state
        } else state
      }
      .map { state =>
        val hs = state.headers
        val rs = state.rows.toList.map(fields => hs.zip(fields.padTo(hs.size, "")).toMap)
        Table(hs, rs, state.comments.toList)
      }
  }

  def read(file: File): IO[Table] = read(Path.fromNioPath(file.toPath))

  /** CSV-aware tab splitter. Single-pass; never backtracks. Respects
    * `"..."` wrapping where a quoted cell may contain tabs, newlines,
    * or escaped quotes (`""`).
    */
  private[common] def splitFields(line: String): List[String] = {
    val out      = ListBuffer.empty[String]
    val cur      = new StringBuilder
    var i        = 0
    var inQuotes = false
    val n        = line.length
    while (i < n) {
      val c = line.charAt(i)
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < n && line.charAt(i + 1) == '"') {
            cur.append('"'); i += 2
          } else {
            inQuotes = false; i += 1
          }
        } else {
          cur.append(c); i += 1
        }
      } else {
        if (c == '"' && cur.isEmpty) {
          inQuotes = true; i += 1
        } else if (c == '\t') {
          out += cur.toString; cur.clear(); i += 1
        } else {
          cur.append(c); i += 1
        }
      }
    }
    out += cur.toString
    out.toList
  }

  private def quote(value: String): String =
    if (value.contains("\t") || value.contains("\n") || value.contains("\"")) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else value

  /** Render a Table as a stream of lines (comments, header, data).
    * Used by [[write]]; kept separate so tests can assert on the
    * serialized form without touching the filesystem.
    */
  def serialize(table: Table): Stream[IO, String] = {
    val commentLines = Stream.emits(table.comments)
    val headerLine   = Stream.emit("# " + table.headers.mkString("\t"))
    val dataLines    = Stream.emits(
      table.rows.map(row => table.headers.map(h => quote(row.getOrElse(h, ""))).mkString("\t"))
    )
    commentLines ++ headerLine ++ dataLines
  }

  /** Atomic write: stream the Table's serialized form into a UUID-
    * suffixed sibling temp file, then atomically rename into place. On
    * filesystems that don't support `ATOMIC_MOVE`, falls back to plain
    * replace.
    *
    * The UUID suffix is a defense-in-depth against parallel writers
    * that have somehow ended up here without going through `modify` —
    * each gets a private temp file so no two writers can collide on
    * the same name.
    */
  // Monotonic counter for temp-file naming. Combined with nanoTime() it
  // gives a per-write unique suffix without depending on SecureRandom
  // (which Scala Native does not implement).
  private val tempCounter = new AtomicLong(0L)

  def write(path: Path, table: Table): IO[Unit] =
    IO.delay {
      val n = tempCounter.incrementAndGet()
      val t = System.nanoTime()
      s".tmp.$t.$n"
    }.flatMap { suffix =>
      val tmp = Path(path.toString + suffix)
      FileOps.writeLines(tmp, serialize(table)) *>
        Files[IO]
          .move(tmp, path, CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.AtomicMove))
          .handleErrorWith(_ => Files[IO].move(tmp, path, CopyFlags(CopyFlag.ReplaceExisting)))
    }

  def write(file: File, table: Table): IO[Unit] = write(Path.fromNioPath(file.toPath), table)

  // -- In-process serialization ----------------------------------------------

  /** Per-path mutex registry. Multiple `modify` calls on the same path
    * within a single JVM serialize through a `Mutex[IO]` keyed by the
    * absolute path. This is REQUIRED in addition to the OS file lock
    * because Scala Native's `FileChannel.lock()` is process-level on
    * many systems — within a single process, two `lock()` calls on the
    * same file may both succeed immediately, leaving callers racing
    * on the temp file and the move.
    */
  private val pathMutexes = new ConcurrentHashMap[String, Mutex[IO]]()

  private def mutexFor(path: Path): IO[Mutex[IO]] = {
    val key = path.absolute.toString
    IO.delay(Option(pathMutexes.get(key))).flatMap {
      case Some(m) => IO.pure(m)
      case None    =>
        Mutex[IO].flatMap { fresh =>
          IO.delay {
            val prior = pathMutexes.putIfAbsent(key, fresh)
            if (prior eq null) fresh else prior
          }
        }
    }
  }

  // -- Cross-process locking -------------------------------------------------

  /** Resource that acquires an exclusive file lock on `<path>.lock`.
    * Release is guaranteed on normal completion, error, or
    * cancellation — that's the whole point of using Resource.
    *
    * `channel.lock()` on Scala Native is a blocking `fcntl(F_SETLKW)`.
    * It sleeps in-kernel without allocating per-retry exception
    * objects, avoiding the Immix GC death spiral we hit in commit
    * 0f08ac0 when we tried `tryLock` in a retry loop.
    */
  def lockFile(path: Path): Resource[IO, FileLock] = {
    val lockPath = new File(path.toString + ".lock")
    for {
      _    <- Resource.eval(IO.blocking {
                if (!lockPath.exists()) lockPath.createNewFile(): Unit
              })
      raf  <- Resource.fromAutoCloseable(IO.blocking(new RandomAccessFile(lockPath, "rw")))
      ch   <- Resource.fromAutoCloseable(IO.delay(raf.getChannel: FileChannel))
      lock <- Resource.make(IO.blocking(ch.lock()))(l => IO.blocking(l.release()).attempt.void)
    } yield lock
  }

  /** Cross-process locked load + mutate + save.
    *
    * Acquires an exclusive file lock, reads the current table (empty
    * if the file doesn't exist yet), applies `fn`, writes atomically,
    * releases the lock. The lock is held for the full load→mutate→save
    * cycle so concurrent writers serialize cleanly without torn rows.
    *
    * Survived a 15-parallel writer race test in the legacy ssg-dev
    * (commit `be18ed6`); that test migrates to `TsvSpec` in Phase 1.
    */
  def modify(path: Path)(fn: Table => Table): IO[Table] =
    modifyWith(path) { t =>
      val updated = fn(t)
      (updated, updated)
    }

  def modify(file: File)(fn: Table => Table): IO[Table] =
    modify(Path.fromNioPath(file.toPath))(fn)

  /** Like [[modify]] but the callback returns both the updated table
    * AND a derived value that gets threaded back through the IO chain.
    *
    * Use this when you need to compute something inside the locked
    * critical section and surface it to the caller — e.g.
    * IssuesDb.add allocates the next ISS-NNN id inside the lock and
    * returns it so the user sees what was assigned, without using
    * unsafe side channels.
    */
  def modifyWith[A](path: Path)(fn: Table => (Table, A)): IO[A] =
    mutexFor(path).flatMap { mutex =>
      mutex.lock.surround {
        lockFile(path).use { _ =>
          for {
            exists <- Files[IO].exists(path)
            table  <- if (exists) read(path) else IO.pure(Table.empty)
            result  = fn(table)
            _      <- write(path, result._1)
          } yield result._2
        }
      }
    }

  def modifyWith[A](file: File)(fn: Table => (Table, A)): IO[A] =
    modifyWith(Path.fromNioPath(file.toPath))(fn)
}
