package workflows4s.zio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.{Effect, Outcome}
import zio.*

class ZIOEffectTest extends AnyFreeSpec with Matchers {

  import ZIOEffect.given
  private val E = Effect[Task]

  private def runSync[A](task: Task[A]): A = Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe.run(task).getOrThrow()
  }

  "ZIOEffect" - {
    "pure should wrap a value" in {
      runSync(E.pure(42)) shouldBe 42
    }

    "flatMap should sequence effects" in {
      val result = runSync {
        E.flatMap(E.pure(21))(x => E.pure(x * 2))
      }
      result shouldBe 42
    }

    "raiseError should create a failed effect" in {
      val error  = new RuntimeException("test error")
      val result = runSync(E.attempt(E.raiseError[Int](error)))
      result shouldBe Left(error)
    }

    "handleErrorWith should recover from errors" in {
      val error  = new RuntimeException("test error")
      val result = runSync {
        E.handleErrorWith(E.raiseError[Int](error))(_ => E.pure(42))
      }
      result shouldBe 42
    }

    "delay should suspend side effects" in {
      var sideEffect = false
      val task       = E.delay { sideEffect = true; 42 }
      sideEffect shouldBe false
      runSync(task) shouldBe 42
      sideEffect shouldBe true
    }

    "ref should provide mutable state" in {
      val result = runSync {
        for {
          ref <- E.ref(0)
          _   <- ref.set(42)
          v   <- ref.get
        } yield v
      }
      result shouldBe 42
    }

    "start should run effects in background" in {
      val result = runSync {
        for {
          fiber   <- E.start(E.pure(42))
          outcome <- fiber.join
        } yield outcome
      }
      result shouldBe Outcome.Succeeded(42)
    }

    "guaranteeCase should run finalizer on success" in {
      var finalizerOutcome: Option[Outcome[Int]] = None
      val result                                 = runSync {
        E.guaranteeCase(E.pure(42)) { outcome =>
          E.delay { finalizerOutcome = Some(outcome) }
        }
      }
      result shouldBe 42
      finalizerOutcome shouldBe Some(Outcome.Succeeded(42))
    }

    "guaranteeCase should run finalizer on error" in {
      var finalizerOutcome: Option[Outcome[Int]] = None
      val error                                  = new RuntimeException("test")
      val result                                 = runSync {
        E.attempt {
          E.guaranteeCase(E.raiseError[Int](error)) { outcome =>
            E.delay { finalizerOutcome = Some(outcome) }
          }
        }
      }
      result shouldBe Left(error)
      finalizerOutcome shouldBe Some(Outcome.Errored(error))
    }

    "withLock should provide mutual exclusion" in {
      val mutex  = E.createMutex
      val result = runSync {
        E.withLock(mutex)(E.pure(42))
      }
      result shouldBe 42
    }
  }
}
