package workflows4s.example

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.scalamock.handlers.CallHandler2
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.{AnyFreeSpec, AnyFreeSpecLike}
import workflows4s.example.WithdrawalWorkflowTest.DummyChecksEngine
import workflows4s.example.checks.StaticCheck
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.example.withdrawal.checks.*
import workflows4s.testing.{Runner, WorkflowTestAdapter}

import scala.annotation.unused
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.JavaDurationOps
import workflows4s.cats.CatsEffect.given
import cats.effect.unsafe.implicits.global

//noinspection ForwardReference
class WithdrawalWorkflowTest extends AnyFreeSpec with MockFactory with WithdrawalWorkflowTest.Suite {
  given Runner[IO] = new Runner[IO] {
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[IO, IOWithdrawalWorkflow.Context.Ctx]()
    withdrawalTests(adapter)
  }

  "render model" in {
    val wf = IOWithdrawalWorkflow.create(null, DummyChecksEngine)
    TestUtils.renderModelToFile(wf.workflowDeclarative, "withdrawal-example-declarative-model.json")
  }

  "render bpmn model" in {
    val wf = IOWithdrawalWorkflow.create(null, DummyChecksEngine)
    TestUtils.renderBpmnToFile(wf.workflow, "withdrawal-example-bpmn.bpmn")
    TestUtils.renderBpmnToFile(wf.workflowDeclarative, "withdrawal-example-bpmn-declarative.bpmn")
  }
  "render mermaid model" in {
    val wf = IOWithdrawalWorkflow.create(null, DummyChecksEngine)
    TestUtils.renderMermaidToFile(wf.workflow.toProgress, "withdrawal-example.mermaid")
    TestUtils.renderMermaidToFile(wf.workflowDeclarative.toProgress, "withdrawal-example-declarative.mermaid")
  }
}
object WithdrawalWorkflowTest {

  trait Suite extends AnyFreeSpecLike with MockFactory {

    def withdrawalTests(
        testAdapter: => WorkflowTestAdapter[IO, IOWithdrawalWorkflow.Context.Ctx],
    )(using runner: Runner[IO]): Unit = {

      "happy path" in new Fixture(testAdapter) {
        assert(actor.queryData() == WithdrawalData.Empty)

        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(success = true)
        withFundsReleased()

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(
          actor.queryData() ==
            WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
        )

        persistProgress("happy-path-1")
        actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Succeeded)
        assert(actor.queryData() == WithdrawalData.Completed.Successfully())
        persistProgress("happy-path-2")

        checkRecovery()
      }
      "reject" - {

        "in validation" in new Fixture(testAdapter) {
          actor.init(CreateWithdrawal(txId, -100, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Amount must be positive"))
          persistProgress("failed-validation")
          checkRecovery()
        }

        "in funds lock" in new Fixture(testAdapter) {
          withFeeCalculation(fees)
          withMoneyOnHold(success = false)

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Not enough funds on the user's account"))
          persistProgress("failed-funds-lock")

          checkRecovery()
        }

        "in checks" in new Fixture(testAdapter) {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withChecks(List(StaticCheck(CheckResult.Rejected())))
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Transaction rejected in checks"))
          persistProgress("failed-checks")

          checkRecovery()
        }

        "in execution initiation" in new Fixture(testAdapter) {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = false)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Rejected by execution engine"))
          persistProgress("failed-execution-initiation")

          checkRecovery()
        }

        "in execution confirmation" in new Fixture(testAdapter) {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = true)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Failed)
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Execution failed"))
          persistProgress("failed-execution")

          checkRecovery()
        }
      }

      "cancel" - {

        // other tests require concurrent testing
        "when waiting for execution confirmation" in new Fixture(testAdapter) {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = true)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))
          persistProgress("canceled-waiting-for-execution-confirmation")

          checkRecovery()
        }

        "when running checks" in new Fixture(testAdapter) {
          val check = StaticCheck(CheckResult.Pending())
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withChecks(List(check))
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          inside(actor.queryData()) { case data: WithdrawalData.Checking =>
            assert(data.checkResults.results == Map(check.key -> CheckResult.Pending()))
          }
          actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))
          persistProgress("canceled-running-checks")

          checkRecovery()
        }

      }

      "retry execution" in new Fixture(testAdapter) {
        assert(actor.queryData() == WithdrawalData.Empty)

        @unused
        var retryCount          = 0
        val executionInitiation = IO {
          if retryCount == 0 then {
            retryCount += 1
            throw new RuntimeException("Failed to initiate execution")
          } else {
            ExecutionResponse.Accepted(externalId)
          }
        }

        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(executionInitiation).anyNumberOfTimes()

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(
          actor.queryData() ==
            WithdrawalData.Checked(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem())),
        )
        adapter.clock.advanceBy(WithdrawalWorkflow.executionRetryDelay.toScala)
        adapter.clock.advanceBy(1.second)
        adapter.executeDueWakeup(actor.wf)
        assert(
          actor.queryData() ==
            WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
        )

        checkRecovery()
      }

      class Fixture(val adapter: WorkflowTestAdapter[IO, IOWithdrawalWorkflow.Context.Ctx]) extends StrictLogging {
        val txId  = "abc"
        val actor = createActor()

        def checkRecovery() = {
          logger.debug("Checking recovery")
          val originalState  = runner.run(actor.wf.queryState())
          val secondActor    = adapter.recover(actor.wf)
          // seems sometimes querying state from fresh actor gets flaky
          val recoveredState = eventually {
            runner.run(secondActor.queryState())
          }
          assert(recoveredState == originalState)
        }

        def createActor() = {
          val wf    = adapter
            .runWorkflow(
              workflow,
              WithdrawalData.Empty,
            )
          val actor = new WithdrawalActor(wf)
          actor
        }

        lazy val amount                     = 100
        lazy val recipient                  = Iban("A")
        lazy val fees                       = Fee(11)
        lazy val externalId                 = "external-id-1"
        lazy val service: WithdrawalService[IO] = mock[WithdrawalService[IO]]

        def checksEngine: ChecksEngine[IO, IOChecksEngine.Context.Ctx] = DummyChecksEngine

        def workflow: IOWithdrawalWorkflow.Context.WIO.Initial =
          IOWithdrawalWorkflow.create(service, checksEngine).workflowDeclarative

        def withFeeCalculation(fee: Fee) =
          (service.calculateFees).expects(*).returning(IO(fee))

        def withMoneyOnHold(success: Boolean) =
          (service.putMoneyOnHold).expects(*).returning(IO(Either.cond(success, (), WithdrawalService.NotEnoughFunds())))

        def withExecutionInitiated(success: Boolean): Unit = {
          withExecutionInitiated(
            IO(if success then ExecutionResponse.Accepted(externalId) else ExecutionResponse.Rejected("Rejected by execution engine")),
          )
          ()
        }

        def withExecutionInitiated(result: IO[ExecutionResponse]): CallHandler2[BigDecimal, Iban, IO[ExecutionResponse]] = {
          (service.initiateExecution)
            .expects(*, *)
            .returning(result)
        }

        def withFundsReleased() =
          (service.releaseFunds)
            .expects(*)
            .returning(IO.unit)

        def withFundsLockCancelled() =
          ((() => service.cancelFundsLock()))
            .expects()
            .returning(IO.unit)

        def withChecks(list: List[Check[IO, WithdrawalData.Validated]]) =
          ((() => service.getChecks()))
            .expects()
            .returning(list)
            .anyNumberOfTimes()

        def withNoChecks() = withChecks(List())

        class WithdrawalActor(val wf: adapter.Actor) {
          def init(req: CreateWithdrawal): Unit = {
            val _ = runner.run(wf.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, req))
          }

          def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit = {
            val _ = runner.run(wf.deliverSignal(WithdrawalWorkflow.Signals.executionCompleted, req))
          }

          def cancel(req: WithdrawalSignal.CancelWithdrawal): Unit = {
            val _ = runner.run(wf.deliverSignal(WithdrawalWorkflow.Signals.cancel, req))
          }

          def queryData(): WithdrawalData = runner.run(wf.queryState())
        }

        def persistProgress(name: String): Unit = {
          TestUtils.renderMermaidToFile(actor.wf.getProgress.unsafeRunSync(), s"withdrawal/progress-$name.mermaid")
        }

      }

    }
  }

  object DummyChecksEngine extends ChecksEngine[IO, IOChecksEngine.Context.Ctx](IOChecksEngine.Context) {
    override def runChecks: IOChecksEngine.Context.WIO[ChecksInput[IO], Nothing, ChecksState.Decided] =
      IOChecksEngine.Context.WIO.pure(ChecksState.Decided(Map(), Decision.ApprovedBySystem())).autoNamed
  }

}
