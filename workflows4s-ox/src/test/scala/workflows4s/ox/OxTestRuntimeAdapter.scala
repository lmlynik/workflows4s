package workflows4s.ox

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.{EffectTestRuntimeAdapter, RecordingKnockerUpper, TestClock}
import workflows4s.wio.*
import workflows4s.ox.OxEffect.given

/** Test runtime adapter for OX/Direct-style workflows. */
class OxTestRuntimeAdapter[Ctx <: WorkflowContext] extends EffectTestRuntimeAdapter[Direct, Ctx] with StrictLogging {

  override val knockerUpper: RecordingKnockerUpper[Direct] = RecordingKnockerUpper[Direct]
  override val clock: TestClock                            = TestClock()
  override val engine: WorkflowInstanceEngine[Direct]      = WorkflowInstanceEngine
    .builder[Direct]
    .withJavaTime(clock)
    .withWakeUps(knockerUpper)
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  override type Actor = OxActor

  override def runWorkflow(
      workflow: WIO.Initial[Direct, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime = InMemoryRuntime.create[Direct, Ctx](workflow, state, engine, "test")
    OxActor(List(), runtime)
  }

  override def recover(first: Actor): Actor = {
    OxActor(first.getEvents, first.runtime)
  }

  case class OxActor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[Direct, Ctx])
      extends DelegateWorkflowInstance[Direct, WCState[Ctx]]
      with EffectTestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {

    val delegate: InMemoryWorkflowInstance[Direct, Ctx] = {
      val inst = runtime.createInstance("").run
      inst.asInstanceOf[InMemoryWorkflowInstance[Direct, Ctx]].recover(events).run
      inst.asInstanceOf[InMemoryWorkflowInstance[Direct, Ctx]]
    }

    override def getEvents: Seq[WCEvent[Ctx]] = delegate.getEvents.run

    override def getExpectedSignals: Direct[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
  }
}

object OxTestRuntimeAdapter {
  def apply[Ctx <: WorkflowContext](): OxTestRuntimeAdapter[Ctx] = new OxTestRuntimeAdapter[Ctx]
}
