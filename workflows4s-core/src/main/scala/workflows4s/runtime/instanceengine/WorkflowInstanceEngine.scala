package workflows4s.runtime.instanceengine

import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import scala.annotation.unused

/** Abstract workflow instance engine parameterized by effect type F[_].
  *
  * This trait defines the core operations for executing workflows in an effect-polymorphic way.
  * Implementations exist for cats-effect IO, ZIO, and other effect systems.
  */
trait WorkflowInstanceEngine[F[_]] {

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[List[SignalDef[?, ?]]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[WCEvent[Ctx]]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp]]

  /** Handles an event, returning the new workflow state if the event was accepted.
    * This is a pure operation - the event is applied synchronously.
    */
  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Option[ActiveWorkflow[Ctx]]

  def onStateChange[Ctx <: WorkflowContext](@unused oldState: ActiveWorkflow[Ctx], @unused newState: ActiveWorkflow[Ctx]): F[Set[PostExecCommand]]

  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): ActiveWorkflow[Ctx] =
    handleEvent(workflow, event).getOrElse(workflow)

}

object WorkflowInstanceEngine {

  enum PostExecCommand {
    case WakeUp
  }
}
