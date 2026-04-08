/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Project-root discovery. Generalized from ssg-dev's hardcoded
 * `build.sbt + ssg-md/` marker check: re-scale walks up the directory
 * tree looking for any of a configurable list of marker files and
 * returns the highest (= outermost) match.
 *
 * This lets re-scale work in any Scala, Rust, Node, Python, or plain
 * Git project without needing project-specific knowledge.
 */
package rescale.common

import cats.effect.IO

import java.io.File

object Paths {

  /** Default markers, in priority order. The walk stops at the first
    * directory containing any of these. Projects can override via
    * `discoverFrom(markers = ...)`.
    */
  val DefaultMarkers: List[String] = List(
    ".rescale",      // explicit opt-in config dir wins over everything
    "build.sbt",     // sbt
    "build.sc",      // mill
    "pom.xml",       // maven
    "Cargo.toml",    // rust
    "package.json",  // node
    "pyproject.toml",// python
    ".git"           // any git repo — the last-resort fallback
  )

  /** Walk up from `start` looking for any of `markers`. Returns the
    * first ancestor (including `start` itself) that contains at least
    * one marker, or None if we reach the filesystem root without a
    * match.
    */
  def discoverFrom(start: File, markers: List[String] = DefaultMarkers): Option[File] = {
    var dir: File = start.getAbsoluteFile
    while (dir != null) {
      if (markers.exists(m => new File(dir, m).exists())) return Some(dir)
      dir = dir.getParentFile
    }
    None
  }

  /** IO-wrapped version of [[discoverFrom]] for use in effectful
    * command pipelines.
    */
  def discover(markers: List[String] = DefaultMarkers): IO[Option[File]] =
    IO.delay(discoverFrom(new File(sys.props.getOrElse("user.dir", ".")), markers))

  /** IO-wrapped project-root lookup that throws if no marker is found.
    * Used by commands that cannot function without a project.
    */
  def projectRoot: IO[File] =
    discover().flatMap {
      case Some(root) => IO.pure(root)
      case None =>
        IO.raiseError(
          new RuntimeException(
            s"re-scale: no project marker found above ${sys.props.getOrElse("user.dir", ".")}. " +
              s"Looked for: ${DefaultMarkers.mkString(", ")}"
          )
        )
    }

  /** Locate the data directory. Prefers `.rescale/data` (explicit opt-in)
    * over `scripts/data` (legacy ssg-dev convention) over the project
    * root itself (fallback for brand-new projects).
    */
  def dataDir(root: File): File = {
    val explicit = new File(root, ".rescale/data")
    if (explicit.isDirectory) return explicit
    val legacy = new File(root, "scripts/data")
    if (legacy.isDirectory) return legacy
    explicit // return the preferred path even if it doesn't exist yet
  }

  /** IO version of [[dataDir]]. */
  def dataDirIO: IO[File] = projectRoot.map(dataDir)

  /** Canonical TSV paths inside [[dataDir]]. */
  def migrationTsv(root: File): File = new File(dataDir(root), "migration.tsv")
  def issuesTsv(root: File): File    = new File(dataDir(root), "issues.tsv")
  def auditTsv(root: File): File     = new File(dataDir(root), "audit.tsv")
  def skipPolicyTsv(root: File): File = new File(dataDir(root), "skip-policy.tsv")

  /** Config files (Phase 2 hook will read this). */
  def hookConfig(root: File): File = new File(root, ".claude-hook.yaml")
}
