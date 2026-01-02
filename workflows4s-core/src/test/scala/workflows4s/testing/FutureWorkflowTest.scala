package workflows4s.testing

import scala.concurrent.{Await, ExecutionContext, Future}
import workflows4s.runtime.instanceengine.{Effect, FutureEffect}

// Ensure an ExecutionContext is available (global is standard for tests)
import scala.concurrent.ExecutionContext.Implicits.global

class FutureWorkflowTest extends WorkflowRuntimeTest[Future] {

  override given effect: Effect[Future] = FutureEffect.futureEffect

  override def unsafeRun(program: => Future[Unit]): Unit =
    Await.result(program, testTimeout)

  given Runner[Future] = new Runner[Future] {
    def run[A](fa: Future[A]): A = Await.result(fa, testTimeout)
  }

  def getAdapter: Adapter = new WorkflowTestAdapter.InMemory[Future, ctx.type]()

  workflowTests(getAdapter)
}
