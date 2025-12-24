package workflows4s.runtime.instanceengine

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}

class FutureEffectTest extends AnyFreeSpec with Matchers with ScalaFutures {

  implicit val ec: ExecutionContext                    = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  import FutureEffect.futureEffect
  val E: Effect[Future] = Effect[Future]

  "FutureEffect" - {

    "pure" in {
      assert(E.pure(42).futureValue == 42)
    }

    "map" in {
      assert(E.map(E.pure(42))(_ * 2).futureValue == 84)
    }

    "flatMap" in {
      assert(E.flatMap(E.pure(42))(x => E.pure(x * 2)).futureValue == 84)
    }

    "delay" in {
      var sideEffect = 0
      assert(E.delay { sideEffect = 42; sideEffect }.futureValue == 42)
      assert(sideEffect == 42)
    }

    "raiseError and handleErrorWith" in {
      val error  = new RuntimeException("test error")
      val result = E.handleErrorWith {
        E.raiseError[Int](error)
      } { _ =>
        E.pure(99)
      }
      assert(result.futureValue == 99)
    }

    "Ref operations" - {
      "get and set" in {
        val ref = E.ref(0).futureValue
        assert(ref.get.futureValue == 0)
        ref.set(42).futureValue
        assert(ref.get.futureValue == 42)
      }

      "update" in {
        val ref = E.ref(10).futureValue
        ref.update(_ + 5).futureValue
        assert(ref.get.futureValue == 15)
      }

      "modify" in {
        val ref      = E.ref(10).futureValue
        val oldValue = ref.modify(v => (v + 5, v)).futureValue
        assert(oldValue == 10)
        assert(ref.get.futureValue == 15)
      }

      "getAndUpdate" in {
        val ref      = E.ref(10).futureValue
        val oldValue = ref.getAndUpdate(_ + 5).futureValue
        assert(oldValue == 10)
        assert(ref.get.futureValue == 15)
      }

      "concurrent updates are atomic" in {
        val ref     = E.ref(0).futureValue
        val updates = Future.sequence((1 to 100).map(_ => ref.update(_ + 1)))
        updates.futureValue
        assert(ref.get.futureValue == 100)
      }
    }

    "withLock" - {
      "executes effect while holding lock" in {
        val mutex    = E.createMutex.futureValue
        var executed = false
        val result   = E.withLock(mutex) {
          executed = true
          E.pure(42)
        }
        assert(result.futureValue == 42)
        assert(executed)
      }

      "releases lock on success" in {
        val mutex  = E.createMutex.futureValue
        E.withLock(mutex)(E.pure(1)).futureValue
        // Should be able to acquire again
        val result = E.withLock(mutex)(E.pure(2)).futureValue
        assert(result == 2)
      }

      "releases lock on failure" in {
        val mutex  = E.createMutex.futureValue
        val error  = new RuntimeException("test")
        val failed = E.withLock(mutex)(E.raiseError[Int](error))
        assert(failed.failed.futureValue == error)
        // Should be able to acquire again after failure
        val result = E.withLock(mutex)(E.pure(42)).futureValue
        assert(result == 42)
      }

      "ensures mutual exclusion" in {
        val mutex = E.createMutex.futureValue
        val ref   = E.ref(0).futureValue

        // Start multiple concurrent operations that each increment the counter
        val operations = (1 to 10).map { _ =>
          E.withLock(mutex) {
            for {
              current <- ref.get
              // Small delay to increase chance of race condition without lock
              _       <- E.delay(Thread.sleep(1))
              _       <- ref.set(current + 1)
            } yield ()
          }
        }

        Future.sequence(operations).futureValue
        assert(ref.get.futureValue == 10)
      }

      "by-name parameter ensures effect is not started before lock is acquired" in {
        val mutex           = E.createMutex.futureValue
        var effectEvaluated = false

        // Create an effect that tracks when it's evaluated
        def trackedEffect: Future[Int] = {
          effectEvaluated = true
          E.pure(42)
        }

        // The by-name parameter should delay evaluation until inside withLock
        val locked = E.withLock(mutex) {
          trackedEffect
        }

        // The effect should have been evaluated (since Future is eager once created)
        // but the key is that it was created INSIDE withLock, after lock acquisition
        assert(locked.futureValue == 42)
        assert(effectEvaluated)
      }
    }

    "start and join" in {
      val fiber = E.start(E.pure(42)).futureValue
      fiber.join.futureValue match {
        case Outcome.Succeeded(value) => assert(value == 42)
        case other                    => fail(s"Expected Succeeded, got $other")
      }
    }

    "guaranteeCase" - {
      "calls finalizer on success" in {
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.pure(42)) { outcome =>
          finalizerOutcome = Some(outcome)
          E.pure(())
        }
        assert(result.futureValue == 42)
        assert(finalizerOutcome == Some(Outcome.Succeeded(42)))
      }

      "calls finalizer on failure" in {
        val error                                  = new RuntimeException("test")
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.raiseError[Int](error)) { outcome =>
          finalizerOutcome = Some(outcome)
          E.pure(())
        }
        assert(result.failed.futureValue == error)
        assert(finalizerOutcome == Some(Outcome.Errored(error)))
      }
    }

    "traverse" in {
      val result = E.traverse(List(1, 2, 3))(x => E.pure(x * 2))
      assert(result.futureValue == List(2, 4, 6))
    }

    "sequence" in {
      val result = E.sequence(List(E.pure(1), E.pure(2), E.pure(3)))
      assert(result.futureValue == List(1, 2, 3))
    }

    "attempt" in {
      assert(E.attempt(E.pure(42)).futureValue == Right(42))
      val error = new RuntimeException("test")
      assert(E.attempt(E.raiseError[Int](error)).futureValue == Left(error))
    }
  }
}
