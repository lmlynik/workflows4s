package workflows4s.ox

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

/** A WorkflowContext that uses Direct (Ox direct-style wrapper) as its effect type.
  *
  * Extend this trait to create workflow contexts that use Ox-style direct execution:
  *
  * {{{
  * object MyWorkflowContext extends DirectWorkflowContext {
  *   case class MyState(...)
  *   sealed trait MyEvent
  *   type State = MyState
  *   type Event = MyEvent
  * }
  * }}}
  *
  * Note: Workflows using DirectWorkflowContext execute synchronously in direct style. For background operations that need true concurrency, consider
  * using cats-effect IO or ZIO instead.
  */
trait DirectWorkflowContext extends WorkflowContext {
  type Eff[A] = Direct[A]
  given effect: Effect[Eff] = OxEffect.directEffect
}
