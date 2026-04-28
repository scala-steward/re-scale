/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * re-scale — Scala Native porting toolkit + Claude plugin
 *
 * Single-module Scala Native project. Produces a standalone binary
 * (`target/scala-3.3.x/re-scale-out`) plus a wrapper shell script at
 * `bin/re-scale.sh` that sets the mandatory SCALANATIVE_MAX_HEAP_SIZE
 * ceiling. The wrapper + binary pair is the shipping unit — the binary
 * is never invoked directly.
 *
 * Test framework: munit + munit-cats-effect3. The 944-file memory-bound
 * test (Ssg944FileMemoryBoundSpec) is the headline acceptance gate and
 * must stay red until the FS2 streaming refactor in Phases 1/4 lands.
 */

import scala.scalanative.build.*

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "com.kubuszok"
ThisBuild / version      := "0.1.5"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Werror",
  "-Wunused:imports,privates,locals,nowarn",
  "-no-indent"
)

// Dependency versions — pinned to the latest Scala Native-compatible
// releases as of 2026-04. Bumping these requires re-running the full
// memory-bound test suite.
val catsEffectVersion = "3.7.0"
val fs2Version        = "3.13.0"
val catsParseVersion  = "1.1.0"
val munitVersion      = "1.2.4"
val munitCeVersion    = "2.2.0"
// Kindlings — Hearth-macro-based YAML and JSON derivation. Brings in
// org.virtuslab::scala-yaml transitively.
val kindlingsVersion  = "0.1.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "re-scale",
    // Scala Native build config
    nativeConfig ~= { c =>
      c.withGC(GC.immix)            // Immix GC; wrapper script caps heap at 1G
        .withMode(Mode.releaseFast) // fast-compile optimized builds
        .withLTO(LTO.none)          // LTO adds link time; not needed for a CLI
    },
    libraryDependencies ++= Seq(
      "org.typelevel"  %%% "cats-effect"               % catsEffectVersion,
      "co.fs2"         %%% "fs2-core"                  % fs2Version,
      "co.fs2"         %%% "fs2-io"                    % fs2Version,
      "com.kubuszok"   %%% "kindlings-yaml-derivation" % kindlingsVersion,
      "com.kubuszok"   %%% "kindlings-jsoniter-json"   % kindlingsVersion,
      "org.typelevel"  %%% "cats-parse"                % catsParseVersion,
      "org.scalameta"  %%% "munit"                     % munitVersion   % Test,
      "org.typelevel"  %%% "munit-cats-effect"         % munitCeVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // `sbt stage` copies the linked binary + wrapper into target/stage/bin/
    // (used by CI and memory-bound tests that spawn a subprocess)
    TaskKey[File]("stage") := {
      val linked   = (Compile / nativeLink).value
      val stageDir = target.value / "stage" / "bin"
      IO.createDirectory(stageDir)
      val binDest     = stageDir / "re-scale-bin"
      val wrapperDest = stageDir / "re-scale"
      IO.copyFile(linked, binDest)
      IO.copyFile(baseDirectory.value / "bin" / "re-scale", wrapperDest)
      binDest.setExecutable(true)
      wrapperDest.setExecutable(true)
      stageDir
    },
    // `sbt install` builds the native binary and copies it to .build/
    // which survives `sbt clean`. The bin/re-scale wrapper finds it
    // there at runtime. This is the primary way to get a working
    // re-scale binary — no separate install.sh needed.
    TaskKey[File]("install") := {
      val linked   = (Compile / nativeLink).value
      val buildDir = baseDirectory.value / ".build"
      IO.createDirectory(buildDir)
      val dest = buildDir / "re-scale-bin"
      IO.copyFile(linked, dest)
      dest.setExecutable(true)
      streams.value.log.info(s"Installed re-scale-bin to $dest")
      dest
    }
  )
