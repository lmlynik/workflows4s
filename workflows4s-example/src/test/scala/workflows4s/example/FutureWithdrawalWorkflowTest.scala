package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.withdrawal.*
import workflows4s.runtime.instanceengine.{Effect, FutureEffect}
import workflows4s.testing.WorkflowTestAdapter

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class FutureWithdrawalWorkflowTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[Future] {

  override given effect: Effect[Future] = FutureEffect.futureEffect

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[Future, testContext.Context.Ctx]()
    withdrawalTests(adapter)
  }
}
