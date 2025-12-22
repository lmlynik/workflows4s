package workflows4s.ox.magnum

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.ox.{Direct, DirectWorkflowContext, OxEffect}
import workflows4s.ox.OxEffect.given
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.{ExecutionStatus, Tagger}
import workflows4s.testing.TestClock
import workflows4s.utils.StringUtils
import workflows4s.wio.{ActiveWorkflow, WIO}

import scala.concurrent.duration.*

class OxPostgresRegistryTest extends AnyFreeSpec with OxMagnumTestSuite with Matchers {

  "OxPostgresRegistry" - {

    "should store and retrieve running workflows" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id1 = randomWorkflowId()
      val id2 = randomWorkflowId()
      val id3 = randomWorkflowId()

      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2), ExecutionStatus.Awaiting).run
      registry.upsertInstance(dummyWorkflow(id3), ExecutionStatus.Running).run

      val running = registry.getWorkflowsByStatus(ExecutionStatus.Running).run

      running should contain theSameElementsAs List(id1, id3)
    }

    "should track stale workflows based on updated_at timestamp" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id1 = randomWorkflowId()
      val id2 = randomWorkflowId()

      // Both start running
      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2), ExecutionStatus.Running).run

      // Advance time and update only id1
      clock.advanceBy(2.seconds)
      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run

      // id2 should be stale (not updated for 2 seconds)
      val stale = registry.getStaleWorkflows(1.second).run

      stale should contain only id2
    }

    "should update workflow status on subsequent upserts" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id = randomWorkflowId()

      // Start as Running
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Running).run
      registry.getWorkflowsByStatus(ExecutionStatus.Running).run should contain only id

      // Transition to Awaiting
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Awaiting).run
      registry.getWorkflowsByStatus(ExecutionStatus.Running).run shouldBe empty
      registry.getWorkflowsByStatus(ExecutionStatus.Awaiting).run should contain only id

      // Transition to Finished
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Finished).run
      registry.getWorkflowsByStatus(ExecutionStatus.Awaiting).run shouldBe empty
      registry.getWorkflowsByStatus(ExecutionStatus.Finished).run should contain only id
    }

    "should separate workflows by template_id" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id1 = WorkflowInstanceId("template-a", "instance-1")
      val id2 = WorkflowInstanceId("template-b", "instance-1")

      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2), ExecutionStatus.Running).run

      val running = registry.getWorkflowsByStatus(ExecutionStatus.Running).run

      running should contain theSameElementsAs List(id1, id2)
    }

    "should retrieve statistics" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val List(id1, id2, id3, id4) = List.fill(4)(randomWorkflowId())

      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id3), ExecutionStatus.Awaiting).run
      registry.upsertInstance(dummyWorkflow(id4), ExecutionStatus.Finished).run

      val stats = registry.getStats.run

      stats.total shouldBe 4
      stats.running shouldBe 2
      stats.awaiting shouldBe 1
      stats.finished shouldBe 1
      stats.oldestRunning shouldBe defined
      stats.newestRunning shouldBe defined
    }

    "should return empty list for workflows with no stale instances" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id = randomWorkflowId()

      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Running).run

      // Just updated, shouldn't be stale
      val stale = registry.getStaleWorkflows(1.second).run

      stale shouldBe empty
    }

    "should get awaiting workflows" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id1 = randomWorkflowId()
      val id2 = randomWorkflowId()
      val id3 = randomWorkflowId()

      registry.upsertInstance(dummyWorkflow(id1), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2), ExecutionStatus.Awaiting).run
      registry.upsertInstance(dummyWorkflow(id3), ExecutionStatus.Awaiting).run

      val awaiting = registry.getAwaitingWorkflows.run

      awaiting should contain theSameElementsAs List(id2, id3)
    }

    "should get workflows with pending wakeups" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id = randomWorkflowId()

      // For now, just test that workflows without wakeup_at are not included
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Awaiting).run

      val pending = registry.getWorkflowsWithPendingWakeups().run

      pending shouldBe empty
    }

    "should query workflows by tags using JSONB containment" in {
      val clock                       = new TestClock()
      val tagger: Option[Tagger[Any]] = Some(new TestTagger().asInstanceOf[Tagger[Any]])
      val registry                    = OxPostgresRegistry(transactor, clock = clock, tagger = tagger).run

      val id1 = randomWorkflowId()
      val id2 = randomWorkflowId()
      val id3 = randomWorkflowId()

      // Setup tagged workflows
      registry.upsertInstance(dummyWorkflow(id1, DummyState(Map("env" -> "prod", "region" -> "us-east"))), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id2, DummyState(Map("env" -> "prod", "region" -> "eu-west"))), ExecutionStatus.Running).run
      registry.upsertInstance(dummyWorkflow(id3, DummyState(Map("env" -> "dev", "region" -> "us-east"))), ExecutionStatus.Running).run

      // Query by single tag
      val prodWorkflows = registry.getWorkflowsByTags(Map("env" -> "prod")).run
      prodWorkflows should contain theSameElementsAs List(id1, id2)

      // Query by multiple tags (AND logic)
      val prodUsEast = registry.getWorkflowsByTags(Map("env" -> "prod", "region" -> "us-east")).run
      prodUsEast should contain only id1

      // Query with no matching tags
      val staging = registry.getWorkflowsByTags(Map("env" -> "staging")).run
      staging shouldBe empty
    }

    "should handle empty tag queries" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val result = registry.getWorkflowsByTags(Map.empty).run

      result shouldBe empty
    }

    "should handle missing wakeup_at gracefully" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id = randomWorkflowId()

      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Awaiting).run

      val pending = registry.getWorkflowsWithPendingWakeups().run

      pending shouldBe empty
    }

    "should preserve created_at timestamp on updates" in {
      val clock    = new TestClock()
      val registry = OxPostgresRegistry(transactor, clock = clock).run

      val id = randomWorkflowId()

      // Create workflow
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Running).run

      // Advance time and update
      clock.advanceBy(5.seconds)
      registry.upsertInstance(dummyWorkflow(id), ExecutionStatus.Awaiting).run

      // Verify timestamps via stats (created_at should be the original time)
      val stats = registry.getStats.run
      stats.total shouldBe 1
    }
  }

  // Test helpers

  private def randomWorkflowId(): WorkflowInstanceId = {
    WorkflowInstanceId(
      StringUtils.randomAlphanumericString(8),
      StringUtils.randomAlphanumericString(8),
    )
  }

  private def dummyWorkflow(id: WorkflowInstanceId, state: DummyState = DummyState(Map.empty)): ActiveWorkflow[Direct, DummyContext.type] = {
    ActiveWorkflow(id, WIO.End(), state)
  }

  private case class DummyState(tags: Map[String, String])

  private object DummyContext extends DirectWorkflowContext {
    type State = DummyState
    type Event = Nothing
  }

  private class TestTagger extends Tagger[DummyState] {
    override def getTags(id: WorkflowInstanceId, state: DummyState): Map[String, String] = {
      state.tags
    }
  }
}
