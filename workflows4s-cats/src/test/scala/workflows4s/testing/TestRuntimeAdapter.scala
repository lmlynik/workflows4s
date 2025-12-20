package workflows4s.testing

import cats.Id
import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.cats.CatsEffect.ioEffect
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

/** Adapt various runtimes to a single interface for tests.
  *
  * This trait provides common infrastructure (clock, engine) for testing workflows. Subclasses implement runWorkflow and recover for their specific
  * runtime.
  */
trait TestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  protected val knockerUpper: RecordingKnockerUpper[IO] = RecordingKnockerUpper[IO]
  val clock: TestClock                                  = TestClock()
  val engine: WorkflowInstanceEngine[IO]                = WorkflowInstanceEngine
    .builder[IO]
    .withJavaTime(clock)
    .withWakeUps(knockerUpper)
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then actor.wakeup()
  }

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

}
