package workflows4s.runtime.pekko

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.testing.FutureTestUtils
import workflows4s.wio.{FutureTestCtx, TestState}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

/** Tests for Pekko runtime using Future effect type.
  *
  * Note: The Pekko runtime now uses Future instead of IO. The IO-based concurrency tests (IOWorkflowRuntimeTest) are not applicable here.
  * Future-based concurrency testing would require different primitives than cats-effect's Semaphore/Ref.
  */
class PekkoRuntimeTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "Pekko runtime with Future" - {
    "should create and run a simple workflow" in {
      val adapter       = new PekkoRuntimeAdapter[FutureTestCtx.type]("simple-test")(using testKit.system)
      val (stepId, wio) = FutureTestUtils.pure

      // Convert to the expected type using asInstanceOf since the types are structurally equivalent
      val initialWio: workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type] =
        wio.provideInput(TestState.empty).asInstanceOf[workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type]]

      val actor = adapter.runWorkflow(initialWio, TestState.empty)

      // Trigger the workflow
      Await.result(actor.wakeup(), 10.seconds)

      // Verify the state
      val state = Await.result(actor.queryState(), 10.seconds)
      assert(state == TestState(List(stepId)))
    }

    "should handle signals" in {
      given ExecutionContext       = testKit.system.executionContext
      val adapter                  = new PekkoRuntimeAdapter[FutureTestCtx.type]("signal-test")(using testKit.system)
      val (signalDef, stepId, wio) = FutureTestUtils.signal

      // Convert to the expected type using asInstanceOf since the types are structurally equivalent
      val initialWio: workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type] =
        wio.provideInput(TestState.empty).asInstanceOf[workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type]]

      val actor = adapter.runWorkflow(initialWio, TestState.empty)

      // Deliver signal
      val result = Await.result(actor.deliverSignal(signalDef, 42), 10.seconds)
      assert(result == Right(42))

      // Verify the state
      val state = Await.result(actor.queryState(), 10.seconds)
      assert(state == TestState(List(stepId)))
    }

    "should recover from events" in {
      val adapter       = new PekkoRuntimeAdapter[FutureTestCtx.type]("recovery-test")(using testKit.system)
      val (stepId, wio) = FutureTestUtils.pure

      // Convert to the expected type using asInstanceOf since the types are structurally equivalent
      val initialWio: workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type] =
        wio.provideInput(TestState.empty).asInstanceOf[workflows4s.wio.WIO.Initial[Future, FutureTestCtx.type]]

      val actor = adapter.runWorkflow(initialWio, TestState.empty)

      // Trigger the workflow
      Await.result(actor.wakeup(), 10.seconds)

      // Verify initial state
      val state1 = Await.result(actor.queryState(), 10.seconds)
      assert(state1 == TestState(List(stepId)))

      // Recover and verify state is preserved
      val recovered = adapter.recover(actor)
      val state2    = Await.result(recovered.queryState(), 10.seconds)
      assert(state2 == state1)
    }
  }

}
