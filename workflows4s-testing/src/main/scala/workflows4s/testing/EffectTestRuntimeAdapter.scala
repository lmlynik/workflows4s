package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.wio.{WCState, WIO, WorkflowContext}

/** Effect-polymorphic test runtime adapter that provides a unified interface for testing workflows with different effect types.
  *
  * @tparam F
  *   the effect type (IO, Task, Direct, etc.)
  * @tparam Ctx
  *   the workflow context
  */
trait EffectTestRuntimeAdapter[F[_], Ctx <: WorkflowContext](using E: Effect[F]) extends StrictLogging {

  /** The knocker upper for recording wakeups */
  val knockerUpper: RecordingKnockerUpper[F]

  /** The test clock for time manipulation */
  val clock: TestClock

  /** The workflow instance engine */
  val engine: WorkflowInstanceEngine[F]

  /** The actor type representing a running workflow instance */
  type Actor <: WorkflowInstance[F, WCState[Ctx]]

  /** Create a new workflow instance */
  def runWorkflow(
      workflow: WIO.Initial[F, Ctx],
      state: WCState[Ctx],
  ): Actor

  /** Recover a workflow instance from its events */
  def recover(first: Actor): Actor

  /** Execute any due wakeups for the given actor */
  def executeDueWakeup(actor: Actor): F[Unit] = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: $wakeup")
    E.whenA(wakeup.exists(_.isBefore(clock.instant()))) {
      actor.wakeup()
    }
  }
}

object EffectTestRuntimeAdapter {

  /** Trait for introspecting events from a workflow instance. Mix this into Actor types to enable event assertions in tests.
    */
  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }
}
