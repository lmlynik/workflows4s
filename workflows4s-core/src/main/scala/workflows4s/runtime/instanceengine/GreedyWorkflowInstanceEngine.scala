package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

/** Engine that triggers a wakeup after each state change for greedy evaluation. */
class GreedyWorkflowInstanceEngine[F[_]](protected val delegate: WorkflowInstanceEngine[F])(using E: Effect[F])
    extends DelegatingWorkflowInstanceEngine[F] {

  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): F[Set[PostExecCommand]] = {
    E.map(super.onStateChange(oldState, newState))(_ + PostExecCommand.WakeUp)
  }

}
