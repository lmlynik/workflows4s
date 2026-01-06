package workflows4s.example.checks

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.{Effect, FutureEffect}
import workflows4s.runtime.pekko.PekkoRuntimeAdapter
import workflows4s.testing.WorkflowTestAdapter

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike with ChecksEngineTestSuite[Future] {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  override given effect: Effect[Future] = FutureEffect.futureEffect

  override val testContext: ChecksEngineTestContext[Future] = new ChecksEngineTestContext[Future]

  override def createTrackingCheck(pendingCount: Int): Check[Future, Unit] & { def runNum: Int } =
    new Check[Future, Unit] {
      var runNum = 0

      override def key: CheckKey = CheckKey("foo")

      override def run(data: Unit): Future[CheckResult] = {
        if runNum < pendingCount then {
          runNum += 1
          Future.successful(CheckResult.Pending())
        } else {
          Future.successful(CheckResult.Approved())
        }
      }
    }

  "pekko with Future" - {
    "should create a PekkoRuntimeAdapter for Future checks engine context" in {
      val adapter = new PekkoRuntimeAdapter[testContext.Context.Ctx]("pekko-checks-future")(using testKit.system)
      assert(adapter != null)
    }
  }

  "in-memory with Future" - {
    val adapter = new WorkflowTestAdapter.InMemory[Future, testContext.Context.Ctx]()
    checkEngineTests(adapter)
  }
}
