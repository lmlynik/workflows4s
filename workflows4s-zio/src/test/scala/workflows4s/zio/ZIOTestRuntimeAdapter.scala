package workflows4s.zio

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.{EffectTestRuntimeAdapter, RecordingKnockerUpper, TestClock}
import workflows4s.wio.*
import workflows4s.zio.ZIOEffect.given
import zio.*

/** Test runtime adapter for ZIO-based workflows. */
class ZIOTestRuntimeAdapter[Ctx <: WorkflowContext] extends EffectTestRuntimeAdapter[Task, Ctx] with StrictLogging {

  override val knockerUpper: RecordingKnockerUpper[Task] = RecordingKnockerUpper[Task]
  override val clock: TestClock                          = TestClock()
  override val engine: WorkflowInstanceEngine[Task]      = WorkflowInstanceEngine
    .builder[Task]
    .withJavaTime(clock)
    .withWakeUps(knockerUpper)
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  override type Actor = ZIOActor

  override def runWorkflow(
      workflow: WIO.Initial[Task, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(InMemoryRuntime.create[Task, Ctx](workflow, state, engine, "test")).getOrThrow()
    }
    ZIOActor(List(), runtime)
  }

  override def recover(first: Actor): Actor = {
    ZIOActor(first.getEvents, first.runtime)
  }

  case class ZIOActor(events: Seq[WCEvent[Ctx]], runtime: InMemoryRuntime[Task, Ctx])
      extends DelegateWorkflowInstance[Task, WCState[Ctx]]
      with EffectTestRuntimeAdapter.EventIntrospection[WCEvent[Ctx]] {

    val delegate: InMemoryWorkflowInstance[Task, Ctx] = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run {
          for {
            inst <- runtime.createInMemoryInstance("")
            _    <- inst.recover(events)
          } yield inst
        }
        .getOrThrow()
    }

    override def getEvents: Seq[WCEvent[Ctx]] = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(delegate.getEvents).getOrThrow()
    }

    override def getExpectedSignals: Task[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
  }
}

object ZIOTestRuntimeAdapter {
  def apply[Ctx <: WorkflowContext](): ZIOTestRuntimeAdapter[Ctx] = new ZIOTestRuntimeAdapter[Ctx]
}
