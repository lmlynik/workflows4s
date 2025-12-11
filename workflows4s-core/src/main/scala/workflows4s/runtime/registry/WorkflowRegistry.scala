package workflows4s.runtime.registry

import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow

object WorkflowRegistry {

  enum ExecutionStatus {
    case Running, Awaiting, Finished
  }

  /** Agent for managing workflow registry, parameterized by effect type F[_]. */
  trait Agent[F[_]] {
    def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): F[Unit]
  }

  trait Tagger[State] {
    def getTags(id: WorkflowInstanceId, state: State): Map[String, String]
  }

}
