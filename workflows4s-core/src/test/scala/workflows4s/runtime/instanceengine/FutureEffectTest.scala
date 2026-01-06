package workflows4s.runtime.instanceengine

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

class FutureEffectTest extends AnyFreeSpec with EffectTestSuite[LazyFuture] with Matchers {

  given ExecutionContext = ExecutionContext.global

  import LazyFuture.lazyFutureEffect
  given effect: Effect[LazyFuture] = Effect[LazyFuture]

  override def assertFailsWith[A](fa: LazyFuture[A]): Throwable = {
    Try(effect.runSyncUnsafe(fa)) match {
      case Failure(e) => e
      case Success(_) => fail("Expected failure but got success")
    }
  }

  // Test utility extension methods for LazyFuture
  // Kept for documentation and potential future use
  extension [A](lf: LazyFuture[A]) {
    def value: A               = Await.result(lf.run, 5.seconds)
    def tryValue: Try[A]       = Try(Await.result(lf.run, 5.seconds))
    def failedValue: Throwable = tryValue match {
      case Failure(e) => e
      case Success(_) => fail("Expected failure but got success")
    }
  }

  "LazyFuture Effect" - {
    effectTests()
  }
}
