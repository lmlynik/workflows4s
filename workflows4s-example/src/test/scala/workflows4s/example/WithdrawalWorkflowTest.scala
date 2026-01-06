package workflows4s.example

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.WorkflowTestAdapter

class WithdrawalWorkflowTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override def persistProgress(progress: workflows4s.wio.model.WIOExecutionProgress[?], name: String): Unit =
    TestUtils.renderMermaidToFile(progress, s"withdrawal/progress-$name.mermaid", technical = false)

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[IO, testContext.Context.Ctx]()
    withdrawalTests(adapter)
  }

  // IO-specific render tests
  "render model" in {
    val checksEngine = new ChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf           = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderModelToFile(wf.workflowDeclarative, "withdrawal-example-declarative-model.json")
  }

  "render bpmn model" in {
    val checksEngine = new ChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf           = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderBpmnToFile(wf.workflow, "withdrawal-example-bpmn.bpmn")
    TestUtils.renderBpmnToFile(wf.workflowDeclarative, "withdrawal-example-bpmn-declarative.bpmn")
  }

  "render mermaid model" in {
    val checksEngine = new ChecksEngine[IO, testContext.ChecksContext.Ctx](testContext.ChecksContext)
    val wf           = testContext.createWorkflow(null, checksEngine)
    TestUtils.renderMermaidToFile(wf.workflow.toProgress, "withdrawal-example.mermaid")
    TestUtils.renderMermaidToFile(wf.workflowDeclarative.toProgress, "withdrawal-example-declarative.mermaid")
  }
}
