/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * FS2 streaming primitives for file I/O.
 *
 * THIS IS THE HEART OF THE ANTI-OOM REWRITE. Every scanner command —
 * `enforce shortcuts`, `enforce stale-stubs`, `compare strict`, the
 * Methods extractors — must read files through [[streamLines]] or
 * [[foldLines]], never via `Files.readAll(path).compile.string` or
 * `java.nio.file.Files.readString`.
 *
 * Invariants enforced by Ssg944FileMemoryBoundSpec:
 *   1. Peak RSS < 512 MiB across all 944 SSG files
 *   2. Wall clock < 30 s per scanner subcommand
 *   3. No command holds more than one file's worth of bytes in memory
 *      at once, and within a file no more than a bounded per-line
 *      buffer (plus any aggregates the caller chooses to accumulate).
 */
package rescale.common

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.text

import java.io.File

object FileOps {

  /** Open a file and yield its lines paired with 1-based line numbers.
    * Backpressure-safe: the stream never materializes more than one
    * line at a time, and cancellation releases the underlying file
    * handle via FS2's Resource-aware readAll combinator.
    *
    * This is the ONLY correct way to walk a file line by line in
    * re-scale. Using it from a scanner means the scanner is
    * constant-memory per file by construction.
    */
  def streamLines(path: Path): Stream[IO, (Int, String)] = {
    Files[IO]
      .readAll(path)
      .through(text.utf8.decode)
      .through(text.lines)
      .through(dropTrailingEmpty)
      .zipWithIndex
      .map { case (line, idx) => ((idx + 1).toInt, line) }
  }

  /** Drop the trailing empty string that `text.lines` emits when the
    * input ends with a newline. Without this, `wc -l file` and
    * `streamLines(file).count` disagree by one for the common case of
    * files terminated by `\n`.
    *
    * Implementation: peek one element ahead via `zipWithNext`. Always
    * emit when there is a next; emit the last element only if non-empty.
    */
  private def dropTrailingEmpty: fs2.Pipe[IO, String, String] = in =>
    in.zipWithNext.flatMap {
      case (line, Some(_))               => Stream.emit(line)
      case (line, None) if line.nonEmpty => Stream.emit(line)
      case _                             => Stream.empty
    }

  /** Java-File compatibility wrapper. Most of our code still uses
    * `java.io.File`; this lets callers hand us one without knowing
    * about FS2 paths.
    */
  def streamLines(file: File): Stream[IO, (Int, String)] =
    streamLines(Path.fromNioPath(file.toPath))

  /** Stream every `.scala` (or other-extension) file under a directory
    * tree. Directory traversal is lazy — the stream never materializes
    * the full file list, and walking a large tree doesn't balloon
    * memory.
    *
    * @param dir       root directory to walk
    * @param extension file extension to include (e.g. `".scala"`).
    *                  Case-sensitive. Pass `""` to include all files.
    */
  def streamFiles(dir: Path, extension: String): Stream[IO, Path] = {
    Files[IO]
      .walk(dir)
      .evalFilter(p => Files[IO].isRegularFile(p))
      .filter(p => extension.isEmpty || p.fileName.toString.endsWith(extension))
  }

  def streamFiles(dir: File, extension: String): Stream[IO, Path] =
    streamFiles(Path.fromNioPath(dir.toPath), extension)

  /** Stream files across multiple root directories, suppressing
    * non-existent roots silently. Used by commands like
    * `enforce shortcuts` that scan several module directories.
    */
  def streamFilesAcross(dirs: List[File], extension: String): Stream[IO, Path] =
    Stream.emits(dirs).filter(_.isDirectory).flatMap(d => streamFiles(d, extension))

  /** Fold a file's lines into an aggregate without ever materializing
    * the file. Typical uses:
    *   - build a Set of identifiers per file
    *   - count occurrences of a pattern
    *   - collect the first N lines matching a predicate
    *
    * The caller's fold function must itself be bounded-size; this
    * combinator provides the streaming wrapper.
    */
  def foldLines[A](path: Path, z: A)(f: (A, (Int, String)) => A): IO[A] =
    streamLines(path).compile.fold(z)(f)

  def foldLines[A](file: File, z: A)(f: (A, (Int, String)) => A): IO[A] =
    foldLines(Path.fromNioPath(file.toPath), z)(f)

  /** Read all lines eagerly into a Vector. Only safe for files that
    * are KNOWN to be small (config files, header blocks, etc).
    * **Never** call this from a scanner.
    */
  def readAllLines(path: Path): IO[Vector[String]] =
    streamLines(path).map(_._2).compile.toVector

  def readAllLines(file: File): IO[Vector[String]] =
    readAllLines(Path.fromNioPath(file.toPath))

  /** Write a stream of lines to a file, one per line, encoded UTF-8.
    * Opens the file for overwrite; does not append. Use Tsv.write for
    * atomic TSV writes with the temp+move dance.
    */
  def writeLines(path: Path, lines: Stream[IO, String]): IO[Unit] =
    (lines.intersperse("\n") ++ Stream.emit("\n"))
      .through(text.utf8.encode)
      .through(Files[IO].writeAll(path))
      .compile
      .drain

  def writeLines(file: File, lines: Stream[IO, String]): IO[Unit] =
    writeLines(Path.fromNioPath(file.toPath), lines)
}
