package workflows4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.checks.DummyChecksEngine
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.{Runner, WorkflowTestAdapter}

class WithdrawalWorkflowTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override given runner: Runner[IO] = new Runner[IO] {
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  override val testContext: WithdrawalWorkflowTestContext[IO] = new WithdrawalWorkflowTestContext[IO]

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[IO, testContext.Context.Ctx]()
    withdrawalTests(adapter)
  }

  // IO-specific render tests
  "render model" in {
    val checksEngine = DummyChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderModelToFile(wf.workflowDeclarative, "withdrawal-example-declarative-model.json")
  }

  "render bpmn model" in {
    val checksEngine = DummyChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderBpmnToFile(wf.workflow, "withdrawal-example-bpmn.bpmn")
    TestUtils.renderBpmnToFile(wf.workflowDeclarative, "withdrawal-example-bpmn-declarative.bpmn")
  }

  "render mermaid model" in {
    val checksEngine = DummyChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderMermaidToFile(wf.workflow.toProgress, "withdrawal-example.mermaid")
    TestUtils.renderMermaidToFile(wf.workflowDeclarative.toProgress, "withdrawal-example-declarative.mermaid")
  }
}
