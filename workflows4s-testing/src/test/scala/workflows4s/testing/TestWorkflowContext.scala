package workflows4s.testing

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.{StepId, TestState, WorkflowContext}

import java.time.Instant

/** Effect-polymorphic test workflow context with pre-defined event types.
  *
  * This context provides a standardized set of events for testing workflows across different effect types (IO, Task, Direct, etc.).
  *
  * @tparam F
  *   the effect type
  */
abstract class TestWorkflowContext[F[_]](using val effect: Effect[F]) extends WorkflowContext {
  type State  = TestState
  type Eff[A] = F[A]

  sealed trait Event extends Serializable

  case class SimpleEvent(value: String) extends Event

  /** Event indicating a step completed with IO */
  case class RunIODone(stepId: StepId) extends Event

  /** Event indicating an IO operation resulted in an error */
  case class RunIOErrored(error: String) extends Event

  /** Event for signal handling with request data */
  case class SignalReceived(req: Int) extends Event

  /** Event indicating a signal resulted in an error */
  case class SignalErrored(req: Int, error: String) extends Event

  /** Event for timer start */
  case class TimerStarted(instant: Instant) extends Event

  /** Event for timer release */
  case class TimerReleased(instant: Instant) extends Event
}
