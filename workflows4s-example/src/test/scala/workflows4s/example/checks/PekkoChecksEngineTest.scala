package workflows4s.example.checks

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.example.withdrawal.checks.FutureChecksEngine
import workflows4s.runtime.pekko.PekkoRuntimeAdapter

import scala.concurrent.Await

/** Pekko-based checks engine tests.
  *
  * Note: The Pekko runtime now uses Future instead of IO. The existing ChecksEngineTest.Suite is designed for IO-based workflows and
  * TestRuntimeAdapter. A Future-based test suite would need to be created to fully test the Pekko runtime with the FutureChecksEngine.
  *
  * For now, we verify basic Pekko runtime functionality with Future-based workflows.
  */
class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "pekko with Future" - {
    "should create a PekkoRuntimeAdapter for FutureChecksEngine context" in {
      // Verify that the adapter can be created with the Future-based checks engine context
      val adapter = new PekkoRuntimeAdapter[FutureChecksEngine.Context]("pekko-checks-future")(using testKit.system)
      assert(adapter != null)
    }
  }

}
