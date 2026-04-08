/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Doctor engine — generic, project-agnostic dev-environment bootstrap
 * runner driven by `.rescale/doctor.yaml`.
 *
 * The pattern: each step is a CHECK command + an optional INSTALL
 * command. The check is run; if it succeeds, the step is OK. If the
 * check fails AND `--ci` was NOT passed (or the step's interactive
 * flag is false), the install command runs. If the install command
 * succeeds, the check is re-run; if it still fails, the step is
 * reported FAIL.
 *
 * The engine does NOT decide what tools a project needs. That's data,
 * lives in the project's own `.rescale/doctor.yaml`. sge ships
 * Rust + NDK + Zig steps; ssg ships sbt + git submodules; a Python
 * project ships pip steps. re-scale only knows how to run them.
 *
 * Schema:
 *
 *   steps:
 *     - id: jdk
 *       name: "JDK 22+ (Panama FFM)"
 *       check:
 *         command: java
 *         args: [-version]
 *         success-when:                # all sub-checks must pass
 *           exit-code: 0
 *           stderr-matches: 'openjdk version "(2[2-9]|[3-9][0-9])\.'
 *       install:
 *         command: sdk
 *         args: [install, java, 25.0.2-zulu]
 *         interactive: true            # skip in --ci mode
 *       hint: "Run: sdk install java 25.0.2-zulu"
 */
package rescale.doctor

import cats.effect.IO
import cats.syntax.all.*
import rescale.common.{Proc, Term}

object Doctor {

  enum StepResult {
    case Ok(stepId: String, name: String)
    case Skipped(stepId: String, name: String, reason: String)
    case Fail(stepId: String, name: String, reason: String, hint: Option[String])
  }

  /** Run every step in declaration order. Returns the per-step result
    * list AND a single Boolean for "everything is OK" (no Fail entries).
    */
  def run(config: DoctorConfig, ciMode: Boolean): IO[(List[StepResult], Boolean)] = {
    config.steps.traverse(step => runStep(step, ciMode)).map { results =>
      val allOk = results.forall {
        case _: StepResult.Ok      => true
        case _: StepResult.Skipped => true
        case _: StepResult.Fail    => false
      }
      (results, allOk)
    }
  }

  private def runStep(step: DoctorConfig.Step, ciMode: Boolean): IO[StepResult] = {
    val displayName = step.name.getOrElse(step.id)
    for {
      _      <- IO(Term.info(s"── ${step.id} : $displayName ──"))
      check1 <- runCheck(step.check)
      result <-
        if (check1) {
          IO(Term.ok(s"  ✓ already satisfied")).as(StepResult.Ok(step.id, displayName))
        } else {
          step.install match {
            case None =>
              val reason = "check failed and no install command configured"
              IO(Term.warn(s"  ✗ $reason")) *>
                step.hint.traverse_(h => IO(Term.warn(s"    hint: $h"))) *>
                IO.pure(StepResult.Fail(step.id, displayName, reason, step.hint))

            case Some(install) if ciMode && install.interactive.getOrElse(false) =>
              val reason = "interactive install skipped in --ci mode"
              IO(Term.warn(s"  ⊘ $reason")) *>
                step.hint.traverse_(h => IO(Term.warn(s"    hint: $h"))) *>
                IO.pure(StepResult.Skipped(step.id, displayName, reason))

            case Some(install) =>
              for {
                _       <- IO(Term.info(s"  → installing: ${install.command} ${install.args.getOrElse(Nil).mkString(" ")}"))
                exit    <- Proc.exec(install.command, install.args.getOrElse(Nil))
                check2  <- if (exit == 0) runCheck(step.check) else IO.pure(false)
                result  <-
                  if (check2)
                    IO(Term.ok(s"  ✓ installed and verified")).as(StepResult.Ok(step.id, displayName))
                  else {
                    val reason =
                      if (exit != 0) s"install command exited $exit"
                      else "install ran but check still failed"
                    IO(Term.err(s"  ✗ $reason")) *>
                      step.hint.traverse_(h => IO(Term.warn(s"    hint: $h"))) *>
                      IO.pure(StepResult.Fail(step.id, displayName, reason, step.hint))
                  }
              } yield result
          }
        }
    } yield result
  }

  /** Run the check sub-command and return whether all assertions pass. */
  private def runCheck(check: DoctorConfig.Check): IO[Boolean] = {
    Proc.run(check.command, check.args.getOrElse(Nil)).map { result =>
      val cond = check.`success-when`.getOrElse(DoctorConfig.SuccessWhen())
      val exitOk =
        cond.`exit-code` match {
          case Some(c) => result.exitCode == c
          case None    => result.exitCode == 0
        }
      val stdoutOk = cond.`stdout-contains` match {
        case Some(s) => result.stdout.contains(s)
        case None    => true
      }
      val stdoutMatchesOk = cond.`stdout-matches` match {
        case Some(re) =>
          try re.r.findFirstIn(result.stdout).isDefined
          catch { case _: Throwable => false }
        case None => true
      }
      val stderrContainsOk = cond.`stderr-contains` match {
        case Some(s) => result.stderr.contains(s)
        case None    => true
      }
      val stderrMatchesOk = cond.`stderr-matches` match {
        case Some(re) =>
          try re.r.findFirstIn(result.stderr).isDefined
          catch { case _: Throwable => false }
        case None => true
      }
      exitOk && stdoutOk && stdoutMatchesOk && stderrContainsOk && stderrMatchesOk
    }
  }
}
