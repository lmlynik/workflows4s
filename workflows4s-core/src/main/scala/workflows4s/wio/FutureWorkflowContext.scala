package workflows4s.wio

import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import scala.concurrent.ExecutionContext

/** Workflow context for Future-based workflows.
  * Implemented using LazyFuture internally.
  */
trait FutureWorkflowContext extends WorkflowContext {
  type Eff[A] = LazyFuture[A]

  def executionContext: ExecutionContext

  given effect: Effect[Eff] = FutureEffect.futureEffect(using executionContext)
}
