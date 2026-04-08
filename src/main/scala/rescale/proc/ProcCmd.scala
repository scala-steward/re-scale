/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Process discovery + targeted termination — Phase 5 of the re-scale plan.
 *
 * Why this exists: sbt servers, java/metals daemons, and Scala Native
 * test runners frequently get orphaned across worktrees and projects.
 * The legacy ssg-dev had `proc list/kill/kill-sbt` but couldn't filter
 * by working directory, so killing "all sbt servers" risked nuking
 * unrelated worktrees' running builds. This rewrite adds:
 *
 *   - Per-process working directory (via lsof on macOS, /proc on Linux)
 *   - Per-process CPU and memory usage (via ps)
 *   - Filtering by --dir DIR  → only processes whose cwd is under DIR
 *   - Filtering by --kind     → sbt | java | metals | all
 *
 * The kill commands take the same filters, so `re-scale proc kill --kind sbt
 * --dir /path/to/repo` only terminates sbt servers running inside that
 * specific repo, leaving everything else alone.
 *
 * Subcommands:
 *
 *   re-scale proc list [--kind sbt|java|metals|all] [--dir DIR]
 *     Show pid, %cpu, %mem, kind, cwd, command line. Default kind = all.
 *
 *   re-scale proc kill --pid N
 *     Kill a single pid. Sends SIGTERM, then SIGKILL after 2s if still alive.
 *
 *   re-scale proc kill --kind sbt|java|metals|all [--dir DIR]
 *     Kill every matching process. Refuses --kind all without --dir
 *     to prevent nuking unrelated processes.
 *
 *   re-scale proc kill-sbt [--dir DIR]
 *     Convenience alias for `kill --kind sbt`. Without --dir, kills
 *     EVERY sbt server on the machine — confirm before running.
 */
package rescale.proc

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import rescale.common.{Cli, Proc, Term}

import java.io.File

object ProcCmd {

  /** Categorization of a process — used for filtering and display. */
  enum Kind {
    case Sbt    // sbt-launch.jar / sbt-launcher.jar
    case Metals // metals-language-server
    case Java   // any other java process
    case Other  // anything else (only shown when --kind all and not filtered out)
  }

  /** Summary record for one process. cwd may be empty if lsof / /proc
    * couldn't resolve it (some root-owned daemons hide their cwd).
    */
  final case class ProcInfo(
    pid:     Long,
    pcpu:    Double,
    pmem:    Double,
    kind:    Kind,
    cwd:     String,
    command: String
  )

  // -- Dispatch -----------------------------------------------------

  def run(args: List[String]): IO[ExitCode] =
    args match {
      case Nil | "--help" :: _ =>
        IO.println(usage).as(ExitCode.Success)
      case "list" :: rest     => list(Cli.parse(rest))
      case "kill" :: rest     => kill(Cli.parse(rest))
      case "kill-sbt" :: rest =>
        // kill-sbt is sugar for kill --kind sbt
        val parsed = Cli.parse(rest)
        val withKind = parsed.copy(flags = parsed.flags + ("kind" -> "sbt"))
        kill(withKind)
      case other :: _ =>
        IO(Term.err(s"Unknown proc command: $other")).as(ExitCode.Error)
    }

  // -- list ---------------------------------------------------------

  def list(args: Cli.Args): IO[ExitCode] = {
    val kindFilter = parseKindFilter(args.flag("kind").getOrElse("all"))
    val dirFilter  = args.flag("dir").map(d => new File(d).getAbsoluteFile)
    for {
      procs <- discover()
      filtered = filterProcs(procs, kindFilter, dirFilter)
      _ <- IO(printProcs(filtered))
    } yield ExitCode.Success
  }

  // -- kill ---------------------------------------------------------

  def kill(args: Cli.Args): IO[ExitCode] = {
    args.flag("pid") match {
      case Some(pidStr) =>
        pidStr.toLongOption match {
          case Some(pid) => killOne(pid).as(ExitCode.Success)
          case None      => IO(Term.err(s"Invalid pid: $pidStr")).as(ExitCode.Error)
        }
      case None =>
        val kindFilter = parseKindFilter(args.flag("kind").getOrElse("sbt"))
        val dirFilter  = args.flag("dir").map(d => new File(d).getAbsoluteFile)
        if (kindFilter.isEmpty && dirFilter.isEmpty) {
          IO(Term.err("Refusing to kill processes without --kind or --dir filter. " +
            "Use `re-scale proc kill --kind all --dir /path` if you really mean it."))
            .as(ExitCode.Error)
        } else {
          for {
            procs   <- discover()
            matched  = filterProcs(procs, kindFilter, dirFilter)
            _       <- if (matched.isEmpty)
                         IO(Term.warn("No matching processes to kill"))
                       else
                         IO(Term.info(s"Killing ${matched.size} process(es)..."))
            _       <- matched.traverse_(p => killOne(p.pid))
          } yield ExitCode.Success
        }
    }
  }

  /** Send SIGTERM, wait 2s, escalate to SIGKILL if still alive. */
  private def killOne(pid: Long): IO[Unit] = {
    for {
      _     <- IO(Term.info(s"  → SIGTERM $pid"))
      _     <- Proc.signalProcess(pid, 15)
      _     <- IO.sleep(scala.concurrent.duration.DurationInt(2).seconds)
      alive <- Proc.isAlive(pid)
      _     <- if (alive)
                 IO(Term.warn(s"  → still alive, SIGKILL $pid")) *>
                 Proc.signalProcess(pid, 9).void
               else
                 IO(Term.ok(s"  ✓ $pid stopped"))
    } yield ()
  }

  // -- discovery ----------------------------------------------------

  /** Discover all sbt/java/metals processes via `ps`. Each entry is
    * augmented with its working directory (via lsof on macOS, /proc on
    * Linux) when possible.
    */
  def discover(): IO[List[ProcInfo]] = {
    Proc.run("ps", List("-axwwo", "pid,pcpu,pmem,args")).flatMap { result =>
      if (!result.ok) IO.pure(Nil)
      else {
        val raw = parsePsOutput(result.stdout)
        // Resolve cwd for each java-ish process. Skip non-jvm processes
        // because their cwd usually isn't relevant to re-scale users.
        raw.traverse { entry =>
          if (entry.kind == Kind.Other) IO.pure(entry)
          else cwdOf(entry.pid).map(cwd => entry.copy(cwd = cwd))
        }
      }
    }
  }

  /** Parse `ps -axwwo pid,pcpu,pmem,args` output into ProcInfo records.
    * Skips the header line. The pcpu/pmem columns are floats.
    *
    * The `args` column may itself contain whitespace, so we split into
    * 4 parts only: the first 3 are pid, pcpu, pmem; everything after
    * is the command.
    */
  private[proc] def parsePsOutput(stdout: String): List[ProcInfo] = {
    val lines = stdout.split("\n").toList
    lines.iterator
      .drop(1) // header
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        val parts = line.split("\\s+", 4)
        if (parts.length < 4) None
        else
          for {
            pid  <- parts(0).toLongOption
            cpu  <- parts(1).toDoubleOption
            mem  <- parts(2).toDoubleOption
          } yield {
            val cmd  = parts(3)
            val kind = classifyCommand(cmd)
            ProcInfo(pid, cpu, mem, kind, cwd = "", command = cmd)
          }
      }
      .toList
  }

  private[proc] def classifyCommand(cmd: String): Kind = {
    val lower = cmd.toLowerCase
    if (lower.contains("sbt-launch") || lower.contains("sbt-launcher") || lower.contains("xsbt.boot")) Kind.Sbt
    else if (lower.contains("metals-language-server") || lower.contains("scalameta.metals")) Kind.Metals
    else if (lower.contains("java ") || lower.startsWith("java") || lower.contains("/java ") || lower.contains("/java\t")) Kind.Java
    else Kind.Other
  }

  /** Resolve a process's working directory. macOS: via `lsof -a -d cwd
    * -p <pid> -Fn`. Linux: via `readlink /proc/<pid>/cwd`. Returns ""
    * on failure.
    */
  private def cwdOf(pid: Long): IO[String] = {
    val isMac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")
    if (isMac) {
      Proc.run("lsof", List("-a", "-d", "cwd", "-p", pid.toString, "-Fn")).map { r =>
        if (!r.ok) ""
        else {
          // -Fn output: lines starting with `n` are the names (paths).
          // Pick the first non-empty one.
          r.stdout.split("\n").iterator
            .map(_.trim)
            .filter(_.startsWith("n"))
            .map(_.drop(1))
            .find(_.nonEmpty)
            .getOrElse("")
        }
      }
    } else {
      Proc.run("readlink", List("-f", s"/proc/$pid/cwd")).map { r =>
        if (r.ok) r.stdout.trim else ""
      }
    }
  }

  // -- filtering ----------------------------------------------------

  /** Empty kind filter = include all. Otherwise only include the
    * listed kinds. Empty dir filter = no cwd restriction.
    */
  private def filterProcs(
    procs:  List[ProcInfo],
    kinds:  Set[Kind],
    dir:    Option[File]
  ): List[ProcInfo] = {
    val byKind =
      if (kinds.isEmpty) procs
      else procs.filter(p => kinds.contains(p.kind))
    dir match {
      case None => byKind
      case Some(d) =>
        val absPath = d.getAbsolutePath
        byKind.filter(p => p.cwd.nonEmpty && p.cwd.startsWith(absPath))
    }
  }

  /** Parse the `--kind` flag value into a Set of Kinds. Accepts
    * comma-separated lists and the special value `all`.
    *
    *   --kind sbt          → {Sbt}
    *   --kind sbt,metals   → {Sbt, Metals}
    *   --kind java         → {Java}
    *   --kind all          → empty (no filter)
    */
  private[proc] def parseKindFilter(spec: String): Set[Kind] = {
    if (spec.equalsIgnoreCase("all") || spec.isEmpty) Set.empty
    else
      spec.split(",").iterator.map(_.trim.toLowerCase).flatMap {
        case "sbt"    => Some(Kind.Sbt)
        case "java"   => Some(Kind.Java)
        case "metals" => Some(Kind.Metals)
        case "other"  => Some(Kind.Other)
        case _        => None
      }.toSet
  }

  // -- printing -----------------------------------------------------

  private def printProcs(procs: List[ProcInfo]): Unit = {
    if (procs.isEmpty) {
      println("(no matching processes)")
      return
    }
    println(f"${"PID"}%-7s  ${"%CPU"}%-5s  ${"%MEM"}%-5s  ${"KIND"}%-7s  ${"CWD"}%-50s  COMMAND")
    procs.sortBy(p => (-p.pcpu, p.pid)).foreach { p =>
      val kindStr = p.kind.toString.toUpperCase
      val cwdStr  = if (p.cwd.length > 50) "…" + p.cwd.takeRight(49) else p.cwd
      val cmdTrunc = if (p.command.length > 80) p.command.take(77) + "..." else p.command
      println(f"${p.pid}%-7d  ${p.pcpu}%-5.1f  ${p.pmem}%-5.1f  $kindStr%-7s  $cwdStr%-50s  $cmdTrunc")
    }
    println(s"\nTotal: ${procs.size} process(es)")
  }

  private val usage: String =
    """Usage: re-scale proc <command>
      |
      |Commands:
      |  list [--kind sbt|java|metals|all] [--dir DIR]
      |    Show pid, %cpu, %mem, kind, cwd, command line.
      |    Default kind = all. --dir filters by working directory.
      |
      |  kill --pid N
      |    Kill a single pid. SIGTERM, then SIGKILL after 2s if still alive.
      |
      |  kill --kind sbt|java|metals|all [--dir DIR]
      |    Kill every matching process. `--kind all` requires `--dir`.
      |
      |  kill-sbt [--dir DIR]
      |    Sugar for `kill --kind sbt`. Without --dir, kills EVERY sbt
      |    server on the machine — confirm before running.""".stripMargin
}
