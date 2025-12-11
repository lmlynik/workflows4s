package workflows4s.runtime.instanceengine

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.{ActiveWorkflow, WCEvent, WorkflowContext}

import java.time.Instant

/** Engine that schedules wakeups with a KnockerUpper.
  *
  * Note: This engine is IO-specific because WakeupResult internally uses IO, and we need to schedule wakeups from
  * within that IO context. For other effect types, a separate implementation would be needed.
  *
  * TODO: Move to workflows4s-cats-effect module
  */
class WakingWorkflowInstanceEngine(protected val delegate: WorkflowInstanceEngine[IO], knockerUpper: KnockerUpper.Agent[IO])
    extends DelegatingWorkflowInstanceEngine[IO]
    with StrictLogging {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WakeupResult[WCEvent[Ctx]]] =
    super.triggerWakeup(workflow).map {
      case WakeupResult.Noop               => WakeupResult.Noop
      case WakeupResult.Processed(eventIO) =>
        WakeupResult.Processed(eventIO.flatMap { result =>
          result match {
            case ProcessingResult.Proceeded(_)      => IO.pure(result)
            case ProcessingResult.Failed(retryTime) =>
              if workflow.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(workflow, Some(retryTime)).as(result)
              else IO.pure(result)
          }
        })
    }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <* IO.whenA(newState.wakeupAt != oldState.wakeupAt)(updateWakeup(newState, newState.wakeupAt))
  }

  private def updateWakeup(workflow: ActiveWorkflow[?], time: Option[Instant]): IO[Unit] = {
    IO(logger.debug(s"Registering wakeup for ${workflow.id} at $time")).void *>
      knockerUpper
        .updateWakeup(workflow.id, time)
        .handleError(err => logger.error("Failed to register wakeup", err))
  }

}
