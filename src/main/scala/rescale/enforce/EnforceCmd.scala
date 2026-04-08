/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * `re-scale enforce` dispatcher — Phase 4 of the re-scale plan.
 *
 * Subcommands:
 *   shortcuts    Pattern scan for shortcut/stub markers
 *   stale-stubs  Cross-reference suspect comments against the def index
 *   verify       Re-verify a covenanted file (or all of them)
 *   skip-policy  CRUD on the skip-policy TSV
 *   compare      Strict source-vs-Scala method comparison
 *
 * Every command takes optional `--src` (one or more comma-separated
 * source roots). When omitted, source roots are auto-discovered via
 * Paths.scanTargets.
 */
package rescale.enforce

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import fs2.io.file.Path
import rescale.common.{Cli, Paths, Term}

import java.io.File
import java.time.LocalDate

object EnforceCmd {

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "shortcuts" :: rest    => shortcuts(Cli.parse(rest))
      case "stale-stubs" :: rest  => staleStubs(Cli.parse(rest))
      case "verify" :: rest       => verify(Cli.parse(rest))
      case "skip-policy" :: rest  => skipPolicy(rest)
      case "compare" :: rest      => compareCmd(Cli.parse(rest))
      case other :: _ =>
        IO(Term.err(s"Unknown enforce command: $other")).as(ExitCode.Error)
    }

  // -- shortcuts ----------------------------------------------------

  def shortcuts(args: Cli.Args): IO[ExitCode] = {
    for {
      roots          <- resolveSourceRoots(args)
      covenantedOnly  = args.hasFlag("covenanted")
      file            = args.flag("file").map(new File(_))
      policyFile     <- Paths.projectRoot.map(r => new File(Paths.dataDir(r), "skip-policy.tsv"))
      policy         <- SkipPolicy.read(policyFile)
      hits           <- file match {
                          case Some(f) => Shortcuts.scanFile(f)
                          case None    => roots.flatTraverse(r => Shortcuts.scanDir(r))
                        }
      finalHits       = SkipPolicy.filter[Shortcuts.Hit](policy, "shortcuts", _.file, hits)
      filtered       <- if (covenantedOnly) keepCovenanted(finalHits) else IO.pure(finalHits)
      _              <- IO(printShortcutHits(filtered))
    } yield {
      if (covenantedOnly && filtered.nonEmpty) ExitCode.Error
      else ExitCode.Success
    }
  }

  private def keepCovenanted(hits: List[Shortcuts.Hit]): IO[List[Shortcuts.Hit]] = {
    hits.traverseFilter { h =>
      Covenant.parse(new File(h.file)).map {
        case Some(header) if header.covenant == "full-port" => Some(h)
        case _                                              => None
      }
    }
  }

  private def printShortcutHits(hits: List[Shortcuts.Hit]): Unit = {
    if (hits.isEmpty) {
      println("No shortcut markers found")
      return
    }
    val byFile = hits.groupBy(_.file).toList.sortBy(-_._2.size)
    for ((f, fhits) <- byFile) {
      println(s"$f  (${fhits.size} hits)")
      fhits.sortBy(_.line).foreach { h =>
        println(f"  ${h.line}%5d  ${h.pattern}%-22s  ${h.text}")
      }
    }
    println(s"\nTotal: ${hits.size} hits in ${byFile.size} files")
  }

  // -- stale-stubs --------------------------------------------------

  def staleStubs(args: Cli.Args): IO[ExitCode] = {
    for {
      roots      <- resolveSourceRoots(args)
      _          <- IO(Term.info(s"Scanning ${roots.size} source root(s) for stale stubs (two-pass streaming)..."))
      policyFile <- Paths.projectRoot.map(r => new File(Paths.dataDir(r), "skip-policy.tsv"))
      policy     <- SkipPolicy.read(policyFile)
      hits       <- StaleStubs.scanList(roots)
      filtered    = SkipPolicy.filter[StaleStubs.StaleHit](policy, "stale-stubs", _.file, hits)
      _          <- IO(printStaleHits(filtered))
    } yield ExitCode.Success
  }

  private def printStaleHits(hits: List[StaleStubs.StaleHit]): Unit = {
    if (hits.isEmpty) {
      println("No stale stubs detected")
      return
    }
    val byFile = hits.groupBy(_.file).toList.sortBy(-_._2.size)
    for ((f, fhits) <- byFile) {
      println(s"$f  (${fhits.size} stale)")
      fhits.sortBy(_.line).foreach { h =>
        println(f"  ${h.line}%5d  ${h.identifier}%-30s  ${h.comment}")
      }
    }
    println(s"\nTotal: ${hits.size} stale stubs in ${byFile.size} files")
  }

  // -- verify -------------------------------------------------------

  def verify(args: Cli.Args): IO[ExitCode] = {
    val all = args.hasFlag("all")
    args.flag("file") match {
      case Some(f) =>
        val file = new File(f)
        Covenant.verify(file).flatMap {
          case Right(()) =>
            IO(Term.ok(s"verified: $f")).as(ExitCode.Success)
          case Left(reason) =>
            IO(Term.err(s"FAIL $f: $reason")).as(ExitCode.Error)
        }
      case None if all =>
        for {
          roots <- resolveSourceRoots(args)
          files <- roots.flatTraverse { r =>
                     fs2.io.file
                       .Files[IO]
                       .walk(Path.fromNioPath(r.toPath))
                       .filter(p => p.fileName.toString.endsWith(".scala"))
                       .compile
                       .toList
                   }
          results <- files.traverse { p =>
                       Covenant.verify(p).map(p -> _)
                     }
          failed = results.collect { case (p, Left(reason)) => (p, reason) }
          ok     = results.count(_._2.isRight)
          _ <- IO {
                 println(s"Verified: $ok of ${results.size} files")
                 if (failed.nonEmpty) {
                   println(s"Failed: ${failed.size}")
                   failed.foreach { case (p, reason) => println(s"  $p: $reason") }
                 }
               }
        } yield if (failed.nonEmpty) ExitCode.Error else ExitCode.Success
      case None =>
        IO(Term.err("Usage: re-scale enforce verify --file <path> | --all")).as(ExitCode.Error)
    }
  }

  // -- skip-policy --------------------------------------------------

  def skipPolicy(rest: List[String]): IO[ExitCode] = {
    rest match {
      case Nil | "list" :: _ =>
        for {
          policyFile <- Paths.projectRoot.map(r => new File(Paths.dataDir(r), "skip-policy.tsv"))
          entries    <- SkipPolicy.read(policyFile)
          _ <- IO {
                 if (entries.isEmpty) println("(no skip-policy entries)")
                 else
                   entries.foreach { e =>
                     println(f"${e.tool}%-12s ${e.path}%-50s ${e.reason}")
                   }
               }
        } yield ExitCode.Success
      case "add" :: more =>
        val parsed = Cli.parse(more)
        val pathArg = parsed.flag("path").orElse(parsed.positionalAt(0))
        val toolArg = parsed.flag("tool").orElse(parsed.positionalAt(1))
        val reason  = parsed.flag("reason").getOrElse("(no reason)")
        val addedBy = parsed.flag("added-by").getOrElse(sys.env.getOrElse("USER", "unknown"))
        (pathArg, toolArg) match {
          case (Some(p), Some(t)) =>
            val entry = SkipPolicy.Entry(p, t, reason, LocalDate.now().toString, addedBy)
            for {
              policyFile <- Paths.projectRoot.map(r => new File(Paths.dataDir(r), "skip-policy.tsv"))
              _          <- SkipPolicy.add(policyFile, entry)
              _          <- IO(Term.ok(s"Added skip-policy entry: $p ($t)"))
            } yield ExitCode.Success
          case _ =>
            IO(Term.err("Usage: re-scale enforce skip-policy add <path> <tool> [--reason ...]")).as(ExitCode.Error)
        }
      case other :: _ =>
        IO(Term.err(s"Unknown skip-policy subcommand: $other")).as(ExitCode.Error)
    }
  }

  // -- compare ------------------------------------------------------

  def compareCmd(args: Cli.Args): IO[ExitCode] = {
    val portFile   = args.flag("port").map(s => Path(s))
    val sourceFile = args.flag("source").map(s => Path(s))
    val strict     = args.hasFlag("strict")
    (portFile, sourceFile) match {
      case (Some(p), Some(s)) =>
        val gapIO = if (strict) Methods.strictCompare(p, s) else Methods.compare(p, s)
        gapIO.flatMap { gap =>
          IO {
            println(s"=== compare $p ←→ $s ===")
            if (gap.missing.nonEmpty) {
              println(s"Missing (${gap.missing.size}):")
              gap.missing.foreach(n => println(s"  - $n"))
            }
            if (gap.extra.nonEmpty) {
              println(s"Extra in port (${gap.extra.size}):")
              gap.extra.foreach(n => println(s"  + $n"))
            }
            if (gap.shortBody.nonEmpty) {
              println(s"Short bodies (${gap.shortBody.size}):")
              gap.shortBody.foreach(n => println(s"  ! $n"))
            }
            if (gap.droppedCtorArgs.nonEmpty) {
              println(s"Dropped ctor args (${gap.droppedCtorArgs.size}):")
              gap.droppedCtorArgs.foreach { case (cls, p) => println(s"  ~ $cls.$p") }
            }
            println(s"Common: ${gap.common.size}")
          }.as(
            if (gap.missing.nonEmpty || gap.shortBody.nonEmpty || gap.droppedCtorArgs.nonEmpty) ExitCode.Error
            else ExitCode.Success
          )
        }
      case _ =>
        IO(Term.err("Usage: re-scale enforce compare --port <scala> --source <java|dart> [--strict]")).as(ExitCode.Error)
    }
  }

  // -- helpers ------------------------------------------------------

  private def resolveSourceRoots(args: Cli.Args): IO[List[File]] =
    args.flag("src") match {
      case Some(spec) =>
        IO.pure(spec.split(",").toList.map(_.trim).filter(_.nonEmpty).map(new File(_)).filter(_.exists()))
      case None =>
        Paths.projectRoot.map(Paths.scanTargets)
    }

  private val usage: String =
    """Usage: re-scale enforce <command>
      |
      |Commands:
      |  shortcuts [--src DIRS] [--file F] [--covenanted]
      |  stale-stubs [--src DIRS]
      |  verify --file <path> | --all [--src DIRS]
      |  skip-policy [list | add <path> <tool> [--reason TEXT]]
      |  compare --port <scala> --source <java|dart> [--strict]""".stripMargin
}
