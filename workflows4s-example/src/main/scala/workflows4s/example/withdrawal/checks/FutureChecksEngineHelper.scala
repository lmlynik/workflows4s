package workflows4s.example.withdrawal.checks

import scala.concurrent.Future
import workflows4s.wio.FutureWorkflowContext
import workflows4s.runtime.instanceengine.FutureEffect

/** Future-specific ChecksEngine for use with Pekko and other Future-based runtimes.
  */
object FutureChecksEngineHelper {

  object Context extends FutureWorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
  }

  def create(): ChecksEngine[Future, Context.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[Future] = FutureEffect.futureEffect(using Context.executionContext)
    new ChecksEngine[Future, Context.Ctx](Context)
  }
}
