package workflows4s.zio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.TestState
import zio.*

import scala.concurrent.duration.DurationInt

/** ZIO-based workflow runtime test suite for testing concurrency behavior. */
class ZIOWorkflowRuntimeTest extends AnyFreeSpec with Matchers {

  private def runSync[A](task: Task[A]): A = Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe.run(task).getOrThrow()
  }

  "ZIOWorkflowRuntime" - {

    "runtime should not allow interrupting a process while another step is running" in {
      def singleRun(i: Int): Task[Unit] = {
        ZIO.succeed(println(s"Running $i iteration")) *> {
          import ZIOTestCtx2.*

          for {
            longRunningStartedPromise  <- Promise.make[Nothing, Unit]
            longrunningFinishedPromise <- Promise.make[Nothing, Unit]
            signalStartedRef           <- Ref.make(false)

            (longRunningStepId, longRunningStep) = ZIOTestUtils.runIOCustom(
                                                     longRunningStartedPromise.succeed(()) *> longrunningFinishedPromise.await,
                                                   )

            (signal, _, signalStep) = ZIOTestUtils.signalCustom(signalStartedRef.set(true))

            wio = longRunningStep.interruptWith(signalStep.toInterruption)

            runtime = ZIOTestRuntimeAdapter[ZIOTestCtx2.Ctx]()
            wf      = runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)

            wakeupFiber <- wf.wakeup().fork
            signalFiber <- (longRunningStartedPromise.await *> wf.deliverSignal(signal, 1)).fork

            initialState <- wf.queryState()
            _             = assert(initialState == TestState.empty)

            _ <- longrunningFinishedPromise.succeed(())
            _ <- wakeupFiber.join.catchAll(_ => ZIO.fail(new Exception("wakeup was cancelled")))

            stateAfterWakeup <- wf.queryState()
            _                 = assert(stateAfterWakeup == TestState(List(longRunningStepId)))

            signalResult  <- signalFiber.join.either
            signalStarted <- signalStartedRef.get

            _ <- ZIO.succeed(assert(!signalStarted))
            _ <- ZIO.succeed(assert(signalResult.isLeft || signalResult.exists(_.isLeft)))

            finalState <- wf.queryState()
            _           = assert(finalState == TestState(List(longRunningStepId)))
          } yield ()
        }
      }

      runSync {
        ZIO
          .foreachDiscard((1 to 50).toList)(singleRun)
          .timeout(zio.Duration.fromScala(60.seconds))
          .someOrFail(new Exception("Test timed out"))
      }
    }
  }
}
