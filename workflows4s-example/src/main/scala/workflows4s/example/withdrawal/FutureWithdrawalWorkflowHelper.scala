package workflows4s.example.withdrawal

import workflows4s.wio.FutureWorkflowContext
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.instanceengine.{FutureEffect, LazyFuture}

/** Future-specific WithdrawalWorkflow helper for use with Pekko and other Future-based runtimes. Internally uses LazyFuture for implementation.
  */
object FutureWithdrawalWorkflowHelper {

  object Context extends FutureWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
    override val executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  object ChecksEngineContext extends FutureWorkflowContext {
    override type Event = workflows4s.example.withdrawal.checks.ChecksEvent
    override type State = workflows4s.example.withdrawal.checks.ChecksState
    override val executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  def create(
      service: WithdrawalService[LazyFuture],
      checksEngine: ChecksEngine[LazyFuture, ChecksEngineContext.Ctx],
  ): WithdrawalWorkflow[LazyFuture, Context.Ctx, ChecksEngineContext.Ctx] = {
    given workflows4s.runtime.instanceengine.Effect[LazyFuture] = FutureEffect.futureEffect(using Context.executionContext)
    new WithdrawalWorkflow[LazyFuture, Context.Ctx, ChecksEngineContext.Ctx](Context, service, checksEngine)
  }
}
