package workflows4s.runtime.pekko

import workflows4s.runtime.instanceengine.{Effect, FutureEffect}
import workflows4s.wio.{TestState, WorkflowContext}

import scala.concurrent.{ExecutionContext, Future}

/** Future-based test context for Pekko runtime tests.
  *
  * Note: This context uses a global ExecutionContext for simplicity in tests. In production code, you would typically inject the ExecutionContext.
  */
object FutureTestCtx extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = TestState

  type Eff[A] = Future[A]

  // Use global ExecutionContext for tests
  private given ExecutionContext = ExecutionContext.global

  given effect: Effect[Eff] = FutureEffect.futureEffect
}
