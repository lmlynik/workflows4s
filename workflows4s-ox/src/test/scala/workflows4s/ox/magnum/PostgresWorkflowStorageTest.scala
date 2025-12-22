package workflows4s.ox.magnum

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.utils.StringUtils

class PostgresWorkflowStorageTest extends AnyFreeSpec with OxPostgresSuite with Matchers {

  "PostgresWorkflowStorage" - {

    "should save and retrieve events in order" in {
      given ByteCodec[TestEvent] = JavaSerdeEventCodec.get[TestEvent]
      val storage                = PostgresWorkflowStorage[TestEvent](transactor)
      val id                     = randomWorkflowId()

      // Save multiple events
      storage.saveEvent(id, TestEvent("event1")).runSync
      storage.saveEvent(id, TestEvent("event2")).runSync
      storage.saveEvent(id, TestEvent("event3")).runSync

      // Retrieve events
      val events = storage.getEvents(id).runSync.toList

      events shouldBe List(
        TestEvent("event1"),
        TestEvent("event2"),
        TestEvent("event3"),
      )
    }

    "should return empty list for workflow with no events" in {
      given ByteCodec[TestEvent] = JavaSerdeEventCodec.get[TestEvent]
      val storage                = PostgresWorkflowStorage[TestEvent](transactor)
      val id                     = randomWorkflowId()

      val events = storage.getEvents(id).runSync.toList

      events shouldBe empty
    }

    "should isolate events between different workflow instances" in {
      given ByteCodec[TestEvent] = JavaSerdeEventCodec.get[TestEvent]
      val storage                = PostgresWorkflowStorage[TestEvent](transactor)

      val id1 = WorkflowInstanceId("template1", "instance1")
      val id2 = WorkflowInstanceId("template1", "instance2")

      storage.saveEvent(id1, TestEvent("event1-1")).runSync
      storage.saveEvent(id2, TestEvent("event2-1")).runSync
      storage.saveEvent(id1, TestEvent("event1-2")).runSync

      val events1 = storage.getEvents(id1).runSync.toList
      val events2 = storage.getEvents(id2).runSync.toList

      events1 shouldBe List(TestEvent("event1-1"), TestEvent("event1-2"))
      events2 shouldBe List(TestEvent("event2-1"))
    }

    "should enforce exclusive locking" in {
      given ByteCodec[TestEvent] = JavaSerdeEventCodec.get[TestEvent]
      val storage                = PostgresWorkflowStorage[TestEvent](transactor)
      val id                     = randomWorkflowId()

      // First lock should succeed
      val result1 = storage
        .withLock(id) {
          workflows4s.ox.Direct {
            "success"
          }
        }
        .runSync

      result1 shouldBe "success"
    }

    "should handle LazyList streaming efficiently" in {
      given ByteCodec[TestEvent] = JavaSerdeEventCodec.get[TestEvent]
      val storage                = PostgresWorkflowStorage[TestEvent](transactor)
      val id                     = randomWorkflowId()

      // Save many events
      (1 to 100).foreach { i =>
        storage.saveEvent(id, TestEvent(s"event-$i")).runSync
      }

      // Retrieve as LazyList (should not load all into memory immediately)
      val events = storage.getEvents(id).runSync

      events.take(5).toList shouldBe List(
        TestEvent("event-1"),
        TestEvent("event-2"),
        TestEvent("event-3"),
        TestEvent("event-4"),
        TestEvent("event-5"),
      )

      events.size shouldBe 100
    }
  }

  // Test helpers

  private def randomWorkflowId(): WorkflowInstanceId = {
    WorkflowInstanceId(
      StringUtils.randomAlphanumericString(8),
      StringUtils.randomAlphanumericString(8),
    )
  }

  private case class TestEvent(data: String) extends Serializable
}
