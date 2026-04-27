/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Header writer for the `fileinfo` command. Inserts or updates
 * key-value lines in a Scala file's leading block comment, preserving
 * existing Covenant-* prefixes and license preamble.
 */
package rescale.fileinfo

import cats.effect.IO

import java.io.{File, PrintWriter}
import java.nio.file.{Files as NIOFiles}

object FileHeaderApply {

  final case class Result(
    path:    String,
    action:  String,
    updated: Map[String, String]
  )

  def setProperties(file: File, updates: Map[String, String]): IO[Result] =
    IO.blocking {
      val lines = NIOFiles.readAllLines(file.toPath)
      val linesVec = (0 until lines.size()).map(lines.get).toVector

      val headerStart = linesVec.indexWhere(_.trim.startsWith("/*"))
      val headerEnd = if (headerStart >= 0) {
        linesVec.indexWhere(l => l.trim == "*/" || l.trim.endsWith("*/"), headerStart)
      } else -1

      if (headerStart < 0 || headerEnd < 0) {
        throw new RuntimeException(
          s"No block comment found in ${file.getPath} — fileinfo set requires a leading /* ... */ comment"
        )
      }

      val covenantKeyMap = Map(
        "covenant"           -> "Covenant",
        "baseline-spec-pass" -> "Covenant-baseline-spec-pass",
        "baseline-loc"       -> "Covenant-baseline-loc",
        "baseline-methods"   -> "Covenant-baseline-methods",
        "source-reference"   -> "Covenant-source-reference",
        "verified"           -> "Covenant-verified"
      )

      val newKvLines = updates.toList.sortBy(_._1).map { case (normKey, value) =>
        val rawKey = covenantKeyMap.getOrElse(normKey, normKey)
        s" * $rawKey: $value"
      }

      val existingKvRange = (headerStart to headerEnd).filter { i =>
        val stripped = stripForMatch(linesVec(i))
        updates.keys.exists { normKey =>
          val rawKey = covenantKeyMap.getOrElse(normKey, normKey)
          stripped.startsWith(rawKey + ":") || stripped.startsWith(rawKey + " :")
        }
      }

      val newLines: Vector[String] = if (existingKvRange.nonEmpty) {
        val firstExisting = existingKvRange.head
        val lastExisting  = existingKvRange.last
        val untouched = (headerStart to headerEnd).filterNot(existingKvRange.contains).filter { i =>
          val stripped = stripForMatch(linesVec(i))
          val isKv = """^[A-Za-z][\w-]*\s*:""".r.findFirstIn(stripped).isDefined
          isKv && !skipKeys.contains(stripped.takeWhile(c => c != ':' && c != ' '))
        }
        val keepLines = untouched.map(linesVec(_))
        linesVec.take(firstExisting) ++
          newKvLines ++
          keepLines.filterNot(l => newKvLines.exists(nl => nl.trim == l.trim)) ++
          linesVec.drop(lastExisting + 1)
      } else {
        val needsSeparator = {
          val prevLine = if (headerEnd > 0) linesVec(headerEnd - 1).trim else ""
          prevLine.nonEmpty && prevLine != "*"
        }
        val separator = if (needsSeparator) Vector(" *") else Vector.empty
        linesVec.take(headerEnd) ++ separator ++ newKvLines ++ linesVec.drop(headerEnd)
      }

      val w = new PrintWriter(file)
      try newLines.foreach(w.println)
      finally w.close()

      Result(
        file.getPath,
        if (existingKvRange.nonEmpty) "updated" else "created",
        updates
      )
    }

  private val skipKeys: Set[String] = Set("SPDX-License-Identifier", "Copyright")

  private def stripForMatch(line: String): String = {
    val open = "/" + "*"
    val close = "*" + "/"
    line.trim
      .stripPrefix("*")
      .stripPrefix(open)
      .stripPrefix("//")
      .stripSuffix(close)
      .trim
  }
}
