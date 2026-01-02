package workflows4s.testing

import scala.concurrent.{Await, ExecutionContext, Future}
import workflows4s.runtime.instanceengine.{Effect, FutureEffect}

// Ensure an ExecutionContext is available (global is standard for tests)
import scala.concurrent.ExecutionContext.Implicits.global

class FutureWorkflowTest extends WorkflowRuntimeTest[Future] {

  override given effect: Effect[Future] = FutureEffect.futureEffect

  // 2. Define how to run Future synchronously (used by the test suite logic)
  override def unsafeRun(program: => Future[Unit]): Unit =
    Await.result(program, testTimeout)

  // 3. Define the Runner strategy (used by the Adapter for internal initialization)
  implicit val runner: Runner[Future] = new Runner[Future] {
    def run[A](fa: Future[A]): A = Await.result(fa, testTimeout)
  }

  // 4. Create the Adapter
  // We use the generic InMemory adapter, which binds to the 'ctx' defined in the parent trait
  def getAdapter: Adapter = new WorkflowTestAdapter.InMemory[Future, ctx.type]()

  // 5. Register the tests
  // This executes the test definitions inside the parent trait
  workflowTests(getAdapter)
}
