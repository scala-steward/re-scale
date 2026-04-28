/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Generalized header parser for the `fileinfo` command. Reads the
 * first 80 lines of a Scala file and extracts all Key: Value pairs
 * from the leading block comment, normalizing Covenant-* prefixes
 * for uniform predicate evaluation.
 */
package rescale.fileinfo

import cats.effect.IO
import fs2.io.file.Path
import rescale.common.FileOps

import java.io.File

object FileHeader {

  final case class FileProperties(
    properties: Map[String, String],
    rawKeys:    Map[String, String],
    filePath:   String
  )

  def parse(path: Path): IO[FileProperties] = {
    val initial = ParseAcc(Map.empty, Map.empty, inBlockComment = false, pastComment = false, lastKey = None)
    FileOps
      .streamLines(path)
      .take(80)
      .compile
      .fold(initial) { case (acc, (_, line)) =>
        if (acc.pastComment) acc
        else {
          val trimmed = line.trim
          val (inBlock, isComment) = {
            if (acc.inBlockComment) {
              val closed = trimmed.contains("*/")
              (if (closed) false else true, true)
            } else if (trimmed.startsWith("/*")) {
              val selfClosing = trimmed.contains("*/") && trimmed.indexOf("*/") > trimmed.indexOf("/*") + 1
              (if (selfClosing) false else true, true)
            } else if (trimmed.startsWith("//")) {
              (false, true)
            } else {
              (false, false)
            }
          }

          if (!isComment && trimmed.nonEmpty) {
            acc.copy(inBlockComment = inBlock, pastComment = true)
          } else if (isComment) {
            val stripped = stripCommentMarkers(line)
            extractKeyValue(stripped) match {
              case Some((rawKey, value)) if !skipKeys.contains(rawKey) =>
                val normalized = normalizeKey(rawKey)
                acc.copy(
                  properties = acc.properties + (normalized -> value.trim),
                  rawKeys = acc.rawKeys + (normalized -> rawKey),
                  inBlockComment = inBlock,
                  lastKey = Some(normalized)
                )
              case _ if stripped.isEmpty =>
                acc.copy(inBlockComment = inBlock, lastKey = None)
              case _ if acc.lastKey.isDefined && stripped.nonEmpty && hasContinuationIndent(line) =>
                val key = acc.lastKey.get
                val prev = acc.properties.getOrElse(key, "")
                val sep = if (prev.endsWith(",")) "" else " "
                acc.copy(
                  properties = acc.properties + (key -> (prev + sep + stripped)),
                  inBlockComment = inBlock
                )
              case _ =>
                acc.copy(inBlockComment = inBlock, lastKey = None)
            }
          } else {
            acc.copy(inBlockComment = inBlock)
          }
        }
      }
      .map(acc => FileProperties(acc.properties, acc.rawKeys, path.toString))
  }

  def parse(file: File): IO[FileProperties] =
    parse(Path.fromNioPath(file.toPath))

  def sourceReference(props: Map[String, String]): Option[String] =
    props.get("original-src")
      .orElse(props.get("source-reference"))
      .orElse(props.collectFirst { case (k, v) if k.endsWith("-reference") => v })

  // -- internals ---------------------------------------------------------

  private final case class ParseAcc(
    properties:     Map[String, String],
    rawKeys:        Map[String, String],
    inBlockComment: Boolean,
    pastComment:    Boolean,
    lastKey:        Option[String]
  )

  private val skipKeys: Set[String] = Set(
    "SPDX-License-Identifier",
    "Copyright"
  )

  private val KeyValuePattern =
    """^([A-Za-z][\w-]*)\s*:\s*(.+)$""".r

  private def extractKeyValue(stripped: String): Option[(String, String)] =
    stripped match {
      case KeyValuePattern(key, value) => Some((key, value))
      case _                           => None
    }

  private[fileinfo] def normalizeKey(rawKey: String): String = {
    if (rawKey == "Covenant") "covenant"
    else if (rawKey == "Covenant-dart-reference") "source-reference"
    else if (rawKey.startsWith("Covenant-"))
      rawKey.drop("Covenant-".length).toLowerCase
    else rawKey.toLowerCase
  }

  /** Detect scalafmt continuation indent: after stripping the leading
    * comment marker (`*`, `//`), a continuation has 2+ leading spaces
    * while normal content has exactly 1. This distinguishes:
    *   ` * Covenant-baseline-methods: a,b,c,`  (1 space → key-value)
    *   ` *   d,e,f`                             (3 spaces → continuation)
    *   ` * Some prose text`                     (1 space → not continuation)
    */
  private def hasContinuationIndent(line: String): Boolean = {
    val trimmed = line.trim
    val afterPrefix =
      if (trimmed.startsWith("/*")) trimmed.drop(2)
      else if (trimmed.startsWith("//")) trimmed.drop(2)
      else if (trimmed.startsWith("*")) trimmed.drop(1)
      else return false
    afterPrefix.length >= 2 && afterPrefix.charAt(0) == ' ' && afterPrefix.charAt(1) == ' '
  }

  private def stripCommentMarkers(line: String): String = {
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
