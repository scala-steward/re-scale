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

  /** Heuristically discover module names inside a project. A "module"
    * is an immediate subdirectory of the project root that contains
    * `src/main/scala`. Used by `re-scale build` / `test verify` to
    * iterate over every sub-project without hardcoding the SSG module
    * list.
    *
    * Reads `.rescale/modules.txt` if present (one module name per
    * line, `#` comments). Otherwise discovers from the directory tree.
    */
  def moduleNames(root: File): List[String] = {
    val cfg = new File(root, ".rescale/modules.txt")
    if (cfg.isFile) {
      val src = scala.io.Source.fromFile(cfg)
      try {
        src.getLines().toList
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.startsWith("#"))
      } finally src.close()
    } else {
      val children = Option(root.listFiles()).getOrElse(Array.empty[File]).toList
      children
        .filter(c => c.isDirectory && new File(c, "src/main/scala").isDirectory)
        .map(_.getName)
        .sorted
    }
  }

  /** Heuristically discover scannable source roots inside a project.
    *
    * Strategy:
    *   1. If `.rescale/scan-targets.txt` exists, read one path per line
    *      (relative to root). Comment lines starting with `#` are skipped.
    *   2. Otherwise, look for any directory that contains a `src/main/scala`
    *      subtree, and return each match. This works for sbt module roots
    *      like `ssg-md/`, `ssg-liquid/`, etc. without requiring config.
    *   3. Fall back to `[root/src/main/scala]` if a single-module project.
    *
    * Returns absolute paths. Non-existent paths are dropped.
    */
  def scanTargets(root: File): List[File] = {
    val cfg = new File(root, ".rescale/scan-targets.txt")
    if (cfg.isFile) {
      val src = scala.io.Source.fromFile(cfg)
      try {
        val lines = src.getLines().toList
        lines
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.startsWith("#"))
          .map(p => new File(root, p))
          .filter(_.exists())
      } finally src.close()
    } else {
      val children = Option(root.listFiles()).getOrElse(Array.empty[File]).toList
      val moduleRoots = children.filter { c =>
        c.isDirectory && new File(c, "src/main/scala").isDirectory
      }
      val moduleSrcs = moduleRoots.map(m => new File(m, "src/main/scala"))
      if (moduleSrcs.nonEmpty) moduleSrcs
      else {
        val single = new File(root, "src/main/scala")
        if (single.isDirectory) List(single) else Nil
      }
    }
  }
}
