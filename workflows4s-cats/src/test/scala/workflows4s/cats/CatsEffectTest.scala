package workflows4s.cats

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.{Effect, EffectTestSuite, Outcome}

import scala.util.{Failure, Success, Try}

class CatsEffectTest extends AnyFreeSpec with EffectTestSuite[IO] with Matchers {

  import CatsEffect.ioEffect
  given effect: Effect[IO] = Effect[IO]

  override def assertFailsWith[A](fa: IO[A]): Throwable = {
    Try(effect.runSyncUnsafe(fa)) match {
      case Failure(e) => e
      case Success(_) => fail("Expected failure but got success")
    }
  }

  "CatsEffect (IO)" - {
    effectTests()

    // Effect-specific tests below

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

      assert(effect.runSyncUnsafe(program) == Outcome.Canceled)
    }
  }
}
