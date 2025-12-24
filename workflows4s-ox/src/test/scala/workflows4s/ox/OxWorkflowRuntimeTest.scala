package workflows4s.ox

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.TestState

/** Direct-style workflow runtime test suite for testing basic runtime behavior.
  *
  * Note: Direct style is synchronous, so the concurrency test from IO/ZIO doesn't apply in the same way. Instead, we test that the runtime correctly
  * handles 50 sequential workflow executions.
  */
class OxWorkflowRuntimeTest extends AnyFreeSpec with Matchers {

  "OxWorkflowRuntime" - {

    "runtime should execute 50 workflow iterations correctly" in {
      def singleRun(i: Int): Unit = {
        println(s"Running $i iteration")

        val (step1Id, step1) = OxTestUtils.runIO
        val (step2Id, step2) = OxTestUtils.pure

        val wio = step1 >>> step2

        val runtime = OxTestRuntimeAdapter[OxTestCtx2.Ctx]()
        val wf      = runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)

        // Execute the workflow
        wf.wakeup().run

        // Check final state
        val finalState = wf.queryState().run
        assert(finalState == TestState(List(step1Id, step2Id))): Unit
      }

      (1 to 50).foreach(singleRun)
    }

    "runtime should handle signals correctly" in {
      val (signal, signalStepId, signalStep) = OxTestUtils.signal

      val wio = signalStep

      val runtime = OxTestRuntimeAdapter[OxTestCtx2.Ctx]()
      val wf      = runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)

      // Initial state should be empty
      val initialState = wf.queryState().run
      assert(initialState == TestState.empty)

      // Deliver signal
      val response = wf.deliverSignal(signal, 42).run
      assert(response == Right(42))

      // Final state should have signal step executed
      val finalState = wf.queryState().run
      assert(finalState == TestState(List(signalStepId)))
    }

    "runtime should handle interruption correctly in sequential execution" in {
      // Create a workflow with a step that can be interrupted by a signal
      val (runIOStepId, runIOStep) = OxTestUtils.runIO
      val (signal, _, signalStep)  = OxTestUtils.signal

      val wio = runIOStep.interruptWith(signalStep.toInterruption)

      val runtime = OxTestRuntimeAdapter[OxTestCtx2.Ctx]()
      val wf      = runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)

      // Execute the runIO step first
      wf.wakeup().run

      // After wakeup, the step should have completed
      val stateAfterWakeup = wf.queryState().run
      assert(stateAfterWakeup == TestState(List(runIOStepId)))

      // Now signal should be rejected since step already completed
      val signalResult = wf.deliverSignal(signal, 1).run
      assert(signalResult.isLeft)
    }
  }
}
