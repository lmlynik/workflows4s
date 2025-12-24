package workflows4s.zio

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext
import zio.Task

/** A WorkflowContext that uses ZIO Task as its effect type.
  *
  * Extend this trait to create workflow contexts that use ZIO for effect handling:
  *
  * {{{
  * object MyWorkflowContext extends ZIOWorkflowContext {
  *   case class MyState(...)
  *   sealed trait MyEvent
  *   type State = MyState
  *   type Event = MyEvent
  * }
  * }}}
  */
trait ZIOWorkflowContext extends WorkflowContext {
  type Eff[A] = Task[A]
  given effect: Effect[Eff] = ZIOEffect.taskEffect
}
