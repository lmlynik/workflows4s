package workflows4s.runtime

import org.scalatest.freespec.AnyFreeSpec
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.instanceengine.IOWorkflowInstanceEngineBuilder

class InMemoryRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemoryRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val engine                = IOWorkflowInstanceEngineBuilder.withJavaTimeIO().withoutWakeUps.withoutRegistering.withSingleStepEvaluation.withoutLogging.get
      val runtime               = InMemoryRuntime
        .default[Ctx](
          workflow = workflow,
          initialState = "initialState",
          engine = engine,
        )
        .unsafeRunSync()

      val instance1 = runtime.createInstance("id1").unsafeRunSync()
      val instance2 = runtime.createInstance("id1").unsafeRunSync()
      assert(instance1 == instance2)
    }

  }

}
