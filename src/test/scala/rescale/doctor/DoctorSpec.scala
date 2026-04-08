/*
 * Copyright (c) 2026 re-scale contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Tests for the Doctor engine. Uses real subprocesses (true/false/echo)
 * to exercise the check + install + ci-skip paths without needing a
 * mock Proc.
 */
package rescale.doctor

import munit.CatsEffectSuite
import rescale.doctor.DoctorConfig.{Check, Install, Step, SuccessWhen}

final class DoctorSpec extends CatsEffectSuite {

  test("step passes when check command exits 0 (true)") {
    val cfg = DoctorConfig(List(
      Step(id = "ok", name = Some("trivial"), check = Check(command = "true"))
    ))
    Doctor.run(cfg, ciMode = false).map { case (results, allOk) =>
      assert(allOk, s"expected allOk; results=$results")
      assert(results.head.isInstanceOf[Doctor.StepResult.Ok])
    }
  }

  test("step fails when check exits non-zero and no install") {
    val cfg = DoctorConfig(List(
      Step(id = "nope", check = Check(command = "false"))
    ))
    Doctor.run(cfg, ciMode = false).map { case (results, allOk) =>
      assert(!allOk, "expected failure")
      assert(results.head.isInstanceOf[Doctor.StepResult.Fail])
    }
  }

  test("step recovers when install fixes the check") {
    // First check fails (false), then install runs (true), then re-check
    // also runs (which is `false` again — so this should still fail).
    // Use this to verify the recovery PATH executes; we can't easily
    // simulate "check goes from false to true after install" without
    // a stateful side-effect.
    val cfg = DoctorConfig(List(
      Step(
        id = "always-false",
        check = Check(command = "false"),
        install = Some(Install(command = "true"))
      )
    ))
    Doctor.run(cfg, ciMode = false).map { case (results, allOk) =>
      // Install runs but check still fails on retry → Fail
      assert(!allOk)
      results.head match {
        case Doctor.StepResult.Fail(_, _, reason, _) =>
          assert(reason.contains("install ran but check still failed"), reason)
        case other => fail(s"expected Fail, got $other")
      }
    }
  }

  test("interactive install is skipped in --ci mode") {
    val cfg = DoctorConfig(List(
      Step(
        id = "interactive",
        check = Check(command = "false"),
        install = Some(Install(command = "true", interactive = Some(true))),
        hint = Some("Run sdk install java")
      )
    ))
    Doctor.run(cfg, ciMode = true).map { case (results, allOk) =>
      // Skipped doesn't count as failure
      assert(allOk, s"expected allOk; results=$results")
      results.head match {
        case Doctor.StepResult.Skipped(_, _, reason) =>
          assert(reason.contains("--ci"), reason)
        case other => fail(s"expected Skipped, got $other")
      }
    }
  }

  test("success-when: stderr-matches predicate") {
    // sh -c "echo hello 1>&2" emits "hello" on stderr.
    val cfg = DoctorConfig(List(
      Step(
        id = "stderr-check",
        check = Check(
          command = "sh",
          args = Some(List("-c", "echo hello 1>&2")),
          `success-when` = Some(SuccessWhen(`stderr-matches` = Some("hel(lo|p)")))
        )
      )
    ))
    Doctor.run(cfg, ciMode = false).map { case (results, allOk) =>
      assert(allOk, s"expected allOk; results=$results")
    }
  }

  test("success-when: stdout-contains negative case") {
    val cfg = DoctorConfig(List(
      Step(
        id = "neg",
        check = Check(
          command = "echo",
          args = Some(List("hello")),
          `success-when` = Some(SuccessWhen(`stdout-contains` = Some("not-here")))
        )
      )
    ))
    Doctor.run(cfg, ciMode = false).map { case (results, allOk) =>
      assert(!allOk)
    }
  }
}
