package workflows4s.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.{Effect, Outcome}

import scala.concurrent.duration.*

class CatsEffectTest extends AnyFreeSpec with Matchers {

  import CatsEffect.ioEffect
  val E: Effect[IO] = Effect[IO]

  "CatsEffect (IO)" - {

    "pure" in {
      assert(E.pure(42).unsafeRunSync() == 42)
    }

    "map" in {
      assert(E.map(E.pure(42))(_ * 2).unsafeRunSync() == 84)
    }

    "flatMap" in {
      assert(E.flatMap(E.pure(42))(x => E.pure(x * 2)).unsafeRunSync() == 84)
    }

    "delay" in {
      var sideEffect = 0
      val io         = E.delay { sideEffect = 42; sideEffect }
      assert(sideEffect == 0) // Not executed yet
      assert(io.unsafeRunSync() == 42)
      assert(sideEffect == 42)
    }

    "raiseError and handleErrorWith" in {
      val error  = new RuntimeException("test error")
      val result = E.handleErrorWith {
        E.raiseError[Int](error)
      } { _ =>
        E.pure(99)
      }
      assert(result.unsafeRunSync() == 99)
    }

    "Ref operations" - {
      "get and set" in {
        val program = for {
          ref <- E.ref(0)
          v1  <- ref.get
          _   <- ref.set(42)
          v2  <- ref.get
        } yield (v1, v2)

        assert(program.unsafeRunSync() == (0, 42))
      }

      "update" in {
        val program = for {
          ref <- E.ref(10)
          _   <- ref.update(_ + 5)
          v   <- ref.get
        } yield v

        assert(program.unsafeRunSync() == 15)
      }

      "modify" in {
        val program = for {
          ref      <- E.ref(10)
          oldValue <- ref.modify(v => (v + 5, v))
          newValue <- ref.get
        } yield (oldValue, newValue)

        assert(program.unsafeRunSync() == (10, 15))
      }

      "getAndUpdate" in {
        val program = for {
          ref      <- E.ref(10)
          oldValue <- ref.getAndUpdate(_ + 5)
          newValue <- ref.get
        } yield (oldValue, newValue)

        assert(program.unsafeRunSync() == (10, 15))
      }

      "concurrent updates are atomic" in {
        val program = for {
          ref    <- E.ref(0)
          _      <- IO.parTraverseN(10)((1 to 100).toList)(_ => ref.update(_ + 1))
          result <- ref.get
        } yield result

        assert(program.unsafeRunSync() == 100)
      }
    }

    "withLock" - {
      "executes effect while holding lock" in {
        val program           = for {
          mutex  <- E.createMutex
          result <- {
            var executed = false
            E.withLock(mutex) {
              executed = true
              E.pure((42, executed))
            }
          }
        } yield result
        val (value, executed) = program.unsafeRunSync()
        assert(value == 42)
        assert(executed)
      }

      "releases lock on success" in {
        val program = for {
          mutex <- E.createMutex
          _     <- E.withLock(mutex)(E.pure(1))
          // Should be able to acquire again
          r     <- E.withLock(mutex)(E.pure(2))
        } yield r
        assert(program.unsafeRunSync() == 2)
      }

      "releases lock on error" in {
        val error       = new RuntimeException("test")
        val program     = for {
          mutex  <- E.createMutex
          failed <- E.withLock(mutex)(E.raiseError[Int](error)).attempt
          // Should be able to acquire again after error
          r      <- E.withLock(mutex)(E.pure(42))
        } yield (failed, r)
        val (failed, r) = program.unsafeRunSync()
        assert(failed == Left(error))
        assert(r == 42)
      }

      "ensures mutual exclusion" in {
        val program = for {
          mutex  <- E.createMutex
          ref    <- E.ref(0)
          // Start multiple concurrent operations that each increment the counter
          _      <- IO.parTraverseN(10)((1 to 10).toList) { _ =>
                      E.withLock(mutex) {
                        for {
                          current <- ref.get
                          // Small delay to increase chance of race condition without lock
                          _       <- IO.sleep(1.millis)
                          _       <- ref.set(current + 1)
                        } yield ()
                      }
                    }
          result <- ref.get
        } yield result

        assert(program.unsafeRunSync() == 10)
      }
    }

    "start and join" in {
      val program = for {
        fiber   <- E.start(E.pure(42))
        outcome <- fiber.join
      } yield outcome

      program.unsafeRunSync() match {
        case Outcome.Succeeded(value) => assert(value == 42)
        case other                    => fail(s"Expected Succeeded, got $other")
      }
    }

    "fiber cancellation" in {
      import cats.effect.kernel.Deferred

      val program = for {
        deferred <- Deferred[IO, Unit]
        fiber    <- E.start {
                      // This will never complete naturally
                      deferred.get *> E.pure(42)
                    }
        _        <- fiber.cancel
        outcome  <- fiber.join
      } yield outcome

      assert(program.unsafeRunSync() == Outcome.Canceled)
    }

    "guaranteeCase" - {
      "calls finalizer on success" in {
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.pure(42)) { outcome =>
          IO { finalizerOutcome = Some(outcome) }
        }
        assert(result.unsafeRunSync() == 42)
        assert(finalizerOutcome == Some(Outcome.Succeeded(42)))
      }

      "calls finalizer on error" in {
        val error                                  = new RuntimeException("test")
        var finalizerOutcome: Option[Outcome[Int]] = None
        val result                                 = E.guaranteeCase(E.raiseError[Int](error)) { outcome =>
          IO { finalizerOutcome = Some(outcome) }
        }
        an[RuntimeException] should be thrownBy result.unsafeRunSync()
        assert(finalizerOutcome == Some(Outcome.Errored(error)))
      }
    }

    "sleep" in {
      val start   = System.currentTimeMillis()
      E.sleep(50.millis).unsafeRunSync()
      val elapsed = System.currentTimeMillis() - start
      assert(elapsed >= 50L)
    }

    "traverse" in {
      val result = E.traverse(List(1, 2, 3))(x => E.pure(x * 2))
      assert(result.unsafeRunSync() == List(2, 4, 6))
    }

    "sequence" in {
      val result = E.sequence(List(E.pure(1), E.pure(2), E.pure(3)))
      assert(result.unsafeRunSync() == List(1, 2, 3))
    }

    "attempt" in {
      assert(E.attempt(E.pure(42)).unsafeRunSync() == Right(42))
      val error = new RuntimeException("test")
      assert(E.attempt(E.raiseError[Int](error)).unsafeRunSync() == Left(error))
    }
  }
}
