package workflows4s.runtime.instanceengine

import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

/** Engine that logs all operations for debugging and observability.
  *
  * Note: This engine is IO-specific because WakeupResult and SignalResult internally use IO, and we need to wrap
  * logging around those internal IO operations.
  *
  * TODO: Move to workflows4s-cats-effect module
  */
class LoggingWorkflowInstanceEngine(
    override protected val delegate: WorkflowInstanceEngine[IO],
) extends DelegatingWorkflowInstanceEngine[IO]
    with StrictLogging {

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]] = {
    (IO(logger.trace(s"[${workflow.id}] queryState()")) *>
      delegate
        .queryState(workflow)
        .flatTap(state => IO(logger.trace(s"[${workflow.id}] queryState → $state")))
        .onError(e => IO(logger.error(s"[${workflow.id}] queryState failed", e)))).withMDC(workflow)
  }

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = {
    (IO(logger.trace(s"[${workflow.id}] getProgress()")) *>
      delegate
        .getProgress(workflow)
        .flatTap(prog => IO(logger.trace(s"[${workflow.id}] getProgress → $prog")))
        .onError(e => IO(logger.error(s"[${workflow.id}] getProgress failed", e))))
      .withMDC(workflow)
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]] = {
    (IO(logger.trace(s"[${workflow.id}] getExpectedSignals()")) *>
      delegate
        .getExpectedSignals(workflow)
        .flatTap(signals => IO(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))
        .onError(e => IO(logger.error(s"[${workflow.id}] getExpectedSignals failed", e))))
      .withMDC(workflow)
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
  ): IO[WakeupResult[WCEvent[Ctx]]] = {
    (IO(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .flatMap({
          case WakeupResult.Noop              =>
            IO(logger.trace("⤷ Nothing to execute")).as(WakeupResult.Noop: WakeupResult[WCEvent[Ctx]])
          case WakeupResult.Processed(result) =>
            WakeupResult
              .Processed(
                IO(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
                  result
                    .flatTap({
                      case ProcessingResult.Proceeded(event) =>
                        IO(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event"))
                      case ProcessingResult.Failed(retry)    =>
                        IO(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $retry"))
                    })
                    .onError(e => IO(logger.error("wakeupEffect failed", e))),
              )
              .pure[IO]
        })
        .onError(e => IO(logger.error(s"[${workflow.id}] triggerWakeup failed", e))))
      .withMDC(workflow)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[SignalResult[WCEvent[Ctx], Resp]] = {
    (IO(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .flatMap({
          case SignalResult.Processed(resultIO) =>
            IO(logger.debug(s"[${workflow.id}] handleSignal → effect returned")).as(
              SignalResult.Processed(
                IO(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
                  resultIO
                    .flatMap(result =>
                      IO(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}"))
                        .as(result),
                    )
                    .onError(e => IO(logger.error("handleSignalEffect failed", e))),
              ),
            )
          case SignalResult.UnexpectedSignal    =>
            IO(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")) *>
              SignalResult.UnexpectedSignal.pure[IO]
        })
        .onError(e => IO(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))))
      .withMDC(workflow)
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      event: WCEvent[Ctx],
  ): Option[ActiveWorkflow[Ctx]] = {
    logger.debug(s"[${workflow.id}] handleEvent(event = $event)")
    val result = delegate.handleEvent(workflow, event)
    result match {
      case Some(_) => logger.debug(s"[${workflow.id}] handleEvent → new state")
      case None    => logger.warn(s"[${workflow.id}] handleEvent → no state change")
    }
    result
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[PostExecCommand]] = {
    (IO(logger.debug(s"""[${oldState.id}] onStateChange:
                        |old state: ${oldState.liveState}
                        |new state: ${newState.liveState}""".stripMargin)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatTap(cmds => IO(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))
        .onError(e => IO(logger.error(s"[${oldState.id}] onStateChange failed", e)))).withMDC(newState)
  }

  extension [A](ioa: IO[A]) {
    def withMDC(activeWorkflow: ActiveWorkflow[? <: WorkflowContext]): IO[A] = {
      IO {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      } *> ioa.guarantee(IO(MDC.clear()))
    }
  }

}
