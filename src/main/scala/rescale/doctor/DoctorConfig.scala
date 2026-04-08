/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `.rescale/doctor.yaml` schema + loader.
 *
 * Schema example:
 *
 *   steps:
 *     - id: jdk
 *       name: "JDK 22+ (Panama FFM)"
 *       check:
 *         command: java
 *         args: [-version]
 *         success-when:
 *           stderr-matches: 'openjdk version "(2[2-9]|[3-9][0-9])\.'
 *       install:
 *         command: sdk
 *         args: [install, java, 25.0.2-zulu]
 *         interactive: true
 *       hint: "Run: sdk install java 25.0.2-zulu"
 *
 *     - id: rust-targets
 *       name: "Rust cross-compile targets"
 *       check:
 *         command: rustup
 *         args: [target, list, --installed]
 *         success-when:
 *           stdout-contains: aarch64-apple-darwin
 *       install:
 *         command: rustup
 *         args: [target, add, aarch64-apple-darwin, x86_64-apple-darwin]
 */
package rescale.doctor

import hearth.kindlings.yamlderivation.{KindlingsYamlCodec, YamlConfig}
import org.virtuslab.yaml.*

import java.io.File
import scala.io.Source

// useDefaults so optional fields with `= None` work transparently.
given YamlConfig = YamlConfig().withUseDefaults

final case class DoctorConfig(steps: List[DoctorConfig.Step] = Nil) derives KindlingsYamlCodec

object DoctorConfig {

  final case class Step(
    id:      String,
    name:    Option[String]   = None,
    check:   Check,
    install: Option[Install]  = None,
    hint:    Option[String]   = None
  ) derives KindlingsYamlCodec

  final case class Check(
    command:        String,
    args:           Option[List[String]] = None,
    `success-when`: Option[SuccessWhen]  = None
  ) derives KindlingsYamlCodec

  final case class SuccessWhen(
    `exit-code`:        Option[Int]    = None,
    `stdout-contains`:  Option[String] = None,
    `stdout-matches`:   Option[String] = None,
    `stderr-contains`:  Option[String] = None,
    `stderr-matches`:   Option[String] = None
  ) derives KindlingsYamlCodec

  final case class Install(
    command:     String,
    args:        Option[List[String]] = None,
    interactive: Option[Boolean]      = None
  ) derives KindlingsYamlCodec

  // -- Loader -------------------------------------------------------

  /** Load a doctor config from a file. Returns Right(empty) when the
    * file doesn't exist (so commands can present a friendly "no config"
    * message instead of crashing).
    */
  def load(file: File): Either[String, DoctorConfig] = {
    if (!file.exists()) Right(DoctorConfig(Nil))
    else {
      val src = Source.fromFile(file)
      val text =
        try src.mkString
        finally src.close()
      parse(text)
    }
  }

  /** Parse a YAML string into a typed DoctorConfig. */
  def parse(yamlText: String): Either[String, DoctorConfig] =
    yamlText.as[DoctorConfig] match {
      case Left(err)  => Left(s"YAML parse error: $err")
      case Right(cfg) => Right(cfg)
    }
}
