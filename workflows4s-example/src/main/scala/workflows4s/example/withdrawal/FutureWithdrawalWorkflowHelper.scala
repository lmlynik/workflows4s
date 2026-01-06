package workflows4s.example.withdrawal

import scala.concurrent.Future
import workflows4s.wio.FutureWorkflowContext
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.instanceengine.FutureEffect

/** Future-specific WithdrawalWorkflow helper for use with Pekko and other Future-based runtimes.
  */
object FutureWithdrawalWorkflowHelper {

  object Context extends FutureWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  object ChecksEngineContext extends FutureWorkflowContext {
    override type Event = workflows4s.example.withdrawal.checks.ChecksEvent
    override type State = workflows4s.example.withdrawal.checks.ChecksState
  }

  def create(service: WithdrawalService[Future], checksEngine: ChecksEngine[Future, ChecksEngineContext.Ctx]): WithdrawalWorkflow[Future, Context.Ctx, ChecksEngineContext.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[Future] = FutureEffect.futureEffect(using Context.executionContext)
    new WithdrawalWorkflow[Future, Context.Ctx, ChecksEngineContext.Ctx](Context, service, checksEngine)
  }

  val checksEmbedding = WithdrawalWorkflow.checksEmbedding[Future, ChecksEngineContext.Ctx, Context.Ctx]
}
