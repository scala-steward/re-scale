/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from ssg-dev scripts/src/common/Cli.scala. Plain Scala — no
 * streaming or resource safety needed for argument parsing.
 */
package rescale.common

/** Hand-rolled command-line argument parser. Splits a list of tokens
  * into named flags (`--key value` or `--key=value`), boolean flags
  * (`--key` with no value), and positional arguments.
  *
  * Intentionally minimal: no typed codecs, no subcommand routing. Each
  * command dispatches on positionals and reads flags via the methods
  * on `Args`.
  */
object Cli {

  final case class Args(
    flags:      Map[String, String],
    positional: List[String]
  ) {
    def flag(name: String): Option[String]                   = flags.get(name)
    def hasFlag(name: String): Boolean                       = flags.contains(name)
    def flagOrDefault(name: String, default: String): String = flags.getOrElse(name, default)

    def requirePositional(index: Int, name: String): String = {
      if (index >= positional.length) {
        throw new IllegalArgumentException(s"Missing required argument: <$name>")
      }
      positional(index)
    }

    def positionalAt(index: Int): Option[String] =
      if (index < positional.length) Some(positional(index)) else None
  }

  def parse(args: List[String]): Args = {
    val flags      = scala.collection.mutable.Map.empty[String, String]
    val positional = scala.collection.mutable.ListBuffer.empty[String]
    var remaining  = args
    var pastDashes = false

    while (remaining.nonEmpty) {
      remaining match {
        case "--" :: rest =>
          pastDashes = true
          remaining = rest
        case arg :: rest if !pastDashes && arg.startsWith("--") =>
          val keyVal = arg.drop(2)
          val eqIdx  = keyVal.indexOf('=')
          if (eqIdx >= 0) {
            flags(keyVal.substring(0, eqIdx)) = keyVal.substring(eqIdx + 1)
            remaining = rest
          } else if (rest.nonEmpty && !rest.head.startsWith("--")) {
            flags(keyVal) = rest.head
            remaining = rest.tail
          } else {
            flags(keyVal) = "true"
            remaining = rest
          }
        case arg :: rest =>
          positional += arg
          remaining = rest
        case Nil =>
          remaining = Nil
      }
    }
    Args(flags.toMap, positional.toList)
  }
}
