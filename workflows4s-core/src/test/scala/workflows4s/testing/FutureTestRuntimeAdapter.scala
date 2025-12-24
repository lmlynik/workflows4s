package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, WorkflowInstanceEngine}
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Test adapter for Future-based runtimes.
  */
trait FutureTestRuntimeAdapter[Ctx <: WorkflowContext] extends StrictLogging {

  implicit def ec: ExecutionContext

  given Effect[Future] = FutureEffect.futureEffect

  protected val knockerUpper: FutureRecordingKnockerUpper = FutureRecordingKnockerUpper()
  val clock: TestClock                                    = TestClock()

  // Made lazy to avoid initialization order issues with ExecutionContext
  lazy val registry: InMemoryWorkflowRegistry[Future] = Await.result(InMemoryWorkflowRegistry[Future](clock), 5.seconds)

  lazy val engine: WorkflowInstanceEngine[Future] = WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  /** Timeout for concurrency tests. Override for slower runtimes like Pekko. */
  def testTimeout: FiniteDuration = 10.seconds

  type Actor <: WorkflowInstance[Future, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO.Initial[Future, Ctx],
      state: WCState[Ctx],
  ): Actor

  def recover(first: Actor): Actor

  final def executeDueWakeup(actor: Actor): Unit = {
    val wakeup = knockerUpper.lastRegisteredWakeup(actor.id)
    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeup}")
    if wakeup.exists(_.isBefore(clock.instant()))
    then Await.result(actor.wakeup(), 30.seconds)
  }
}
