package workflows4s.example.withdrawal.checks

import workflows4s.wio.FutureWorkflowContext
import workflows4s.runtime.instanceengine.{LazyFuture, FutureEffect}

/** Future-specific ChecksEngine for use with Pekko and other Future-based runtimes.
  * Internally uses LazyFuture for implementation.
  */
object FutureChecksEngineHelper {

  object Context extends FutureWorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
    override val executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  def create(): ChecksEngine[LazyFuture, Context.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[LazyFuture] = FutureEffect.futureEffect(using Context.executionContext)
    new ChecksEngine[LazyFuture, Context.Ctx](Context)
  }
}
