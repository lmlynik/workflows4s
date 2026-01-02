package workflows4s.wio

import scala.concurrent.{ExecutionContext, Future}
import workflows4s.runtime.instanceengine.{Effect, FutureEffect}

/** Helper trait for Future-based workflow contexts. Extend this for workflows that use scala.concurrent.Future.
  *
  * Override `executionContext` to provide a custom ExecutionContext, or use the default global one.
  */
trait FutureWorkflowContext extends WorkflowContext {
  type Eff[A] = Future[A]

  /** Override this to provide a custom ExecutionContext. Defaults to the global ExecutionContext.
    */
  given executionContext: ExecutionContext = ExecutionContext.global

  given effect: Effect[Eff] = FutureEffect.futureEffect
}
