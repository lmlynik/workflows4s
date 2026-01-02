package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.{DelegateWorkflowInstance, WorkflowInstance}
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.wio.*
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.InMemoryWorkflowInstance

import scala.concurrent.duration.*

trait EventIntrospection[Event] {
  def getEvents: Seq[Event]
}

trait WorkflowTestAdapter[F[_], Ctx <: WorkflowContext] extends StrictLogging {

  implicit def effect: Effect[F]

  implicit def runner: Runner[F]

  def testTimeout: FiniteDuration = 10.seconds
  val clock: TestClock            = TestClock()

  protected lazy val knockerUpper: TestKnockerUpper[F] = runner.run(TestKnockerUpper.create[F])
  lazy val registry: InMemoryWorkflowRegistry[F]       = runner.run(InMemoryWorkflowRegistry[F](clock))

  lazy val engine: WorkflowInstanceEngine[F] = WorkflowInstanceEngine.default(knockerUpper, registry, clock)

  type Actor <: WorkflowInstance[F, WCState[Ctx]] & EventIntrospection[WCEvent[Ctx]]

  def runWorkflow(workflow: WIO.Initial[F, Ctx], state: WCState[Ctx]): Actor
  def recover(first: Actor): Actor

  final def executeDueWakeup(actor: Actor): Unit = {
    // check generic logic
    val wakeupToCheck = runner.run(knockerUpper.lastRegisteredWakeup(actor.id))

    logger.debug(s"Executing due wakeup for actor ${actor.id}. Last registered wakeup: ${wakeupToCheck}")

    if wakeupToCheck.exists(_.isBefore(clock.instant())) then {
      runner.run(actor.wakeup())
    }
  }
}

object WorkflowTestAdapter {

  class InMemory[F[_], Ctx <: WorkflowContext](using
      override val effect: Effect[F],
      override val runner: Runner[F],
  ) extends WorkflowTestAdapter[F, Ctx] {

    case class InMemoryActor(
        events: Seq[WCEvent[Ctx]],
        instance: InMemoryWorkflowInstance[F, Ctx],
        runtime: InMemoryRuntime[F, Ctx],
    ) extends DelegateWorkflowInstance[F, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {

      val delegate: WorkflowInstance[F, WCState[Ctx]]           = instance
      override def getEvents: Seq[WCEvent[Ctx]]                 = runner.run(instance.getEvents)
      override def getExpectedSignals: F[List[SignalDef[?, ?]]] = instance.getExpectedSignals
    }

    override type Actor = InMemoryActor

    override def runWorkflow(workflow: WIO.Initial[F, Ctx], state: WCState[Ctx]): Actor = {
      val action = for {
        runtime <- InMemoryRuntime.create[F, Ctx](workflow, state, engine)
        // Ensure the ID used here matches what executeDueWakeup expects
        inst    <- runtime.createInMemoryInstance("test-instance-1")
      } yield InMemoryActor(List(), inst, runtime)

      runner.run(action)
    }

    override def recover(first: Actor): Actor = {
      val action = for {
        recovered <- first.runtime.createInMemoryInstance("test-instance-1")
        events     = first.getEvents
        _         <- recovered.recover(events)
      } yield InMemoryActor(events, recovered, first.runtime)

      runner.run(action)
    }
  }
}
