/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package rescale.fileinfo

import hearth.kindlings.yamlderivation.{KindlingsYamlCodec, YamlConfig}
import org.virtuslab.yaml.*

import java.io.File
import scala.io.Source

private given YamlConfig = YamlConfig().withUseDefaults

final case class SourceReposConfig(
  `source-repos`: Map[String, String] = Map.empty
) derives KindlingsYamlCodec

object SourceReposConfig {

  val empty: SourceReposConfig = SourceReposConfig()

  def load(file: File): SourceReposConfig =
    if (!file.exists()) empty
    else {
      val src = Source.fromFile(file)
      val text = try src.mkString finally src.close()
      text.as[SourceReposConfig] match {
        case Left(_)    => empty
        case Right(cfg) => cfg
      }
    }

  def resolveGitRoot(
    sourceRepos: Map[String, String],
    sourcePath:  String,
    sourceLib:   Option[String],
    root:        File
  ): (File, String) = {
    val byPrefix = sourceRepos.values.find { subPath =>
      sourcePath.startsWith(subPath + "/")
    }
    byPrefix match {
      case Some(subPath) =>
        (new File(root, subPath), sourcePath.stripPrefix(subPath).stripPrefix("/"))
      case None =>
        sourceLib.flatMap(sourceRepos.get) match {
          case Some(subPath) => (new File(root, subPath), sourcePath)
          case None          => (root, sourcePath)
        }
    }
  }
}
