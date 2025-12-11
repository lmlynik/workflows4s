package workflows4s.runtime

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.instanceengine.IOWorkflowInstanceEngineBuilder

class InMemorySyncRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemorySyncRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val engine                = IOWorkflowInstanceEngineBuilder.withJavaTimeIO().withoutWakeUps.withoutRegistering.withSingleStepEvaluation.withoutLogging.get
      val runtime               = InMemorySyncRuntime.create[Ctx](
        workflow = workflow,
        initialState = "initialState",
        engine = engine,
      )

      val instance1 = runtime.createInstance("id1")
      val instance2 = runtime.createInstance("id1")
      assert(instance1 == instance2)
    }

  }

}
