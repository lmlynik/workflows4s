package workflows4s.example.withdrawal

import workflows4s.example.withdrawal.WithdrawalEvent.{MoneyLocked, WithdrawalAccepted, WithdrawalRejected}
import workflows4s.example.withdrawal.WithdrawalService.ExecutionResponse
import workflows4s.example.withdrawal.WithdrawalSignal.{CancelWithdrawal, CreateWithdrawal, ExecutionCompleted}
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio
import workflows4s.wio.internal.WorkflowEmbedding
import workflows4s.wio.{SignalDef, WorkflowContext}

import java.time.Duration

class WithdrawalWorkflow[
  F[_],
  Ctx <: WorkflowContext { type Eff[A] = F[A]; type Event = WithdrawalEvent; type State = WithdrawalData },
  ChecksCtx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState }
](
  val ctx: Ctx,
  service: WithdrawalService[F],
  checksEngine: ChecksEngine[F, ChecksCtx]
)(using effect: Effect[F]) {

  import ctx.WIO

  def workflow: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    (for {
      _ <- validate
      _ <- calculateFees
      _ <- putFundsOnHold
      _ <- runChecks
      _ <- execute
      s <- releaseFunds
    } yield s)
      .handleErrorWith(cancelFundsIfNeeded)

  def workflowDeclarative: WIO.Initial =
    (
      (
        validate >>>
          calculateFees >>>
          putFundsOnHold >>>
          runChecks >>>
          execute
      ).interruptWith(handleCancellation)
        >>> releaseFunds
    ).handleErrorWith(cancelFundsIfNeeded) >>> WIO.noop()

  private def validate: WIO[Any, WithdrawalRejection.InvalidInput, WithdrawalData.Initiated] =
    WIO
      .handleSignal(WithdrawalWorkflow.createWithdrawalSignal)
      .using[Any]
      .purely { (_, signal) =>
        if signal.amount > 0 then WithdrawalAccepted(signal.txId, signal.amount, signal.recipient)
        else WithdrawalRejected("Amount must be positive")
      }
      .handleEventWithError { (_, event) =>
        event match {
          case WithdrawalAccepted(txId, amount, recipient) => Right(WithdrawalData.Initiated(txId, amount, recipient))
          case WithdrawalRejected(error)                   => Left(WithdrawalRejection.InvalidInput(error))
        }
      }
      .voidResponse
      .autoNamed

  private def calculateFees: WIO[WithdrawalData.Initiated, Nothing, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => effect.map(service.calculateFees(state.amount))(WithdrawalEvent.FeeSet.apply))
    .handleEvent { (state, event) => state.validated(event.fee) }
    .autoNamed()

  private def putFundsOnHold: WIO[WithdrawalData.Validated, WithdrawalRejection.NotEnoughFunds, WithdrawalData.Validated] =
    WIO
      .runIO[WithdrawalData.Validated](state =>
        effect.map(service.putMoneyOnHold(state.amount))({
          case Left(WithdrawalService.NotEnoughFunds()) => WithdrawalEvent.MoneyLocked.NotEnoughFunds()
          case Right(_)                                 => WithdrawalEvent.MoneyLocked.Success()
        }),
      )
      .handleEventWithError { (state, evt) =>
        evt match {
          case MoneyLocked.Success()        => Right(state)
          case MoneyLocked.NotEnoughFunds() => Left(WithdrawalRejection.NotEnoughFunds())
        }
      }
      .autoNamed()

  private def runChecks: WIO[WithdrawalData.Validated, WithdrawalRejection.RejectedInChecks, WithdrawalData.Checked] = {
    val embedding = WithdrawalWorkflow.checksEmbedding[F, ChecksCtx, Ctx]
    val doRunChecks: WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked] = {
      val innerWIO = checksEngine.runChecks
        .transformInput((x: WithdrawalData.Validated) => ChecksInput(x, service.getChecks()))
      ctx.WIO.embed(innerWIO.asInstanceOf)(embedding.asInstanceOf).asInstanceOf[WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked]]
    }

    val actOnDecision = WIO.pure
      .makeFrom[WithdrawalData.Checked]
      .errorOpt(_.checkResults.decision match {
        case Decision.RejectedBySystem()   => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedBySystem()   => None
        case Decision.RejectedByOperator() => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedByOperator() => None
      })
      .autoNamed

    doRunChecks >>> actOnDecision
  }

  private def execute: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    initiateExecution >>> awaitExecutionCompletion

  // This could use retries once we have them
  private def initiateExecution: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .runIO[WithdrawalData.Checked](s =>
        effect.map(service.initiateExecution(s.netAmount, s.recipient))(WithdrawalEvent.ExecutionInitiated.apply),
      )
      .handleEventWithError((s, event) =>
        event.response match {
          case ExecutionResponse.Accepted(externalId) => Right(s.executed(externalId))
          case ExecutionResponse.Rejected(error)      => Left(WithdrawalRejection.RejectedByExecutionEngine(error))
        },
      )
      .autoNamed()
      .retryIn(_ => WithdrawalWorkflow.executionRetryDelay)

  private def awaitExecutionCompletion: WIO[WithdrawalData.Executed, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .handleSignal(WithdrawalWorkflow.executionCompletedSignal)
      .using[WithdrawalData.Executed]
      .purely((_, sig) => WithdrawalEvent.ExecutionCompleted(sig))
      .handleEventWithError((s, e: WithdrawalEvent.ExecutionCompleted) =>
        e.status match {
          case ExecutionCompleted.Succeeded => Right(s)
          case ExecutionCompleted.Failed    => Left(WithdrawalRejection.RejectedByExecutionEngine("Execution failed"))
        },
      )
      .voidResponse
      .done

  private def releaseFunds: WIO[WithdrawalData.Executed, Nothing, WithdrawalData.Completed] =
    WIO
      .runIO[WithdrawalData.Executed](st => effect.map(service.releaseFunds(st.amount))(_ => WithdrawalEvent.MoneyReleased()))
      .handleEvent((st, _) => st.completed())
      .autoNamed()

  private def cancelFundsIfNeeded: WIO[(WithdrawalData, WithdrawalRejection), Nothing, WithdrawalData.Completed.Failed] = {
    WIO
      .runIO[(WithdrawalData, WithdrawalRejection)]({ case (_, r) =>
        r match {
          case WithdrawalRejection.InvalidInput(error)              => effect.pure(WithdrawalEvent.RejectionHandled(error))
          case WithdrawalRejection.NotEnoughFunds()                 => effect.pure(WithdrawalEvent.RejectionHandled("Not enough funds on the user's account"))
          case WithdrawalRejection.RejectedInChecks()               =>
            effect.map(service.cancelFundsLock())(_ => WithdrawalEvent.RejectionHandled("Transaction rejected in checks"))
          case WithdrawalRejection.RejectedByExecutionEngine(error) => effect.map(service.cancelFundsLock())(_ => WithdrawalEvent.RejectionHandled(error))
          case WithdrawalRejection.Cancelled(operatorId, comment)   =>
            effect.map(service.cancelFundsLock())(_ => WithdrawalEvent.RejectionHandled(s"Cancelled by ${operatorId}. Comment: ${comment}"))
        }
      })
      .handleEvent((_: (WithdrawalData, WithdrawalRejection), evt) => WithdrawalData.Completed.Failed(evt.error))
      .autoNamed()
  }

  private def handleCancellation = {
    WIO.interruption
      .throughSignal(WithdrawalWorkflow.cancelSignal)
      .handleAsync((state, signal) => {
        def ok = effect.pure(WithdrawalEvent.WithdrawalCancelledByOperator(signal.operatorId, signal.comment))
        state match {
          case _: WithdrawalData.Empty     => ok
          case _: WithdrawalData.Initiated => ok
          case _: WithdrawalData.Validated => ok
          case _: WithdrawalData.Checking  => ok
          case _: WithdrawalData.Checked   => ok
          case _: WithdrawalData.Executed  =>
            if signal.acceptStartedExecution then ok
            else
              effect.raiseError(
                new Exception("To cancel transaction that has been already executed, this fact has to be explicitly accepted in the request."),
              )
          case _: WithdrawalData.Completed => effect.raiseError(new Exception(s"Unexpected state for cancellation: $state"))
        }
      })
      .handleEventWithError((_, evt) => Left(WithdrawalRejection.Cancelled(evt.operatorId, evt.comment)))
      .voidResponse
      .done
  }

}

object WithdrawalWorkflow {

  val executionRetryDelay = Duration.ofMinutes(2)

  val createWithdrawalSignal   = SignalDef[CreateWithdrawal, Unit]()
  val executionCompletedSignal = SignalDef[ExecutionCompleted, Unit]()
  val cancelSignal             = SignalDef[CancelWithdrawal, Unit]()

  def checksEmbedding[
    F[_],
    ChecksCtx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState },
    WithdrawalCtx <: WorkflowContext { type Eff[A] = F[A]; type Event = WithdrawalEvent; type State = WithdrawalData }
  ]: WorkflowEmbedding[ChecksCtx, WithdrawalCtx, WithdrawalData.Validated] =
    new WorkflowEmbedding[ChecksCtx, WithdrawalCtx, WithdrawalData.Validated] {
      override def convertEvent(e: WorkflowContext.Event[ChecksCtx]): WorkflowContext.Event[WithdrawalCtx] =
        WithdrawalEvent.ChecksRun(e.asInstanceOf[ChecksEvent]).asInstanceOf[WorkflowContext.Event[WithdrawalCtx]]

      override def unconvertEvent(e: WorkflowContext.Event[WithdrawalCtx]): Option[WorkflowContext.Event[ChecksCtx]] = e match {
        case WithdrawalEvent.ChecksRun(inner) => Some(inner.asInstanceOf[WorkflowContext.Event[ChecksCtx]])
        case _                                => None
      }

      override type OutputState[T <: WorkflowContext.State[ChecksCtx]] <: WorkflowContext.State[WithdrawalCtx] = T match {
        case ChecksState.InProgress => WithdrawalData.Checking
        case ChecksState.Decided    => WithdrawalData.Checked
      }

      override def convertState[T <: WorkflowContext.State[ChecksCtx]](s: T, input: WithdrawalData.Validated): OutputState[T] = (s match {
        case x: ChecksState.InProgress => input.checking(x)
        case x: ChecksState.Decided    => input.checked(x)
      }).asInstanceOf[OutputState[T]]

      override def unconvertState(outerState: WorkflowContext.State[WithdrawalCtx]): Option[WorkflowContext.State[ChecksCtx]] = outerState.asInstanceOf[WithdrawalData] match {
        case _: WithdrawalData.Validated => Some(ChecksState.Empty.asInstanceOf[WorkflowContext.State[ChecksCtx]])
        case x: WithdrawalData.Checking  => Some(x.checkResults.asInstanceOf[WorkflowContext.State[ChecksCtx]])
        case x: WithdrawalData.Checked   => Some(x.checkResults.asInstanceOf[WorkflowContext.State[ChecksCtx]])
        case _                           => None
      }
    }

}
