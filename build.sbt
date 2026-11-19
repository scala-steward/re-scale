/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * re-scale: Scala Native porting toolkit + Claude Code plugin.
 *
 * One projectMatrix (`cli/`) built for the JVM and Scala Native. The native row is the shipping unit: CI links it
 * on each supported OS/architecture and the release attaches those executables to the published native module as
 * classified artifacts that `cs install` downloads. The JVM row is the fallback for platforms without an executable.
 * The version comes from git tags (sbt-kubuszok).
 */

import commandmatrix.extra.*
import kubuszok.sbt.*
import kubuszok.sbt.KubuszokPlugin.autoImport.*
import scala.scalanative.build.*

val scala3 = "3.8.3"
val platforms = List(VirtualAxis.jvm, VirtualAxis.native)

// Bumping these requires re-running the memory-bound test suite.
val catsEffectVersion = "3.7.0"
val fs2Version = "3.16.0"
val catsParseVersion = "1.1.0"
val munitVersion = "1.3.6" // 1.2.x pins Scala Native test-interface 0.5.10, below sbt-kubuszok's 0.5.12
val munitCeVersion = "2.2.0"
val kindlingsVersion = "0.1.0"

// Immix heap ceiling compiled into the executable, so no wrapper has to set it; GC_MAXIMUM_HEAP_SIZE still overrides.
val maxHeapSize = "1G"

// Coursier platform names (`cs install --install-platform`); the release attaches `re-scale-<platform>` from
// `nativeBinariesDir` as the artifact with that classifier, so an app descriptor can use `${platform}` directly.
val nativeBinaryPlatforms = Seq("x86_64-pc-linux", "aarch64-pc-linux", "x86_64-apple-darwin", "aarch64-apple-darwin")
val nativeBinariesDir = file(sys.env.getOrElse("RESCALE_NATIVE_BINARIES", "target/native-binaries"))

val dev = new DevProperties(scala213 = None, scala3 = Some(scala3), platforms = platforms)

val stage = taskKey[File]("Copy the linked executable and the bin/re-scale wrapper into target/stage/bin")
val install = taskKey[File]("Copy the linked executable to .build/re-scale-bin, which survives `clean`")

val publishSettings = Seq(
  organization := "com.kubuszok",
  homepage := Some(url("https://github.com/kubuszok/re-scale")),
  organizationHomepage := Some(url("https://kubuszok.com")),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  scmInfo := Some(
    ScmInfo(url("https://github.com/kubuszok/re-scale/"), "scm:git:git@github.com:kubuszok/re-scale.git")
  ),
  startYear := Some(2026),
  developers := List(
    Developer("MateuszKubuszok", "Mateusz Kubuszok", "", url("https://kubuszok.com"))
  ),
  projectType := ProjectType.ScalaLibrary
)

val nativeSettings = MatrixAction
  .ForPlatform(VirtualAxis.native)
  .Configure(
    _.settings(
      nativeConfig ~= { c =>
        c.withGC(GC.immix)
          .withMode(Mode.releaseFast)
          .withLTO(LTO.none)
          .withCompileOptions(c.compileOptions :+ s"-DGC_MAXIMUM_HEAP_SIZE=$maxHeapSize")
      },
      artifacts ++= nativeBinaryPlatforms.filter(p => (nativeBinariesDir / s"re-scale-$p").isFile).map { p =>
        Artifact(moduleName.value, "bin", "bin", p)
      },
      packagedArtifacts ++= Def.uncached {
        val converter = fileConverter.value
        nativeBinaryPlatforms.flatMap { p =>
          val f = nativeBinariesDir / s"re-scale-$p"
          if (f.isFile) Some(Artifact(moduleName.value, "bin", "bin", p) -> converter.toVirtualFile(f.toPath))
          else None
        }.toMap
      },
      stage := Def.uncached {
        val linked = fileConverter.value.toPath((Compile / nativeLink).value).toFile
        val stageDir = (ThisBuild / baseDirectory).value / "target" / "stage" / "bin"
        IO.createDirectory(stageDir)
        val binDest = stageDir / "re-scale-bin"
        val wrapperDest = stageDir / "re-scale"
        IO.copyFile(linked, binDest)
        IO.copyFile((ThisBuild / baseDirectory).value / "bin" / "re-scale", wrapperDest)
        binDest.setExecutable(true)
        wrapperDest.setExecutable(true)
        stageDir
      },
      install := Def.uncached {
        val linked = fileConverter.value.toPath((Compile / nativeLink).value).toFile
        val buildDir = (ThisBuild / baseDirectory).value / ".build"
        IO.createDirectory(buildDir)
        val dest = buildDir / "re-scale-bin"
        IO.copyFile(linked, dest)
        dest.setExecutable(true)
        streams.value.log.info(s"Installed re-scale-bin to $dest")
        dest
      },
      // The memory-bound specs spawn the staged wrapper; the native test binary may not run in the build root.
      Test / envVars += "RESCALE_STAGE_DIR" -> ((ThisBuild / baseDirectory).value / "target" / "stage" / "bin").getPath
    )
  )

lazy val cli = projectMatrix
  .in(file("cli"))
  .someVariations(List(scala3), platforms)((dev.only1VersionInIDE :+ nativeSettings) *)
  .settings(publishSettings)
  .settings(
    name := "re-scale",
    moduleName := "re-scale",
    description := "Scala porting toolkit CLI: Claude Code hook, enforcement gates and migration tracking",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Werror",
      "-Wunused:imports,privates,locals,nowarn",
      "-no-indent"
    ),
    Compile / mainClass := Some("rescale.Main"),
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "rescale" / "Version.scala"
      IO.write(
        file,
        s"""package rescale
           |
           |/** The build's version, from git tags. */
           |object Version {
           |  val value: String = "${version.value}"
           |}
           |""".stripMargin
      )
      Seq(file)
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "com.kubuszok" %% "kindlings-yaml-derivation" % kindlingsVersion,
      "com.kubuszok" %% "kindlings-jsoniter-json" % kindlingsVersion,
      "org.typelevel" %% "cats-parse" % catsParseVersion,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCeVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

// sbt-kubuszok 0.2.4 emits `<id>/test`, which sbt 2 caches ("No tests to run"), and no `<id>/doc`.
// 0.2.5 does both and registers the aliases itself (`al.commandAliases`); drop this subclass when upgrading.
lazy val al = new Aliases(published = Seq(cli)) {
  private def full(command: String): String =
    command
      .split(";")
      .map(_.trim)
      .map(s => if (s.endsWith("/test")) s.stripSuffix("/test") + "/testFull" else s)
      .mkString(" ; ")
  // The native row stages the executable after `clean`: its memory-bound specs spawn it.
  override def ci(platform: String, scalaBinary: String): String = {
    val steps = full(super.ci(platform, scalaBinary)).split(" ; ").toVector
    val staged = if (platform == "Native") steps.head +: "cliNative/stage" +: steps.tail else steps
    (staged ++ projectIds(published, platform, scalaBinary).map(id => s"$id/doc")).mkString(" ; ")
  }
  override def test(platform: String, scalaBinary: String): String = full(super.test(platform, scalaBinary))
}

lazy val root = project
  .in(file("."))
  .enablePlugins(KubuszokRootPlugin)
  .settings(name := "re-scale-root")
  .settings(
    Seq("JVM", "Native").flatMap { platform =>
      val suffix = platform.toLowerCase
      addCommandAlias(s"ci-$suffix-3", al.ci(platform, "3")) ++
        addCommandAlias(s"test-$suffix-3", al.test(platform, "3"))
    } ++
      addCommandAlias("stage", "cliNative/stage") ++
      addCommandAlias("install", "cliNative/install")
  )
  .aggregate(cli.projectRefs *)
