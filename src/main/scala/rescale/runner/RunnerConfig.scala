/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `.rescale/runners.yaml` schema + loader.
 *
 * Schema example (`ssg/.rescale/runners.yaml`):
 *
 *   runners:
 *     sass-spec:
 *       description: "dart-sass spec compatibility test harness"
 *       invoke:
 *         command: sbt
 *         args: [--client, "ssg-sass/testOnly ssg.sass.SassSpecRunner"]
 *       mode-file:
 *         path: ssg-sass/target/sass-spec-mode.tsv
 *         format: kv                # one `key=value` per line
 *       output:
 *         success:
 *           regex: 'sass-spec:\s+Total=(\d+)\s+Passing=(\d+)'
 *           capture:
 *             total:   1
 *             passing: 2
 *         failure:
 *           keep-lines-matching: ['sass-spec:', 'FAIL', 'regressions']
 *           max-lines: 10
 *       modes:
 *         regression: {}
 *         strict: { strict: "1" }
 *         snapshot: { snapshot: "1" }
 *         subdir: { subdir: "$1" }
 *
 * The `mode-file` dance preserves the legacy SassSpec.scala correctness
 * fix: a stateful mode file is written before the runner exec, the
 * runner reads + deletes it on entry, and a runner crash never leaks
 * mode state across sessions.
 */
package rescale.runner

import hearth.kindlings.yamlderivation.{KindlingsYamlCodec, YamlConfig}
import org.virtuslab.yaml.*

import java.io.File
import scala.io.Source

given YamlConfig = YamlConfig().withUseDefaults

final case class RunnersConfig(
  runners: Map[String, RunnersConfig.Runner] = Map.empty
) derives KindlingsYamlCodec

object RunnersConfig {

  final case class Runner(
    description: Option[String]              = None,
    invoke:      Invoke,
    `mode-file`: Option[ModeFile]            = None,
    output:      Option[OutputSpec]          = None,
    modes:       Map[String, Map[String, String]] = Map.empty
  ) derives KindlingsYamlCodec

  final case class Invoke(
    command: String,
    args:    Option[List[String]] = None,
    cwd:     Option[String]       = None
  ) derives KindlingsYamlCodec

  final case class ModeFile(
    path:   String,
    format: String = "kv"   // currently only `kv` is supported
  ) derives KindlingsYamlCodec

  final case class OutputSpec(
    success: Option[Pattern] = None,
    failure: Option[Failure] = None
  ) derives KindlingsYamlCodec

  final case class Pattern(
    regex:   String,
    capture: Map[String, Int] = Map.empty
  ) derives KindlingsYamlCodec

  final case class Failure(
    `keep-lines-matching`: List[String] = Nil,
    `max-lines`:           Int          = 20
  ) derives KindlingsYamlCodec

  // -- Loader -------------------------------------------------------

  def load(file: File): Either[String, RunnersConfig] = {
    if (!file.exists()) Right(RunnersConfig(Map.empty))
    else {
      val src = Source.fromFile(file)
      val text =
        try src.mkString
        finally src.close()
      parse(text)
    }
  }

  def parse(yamlText: String): Either[String, RunnersConfig] =
    yamlText.as[RunnersConfig] match {
      case Left(err)  => Left(s"YAML parse error: $err")
      case Right(cfg) => Right(cfg)
    }
}
