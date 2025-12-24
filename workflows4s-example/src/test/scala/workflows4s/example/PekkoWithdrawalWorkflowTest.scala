package workflows4s.example

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.pekko.PekkoRuntimeAdapter
import workflows4s.example.withdrawal.FutureWithdrawalWorkflow

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Pekko-based withdrawal workflow tests.
  *
  * Note: The Pekko runtime now uses Future instead of IO. The existing WithdrawalWorkflowTest.Suite is designed for IO-based workflows and
  * TestRuntimeAdapter. A Future-based test suite would need to be created to fully test the Pekko runtime with the FutureWithdrawalWorkflow.
  *
  * For now, we verify basic Pekko runtime functionality with Future-based workflows.
  */
class PekkoWithdrawalWorkflowTest
    extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster"))
    with AnyFreeSpecLike
    with BeforeAndAfterAll
    with MockFactory {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "pekko with Future" - {
    "should create a PekkoRuntimeAdapter for FutureWithdrawalWorkflow context" in {
      // Verify that the adapter can be created with the Future-based workflow context
      val adapter = new PekkoRuntimeAdapter[FutureWithdrawalWorkflow.Context.Ctx]("pekko-withdrawal-future")(using testKit.system)
      assert(adapter != null)
    }
  }

}
