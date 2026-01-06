package workflows4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Inside.inside
import org.scalatest.freespec.{AnyFreeSpec, AnyFreeSpecLike}
import workflows4s.cats.CatsEffect.given
import workflows4s.example.TestUtils
import workflows4s.example.withdrawal.checks.*
import workflows4s.testing.{Runner, WorkflowTestAdapter}

import scala.annotation.nowarn
import scala.reflect.Selectable.reflectiveSelectable

class ChecksEngineTest extends AnyFreeSpec with ChecksEngineTest.Suite {

  implicit val runner: Runner[IO] = new Runner[IO] {
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[IO, IOChecksEngine.Context.Ctx]()
    checkEngineTests(adapter)
  }

  "render models" in {
    val checksEngine = IOChecksEngine.create()
    val wf = checksEngine.runChecks
    TestUtils.renderBpmnToFile(wf, "checks-engine.bpmn")
    TestUtils.renderMermaidToFile(wf.toProgress, "checks-engine.mermaid")
  }
}

object ChecksEngineTest {

  trait Suite extends AnyFreeSpecLike {

    def checkEngineTests(
        testAdapter: WorkflowTestAdapter[IO, IOChecksEngine.Context.Ctx],
    )(using runner: Runner[IO]): Unit = {

      "re-run pending checks until complete" in new Fixture(testAdapter) {
        val check: Check[IO, Unit] { def runNum: Int } = new Check[IO, Unit] {
          @nowarn("msg=unused private member") // compiler went nuts
          var runNum = 0

          override def key: CheckKey = CheckKey("foo")

          override def run(data: Unit): IO[CheckResult] = runNum match {
            case 0 | 1 =>
              IO {
                runNum += 1
              }.as(CheckResult.Pending())
            case _     => IO(CheckResult.Approved())
          }
        }

        val actor = createWorkflow(List(check))
        actor.run()
        assert(check.runNum == 1)

        inside(actor.state) { case x: ChecksState.Pending =>
          assert(x.results == Map(check.key -> CheckResult.Pending()))
        }

        // Advance clock using Scala FiniteDuration
        adapter.clock.advanceBy(ChecksEngine.retryBackoff)
        actor.run()
        assert(check.runNum == 2)
        inside(actor.state) { case x: ChecksState.Pending =>
          assert(x.results == Map(check.key -> CheckResult.Pending()))
        }

        adapter.clock.advanceBy(ChecksEngine.retryBackoff)
        actor.run()
        assert(actor.state == ChecksState.Decided(Map(check.key -> CheckResult.Approved()), Decision.ApprovedBySystem()))

        checkRecovery(actor)
      }

      "timeout checks" in new Fixture(testAdapter) {
        val check = StaticCheck(CheckResult.Pending())
        val actor = createWorkflow(List(check))
        actor.run()

        adapter.clock.advanceBy(ChecksEngine.timeoutThreshold)
        adapter.executeDueWakeup(actor.wf)

        assert(actor.state == ChecksState.Executed(Map(check.key -> CheckResult.TimedOut())))

        actor.review(ReviewDecision.Approve)
        assert(
          actor.state == ChecksState.Decided(
            Map(check.key -> CheckResult.TimedOut()),
            Decision.ApprovedByOperator(),
          ),
        )
      }

      class Fixture(val adapter: WorkflowTestAdapter[IO, IOChecksEngine.Context.Ctx]) extends StrictLogging {

        def createWorkflow(checks: List[Check[IO, Unit]]): ChecksActor = {
          // The result of runWorkflow is of type adapter.Actor
          val checksEngine = IOChecksEngine.create()
          val wf = adapter.runWorkflow(
            checksEngine.runChecks.provideInput(ChecksInput((), checks)),
            null: ChecksState,
          )
          new ChecksActor(wf)
        }

        def checkRecovery(firstActor: ChecksActor) = {
          val originalState  = firstActor.state
          // adapter.recover expects an adapter.Actor
          val secondActor    = adapter.recover(firstActor.wf)
          val recoveredState = runner.run(secondActor.queryState())
          assert(recoveredState == originalState)
        }

        // wf is now typed as adapter.Actor
        class ChecksActor(val wf: adapter.Actor) {
          def run(): Unit = runner.run(wf.wakeup())

          def state: ChecksState = runner.run(wf.queryState())

          def review(decision: ReviewDecision): Unit = {
            val _ = runner.run(wf.deliverSignal(ChecksEngine.signals, decision))
          }
        }
      }
    }
  }
}
