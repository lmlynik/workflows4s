package workflows4s.ox.magnum

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import workflows4s.ox.Direct
import workflows4s.ox.DirectWorkflowContext
import workflows4s.ox.OxEffect.given
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.registry.WorkflowRegistry.Tagger
import workflows4s.testing.TestClock
import workflows4s.wio.ActiveWorkflow
import workflows4s.wio.WIO

/** Property-based tests for OxPostgresRegistry.
  *
  * These tests verify the correctness properties defined in the design document for the ox-magnum-repo-refactor feature.
  */
class OxPostgresRegistryPropertyTest extends AnyFreeSpec with OxMagnumTestSuite with Matchers with ScalaCheckPropertyChecks {

  // Configure ScalaCheck to run at least 100 iterations per property
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 100)

  // Generators for test data
  val genAlphanumericString: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty).map(_.take(20))

  val genWorkflowInstanceId: Gen[WorkflowInstanceId] = for {
    templateId <- genAlphanumericString
    instanceId <- genAlphanumericString
  } yield WorkflowInstanceId(templateId, instanceId)

  val genExecutionStatus: Gen[ExecutionStatus] = Gen.oneOf(
    ExecutionStatus.Running,
    ExecutionStatus.Awaiting,
    ExecutionStatus.Finished,
  )

  val genTags: Gen[Map[String, String]] = Gen.mapOf(
    for {
      key   <- genAlphanumericString
      value <- genAlphanumericString
    } yield (key, value),
  )

  given arbWorkflowInstanceId: Arbitrary[WorkflowInstanceId] = Arbitrary(genWorkflowInstanceId)
  given arbExecutionStatus: Arbitrary[ExecutionStatus]       = Arbitrary(genExecutionStatus)
  given arbTags: Arbitrary[Map[String, String]]              = Arbitrary(genTags)

  // Test helpers
  private case class DummyState(tags: Map[String, String])

  private object DummyContext extends DirectWorkflowContext {
    type State = DummyState
    type Event = Nothing
  }

  private def dummyWorkflow(id: WorkflowInstanceId, state: DummyState = DummyState(Map.empty)): ActiveWorkflow[Direct, DummyContext.type] = {
    ActiveWorkflow(id, WIO.End(), state)
  }

  private class TestTagger extends Tagger[DummyState] {
    override def getTags(id: WorkflowInstanceId, state: DummyState): Map[String, String] = {
      state.tags
    }
  }

  "OxPostgresRegistry Property Tests" - {

    /** Feature: ox-magnum-repo-refactor, Property 2: Upsert Correctness
      *
      * *For any* workflow instance and execution status, after calling `upsertInstance`:
      *   - If the instance did not exist, a new row should be created with the correct values
      *   - If the instance already existed, the row should be updated with the new status, updated_at, wakeup_at, and tags
      *   - The resulting row should have the expected field values
      *
      * **Validates: Requirements 4.1, 4.2, 4.3**
      */
    "Property 2: Upsert Correctness - new instances are created with correct values" in {
      forAll(genWorkflowInstanceId, genExecutionStatus) { (id: WorkflowInstanceId, status: ExecutionStatus) =>
        val clock    = new TestClock()
        val registry = OxPostgresRegistry(transactor, clock = clock).run

        // Upsert a new workflow
        registry.upsertInstance(dummyWorkflow(id), status).run

        // Verify the workflow exists with the correct status
        val expectedStatusList = status match {
          case ExecutionStatus.Running  => registry.getWorkflowsByStatus(ExecutionStatus.Running).run
          case ExecutionStatus.Awaiting => registry.getWorkflowsByStatus(ExecutionStatus.Awaiting).run
          case ExecutionStatus.Finished => registry.getWorkflowsByStatus(ExecutionStatus.Finished).run
        }

        assert(expectedStatusList.contains(id), s"Expected $id to be in status list $expectedStatusList")

        // Clean up for next iteration
        cleanupWorkflow(id)
      }
    }

    "Property 2: Upsert Correctness - existing instances are updated with new status" in {
      forAll(genWorkflowInstanceId, genExecutionStatus, genExecutionStatus) {
        (id: WorkflowInstanceId, initialStatus: ExecutionStatus, newStatus: ExecutionStatus) =>
          val clock    = new TestClock()
          val registry = OxPostgresRegistry(transactor, clock = clock).run

          // Create initial workflow
          registry.upsertInstance(dummyWorkflow(id), initialStatus).run

          // Update with new status
          registry.upsertInstance(dummyWorkflow(id), newStatus).run

          // Verify the workflow has the new status
          val workflowsWithNewStatus = newStatus match {
            case ExecutionStatus.Running  => registry.getWorkflowsByStatus(ExecutionStatus.Running).run
            case ExecutionStatus.Awaiting => registry.getWorkflowsByStatus(ExecutionStatus.Awaiting).run
            case ExecutionStatus.Finished => registry.getWorkflowsByStatus(ExecutionStatus.Finished).run
          }

          assert(workflowsWithNewStatus.contains(id), s"Expected $id to be in new status list $workflowsWithNewStatus")

          // If status changed, verify it's not in the old status list
          if initialStatus != newStatus then {
            val workflowsWithOldStatus = initialStatus match {
              case ExecutionStatus.Running  => registry.getWorkflowsByStatus(ExecutionStatus.Running).run
              case ExecutionStatus.Awaiting => registry.getWorkflowsByStatus(ExecutionStatus.Awaiting).run
              case ExecutionStatus.Finished => registry.getWorkflowsByStatus(ExecutionStatus.Finished).run
            }
            assert(!workflowsWithOldStatus.contains(id), s"Expected $id to NOT be in old status list $workflowsWithOldStatus"): Unit
          }

          // Clean up for next iteration
          cleanupWorkflow(id)
      }
    }

    /** Feature: ox-magnum-repo-refactor, Property 3: Tag Preservation on NULL Update
      *
      * *For any* existing workflow with non-null tags, upserting with NULL tags should preserve the existing tags (COALESCE behavior).
      *
      * **Validates: Requirements 4.4**
      */
    "Property 3: Tag Preservation on NULL Update - existing tags are preserved when updating with NULL tags" in {
      // Generate non-empty tags for initial state
      val genNonEmptyTags: Gen[Map[String, String]] = Gen.nonEmptyMap(
        for {
          key   <- genAlphanumericString
          value <- genAlphanumericString
        } yield (key, value),
      )

      forAll(genWorkflowInstanceId, genNonEmptyTags, genExecutionStatus, genExecutionStatus) {
        (id: WorkflowInstanceId, initialTags: Map[String, String], initialStatus: ExecutionStatus, newStatus: ExecutionStatus) =>
          val clock    = new TestClock()
          val tagger   = Some(new TestTagger().asInstanceOf[Tagger[Any]])
          val registry = OxPostgresRegistry(transactor, clock = clock, tagger = tagger).run

          // Create workflow with tags
          registry.upsertInstance(dummyWorkflow(id, DummyState(initialTags)), initialStatus).run

          // Verify tags were stored
          val workflowsWithTags = registry.getWorkflowsByTags(initialTags).run
          assert(workflowsWithTags.contains(id), s"Expected $id to be found with tags $initialTags")

          // Update workflow with empty tags (NULL)
          registry.upsertInstance(dummyWorkflow(id, DummyState(Map.empty)), newStatus).run

          // Verify original tags are preserved (COALESCE behavior)
          val workflowsWithTagsAfterUpdate = registry.getWorkflowsByTags(initialTags).run
          assert(workflowsWithTagsAfterUpdate.contains(id), s"Expected $id to still have tags $initialTags after update with NULL tags")

          // Clean up for next iteration
          cleanupWorkflow(id)
      }
    }
  }

  /** Helper to clean up a workflow after each property test iteration */
  private def cleanupWorkflow(id: WorkflowInstanceId): Unit = {
    import com.augustnagro.magnum.*
    transact(transactor) {
      sql"DELETE FROM workflow_registry WHERE template_id = ${id.templateId} AND instance_id = ${id.instanceId}".update.run(): Unit
    }
  }
}
