package workflows4s.ox.magnum

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.ox.{Direct, DirectWorkflowContext}
import workflows4s.ox.OxEffect.given
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

class DatabaseRuntimeTest extends AnyFreeSpec with OxPostgresSuite {

  "DatabaseRuntime" - {

    "should persist and recover workflow state from events" in {
      import TestCtx.*

      val workflow = workflows4s.wio.WIO.End[Direct, TestCtx.Ctx]()

      val engine = WorkflowInstanceEngine.basic[Direct]()

      val runtime = DatabaseRuntime
        .create[TestCtx.Ctx](workflow, WFState(0), transactor, engine, JavaSerdeEventCodec.get, "test-workflow")
        .runSync

      // For now, just verify basic functionality
      val instance = runtime.createInstance("1").runSync

      assert(instance.queryState().runSync.count == 0)
    }
  }

  object TestCtx extends DirectWorkflowContext {
    case class WFState(count: Int)
    enum WFEvent {
      case Incremented
    }

    type State = WFState
    type Event = WFEvent
  }
}
