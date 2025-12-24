package workflows4s.runtime.pekko

import workflows4s.wio.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/** Future-based test utilities for Pekko runtime tests.
  */
object FutureTestUtils {

  type Error = String

  def pure: (StepId, FutureTestCtx.WIO[TestState, Nothing, TestState]) = {
    import FutureTestCtx.*
    val stepId = StepId.random("pure")
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }

  def error: (Error, FutureTestCtx.WIO[Any, String, Nothing]) = {
    import FutureTestCtx.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }

  def runFuture(using ec: ExecutionContext): (StepId, FutureTestCtx.WIO[TestState, Nothing, TestState]) = {
    runFutureCustom(Future.successful(()))
  }

  def errorFuture: (Error, FutureTestCtx.WIO[Any, String, Nothing]) = {
    import FutureTestCtx.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunFutureErrored(error: String) extends FutureTestCtx.Event
    val wio = WIO
      .runIO[Any](_ => Future.successful(RunFutureErrored(error)))
      .handleEventWithError((_, evt: RunFutureErrored) => Left(evt.error))
      .done
    (error, wio)
  }

  def runFutureCustom(logic: Future[Unit])(using ec: ExecutionContext): (StepId, FutureTestCtx.WIO[TestState, Nothing, TestState]) = {
    import FutureTestCtx.*
    case class RunFutureDone(stepId: StepId) extends FutureTestCtx.Event
    val stepId = StepId.random
    val wio    = WIO
      .runIO[TestState](_ => logic.map(_ => RunFutureDone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def errorHandler: FutureTestCtx.WIO[(TestState, Error), Nothing, TestState] = {
    import FutureTestCtx.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  // inline assures two calls get different events
  inline def signal(using
      ec: ExecutionContext,
  ): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Future, TestState, Nothing, TestState, FutureTestCtx.Ctx]) =
    signalCustom(Future.successful(()))

  // inline assures two calls get different events
  inline def signalCustom(
      logic: Future[Unit],
  )(using ec: ExecutionContext): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Future, TestState, Nothing, TestState, FutureTestCtx.Ctx]) = {
    import FutureTestCtx.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends FutureTestCtx.Event with Serializable {
      override def toString: String = s"SigEvent(${req})"
    }
    val stepId = StepId.random("signal")
    val wio = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .withSideEffects((_, req) => logic.map(_ => SigEvent(req)))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[Future, TestState, Error, TestState, FutureTestCtx.Ctx]) = {
    import FutureTestCtx.{*, given}
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    case class SignalErrored(req: Int, error: String) extends FutureTestCtx.Event
    val error = s"error-${UUID.randomUUID()}"
    val wio   = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => SignalErrored(req, error))
      .handleEventWithError((_, evt) => Left(evt.error))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, error, wio)
  }

  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO.Timer[FutureTestCtx.Eff, FutureTestCtx.Ctx, TestState, Nothing, TestState]) = {
    import FutureTestCtx.*
    case class Started(instant: Instant)  extends Event
    case class Released(instant: Instant) extends Event
    val duration = secs.seconds
    val wio      = WIO
      .await[TestState](duration)
      .persistStartThrough(x => Started(x.at))(_.instant)
      .persistReleaseThrough(x => Released(x.at))(_.instant)
      .done
    (duration.plus(1.milli), wio)
  }
}
