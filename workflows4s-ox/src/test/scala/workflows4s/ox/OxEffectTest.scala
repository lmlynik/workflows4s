package workflows4s.ox

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.{Effect, Outcome}

class OxEffectTest extends AnyFreeSpec with Matchers {

  import OxEffect.given
  private val E = Effect[Direct]

  "OxEffect" - {
    "pure should wrap a value" in {
      E.pure(42).run shouldBe 42
    }

    "flatMap should sequence effects" in {
      val result = E.flatMap(E.pure(21))(x => E.pure(x * 2)).run
      result shouldBe 42
    }

    "raiseError should create a failed effect" in {
      val error  = new RuntimeException("test error")
      val result = E.attempt(E.raiseError[Int](error)).run
      result shouldBe Left(error)
    }

    "handleErrorWith should recover from errors" in {
      val error  = new RuntimeException("test error")
      val result = E.handleErrorWith(E.raiseError[Int](error))(_ => E.pure(42)).run
      result shouldBe 42
    }

    "delay should suspend side effects" in {
      var sideEffect = false
      val direct     = E.delay { sideEffect = true; 42 }
      sideEffect shouldBe false
      direct.run shouldBe 42
      sideEffect shouldBe true
    }

    "ref should provide mutable state" in {
      val result = (for {
        ref <- E.ref(0)
        _   <- ref.set(42)
        v   <- ref.get
      } yield v).run
      result shouldBe 42
    }

    "ref update should be atomic" in {
      val result = (for {
        ref <- E.ref(0)
        _   <- ref.update(_ + 1)
        _   <- ref.update(_ + 1)
        v   <- ref.get
      } yield v).run
      result shouldBe 2
    }

    "start should return completed fiber in direct style" in {
      val result = (for {
        fiber   <- E.start(E.pure(42))
        outcome <- fiber.join
      } yield outcome).run
      result shouldBe Outcome.Succeeded(42)
    }

    "guaranteeCase should run finalizer on success" in {
      var finalizerOutcome: Option[Outcome[Int]] = None
      val result                                 = E
        .guaranteeCase(E.pure(42)) { outcome =>
          E.delay { finalizerOutcome = Some(outcome) }
        }
        .run
      result shouldBe 42
      finalizerOutcome shouldBe Some(Outcome.Succeeded(42))
    }

    "guaranteeCase should run finalizer on error" in {
      var finalizerOutcome: Option[Outcome[Int]] = None
      val error                                  = new RuntimeException("test")
      val result                                 = E.attempt {
        E.guaranteeCase(E.raiseError[Int](error)) { outcome =>
          E.delay { finalizerOutcome = Some(outcome) }
        }
      }.run
      result shouldBe Left(error)
      finalizerOutcome shouldBe Some(Outcome.Errored(error))
    }

    "withLock should provide mutual exclusion" in {
      val mutex  = E.createMutex
      val result = E.withLock(mutex)(E.pure(42)).run
      result shouldBe 42
    }

    "map should transform results" in {
      val result = E.map(E.pure(21))(_ * 2).run
      result shouldBe 42
    }
  }
}
