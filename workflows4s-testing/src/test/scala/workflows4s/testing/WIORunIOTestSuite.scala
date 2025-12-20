package workflows4s.testing

import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.{Effect, UnsafeRun}
import workflows4s.wio.WIO.RunIO
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.{ActiveWorkflow, ErrorMeta, SignalDef, WCState, WorkflowContext}

import java.time.Instant

/** Abstract test suite for WIO.RunIO operations. Extend this trait and provide effect-specific implementations.
  *
  * @tparam F
  *   the effect type (IO, Task, Direct, etc.)
  */
trait WIORunIOTestSuite[F[_]] extends AnyFreeSpecLike with Matchers with EitherValues {

  /** The Effect instance for F */
  def effectInstance: Effect[F]

  /** The UnsafeRun instance for F */
  def unsafeRunInstance: UnsafeRun[F]

  /** Create a pure effect containing the given value */
  def pure[A](a: A): F[A] = effectInstance.pure(a)

  /** Create a failed effect with the given exception */
  def raiseError[A](ex: Throwable): F[A] = effectInstance.raiseError(ex)

  /** Run an effect synchronously */
  def runSync[A](fa: F[A]): A = unsafeRunInstance.unsafeRunSync(fa)

  /** Effect-polymorphic test context */
  object TestCtx extends WorkflowContext {
    trait Event
    case class SimpleEvent(value: String) extends Event
    type State = String

    type Eff[A] = F[A]
    given effect: Effect[Eff] = effectInstance

    extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
      def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Eff, Ctx] =
        ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
    }

    def ignore[A, B, C]: (A, B) => C = (_, _) => ???

    given Conversion[String, SimpleEvent] = SimpleEvent.apply
  }

  import TestCtx.{SimpleEvent, ignore, toWorkflow, given}

  def wioRunIOTests(): Unit = {

    "proceed" in {
      val wf = TestCtx.WIO
        .runIO[String](input => pure(s"ProcessedEvent($input)"))
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)

      assert(resultOpt.toRaw.isDefined)
      val processingResult = runSync(resultOpt.toRaw.get)
      processingResult match {
        case WakeupResult.ProcessingResult.Proceeded(event) =>
          assert(event == SimpleEvent("ProcessedEvent(initialState)"))
        case _                                              => fail("Expected Proceeded result")
      }
    }

    "error in IO" in {
      val wf = TestCtx.WIO
        .runIO[String](_ => raiseError(new RuntimeException("IO failed")))
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val Some(result) = wf.proceed(Instant.now).toRaw: @unchecked

      val processingResult = runSync(result)
      processingResult match {
        case WakeupResult.ProcessingResult.Failed(ex) =>
          assert(ex.getMessage == "IO failed")
        case _                                        => fail("Expected Failed result")
      }
    }

    "event handling" in {
      val wf = TestCtx.WIO
        .runIO[String](_ => ???)
        .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
        .done
        .toWorkflow("initialState")

      val Some(result) = wf.handleEvent("my-event"): @unchecked

      assert(result.staticState == "SuccessHandled(initialState, SimpleEvent(my-event))")
    }

    "handle signal" in {
      val wf = TestCtx.WIO
        .runIO[Any](_ => ???)
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.handleSignal(SignalDef[String, String]())("").toRaw

      assert(resultOpt.isEmpty)
    }

    "metadata attachment" - {
      val base = TestCtx.WIO
        .runIO[String](input => pure(s"EventGenerated($input)"))
        .handleEvent(ignore)

      extension (x: TestCtx.WIO[?, ?, ?]) {
        def extractMeta: RunIO.Meta = x.asInstanceOf[workflows4s.wio.WIO.RunIO[?, ?, ?, ?, ?, ?]].meta
      }

      "defaults" in {
        val wio = base.done

        val meta = wio.extractMeta
        assert(meta == RunIO.Meta(ErrorMeta.NoError(), None, None))
      }

      "explicitly named" in {
        val wio = base.named("ExplicitRunIO")

        val meta = wio.extractMeta
        assert(meta.name.contains("ExplicitRunIO"))
      }

      "autonamed" in {
        val autonamedRunIO = base.autoNamed()

        val meta = autonamedRunIO.extractMeta
        assert(meta.name.contains("Autonamed Run IO"))
      }

      "error autonamed" in {
        val wio = TestCtx.WIO
          .runIO[String](_ => ???)
          .handleEventWithError((_, _) => Left(""))
          .done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("String"))
      }

      "error explicitly named" in {
        val wio = TestCtx.WIO
          .runIO[String](_ => ???)
          .handleEventWithError(ignore)(using ErrorMeta.Present("XXX"))
          .done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("XXX"))
      }
    }
  }
}
