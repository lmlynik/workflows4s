package workflows4s.testing

import workflows4s.runtime.instanceengine.Effect
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.*

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/** Effect-polymorphic test utilities for workflow testing.
  *
  * Provides common test helpers for creating workflow operations across different effect types. Each method creates test-specific WIO operations
  * using the context's pre-defined event types.
  *
  * @tparam F
  *   the effect type (IO, Task, Direct, etc.)
  * @tparam C
  *   the specific context type (for proper path-dependent type inference)
  * @param ctx
  *   the test workflow context providing event types and WIO definitions
  */
class EffectTestUtils[F[_], C <: TestWorkflowContext[F]](val ctx: C)(using E: Effect[F]) {

  type Error = String

  /** Creates a pure step that adds a step ID to the state */
  def pure: (StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    val stepId = StepId.random("pure")
    val wio    = ctx.WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done
    (stepId, wio)
  }

  /** Creates a pure error step */
  def error: (Error, ctx.WIO[Any, String, Nothing]) = {
    val err = s"error-${UUID.randomUUID()}"
    val wio = ctx.WIO.pure.error(err).done
    (err, wio)
  }

  /** Creates an IO step using unit effect */
  def runIO: (StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    runIOCustom(E.unit)
  }

  /** Creates an IO step that returns an error */
  def errorIO: (Error, ctx.WIO[Any, String, Nothing]) = {
    val err = s"error-${UUID.randomUUID()}"
    val wio = ctx.WIO
      .runIO[Any](_ => E.pure(ctx.RunIOErrored(err)))
      .handleEventWithError((_, evt: ctx.RunIOErrored) => Left(evt.error))
      .done
    (err, wio)
  }

  /** Creates an IO step with custom logic */
  def runIOCustom(logic: F[Unit]): (StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    val stepId = StepId.random
    val wio    = ctx.WIO
      .runIO[TestState](_ => logic.as(ctx.RunIODone(stepId)))
      .handleEvent((st, evt: ctx.RunIODone) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  /** Creates an error handler that records errors in state */
  def errorHandler: ctx.WIO[(TestState, Error), Nothing, TestState] = {
    ctx.WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  /** Creates a signal handler step using unit effect */
  def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[F, TestState, Nothing, TestState, ctx.Ctx]) =
    signalCustom(E.unit)

  /** Creates a signal handler step with custom logic */
  def signalCustom(logic: F[Unit]): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[F, TestState, Nothing, TestState, ctx.Ctx]) = {
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    val stepId    = StepId.random("signal")
    val wio       = ctx.WIO
      .handleSignal(signalDef)
      .using[TestState]
      .withSideEffects((_, req) => logic.as(ctx.SignalReceived(req)))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt: ctx.SignalReceived) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  /** Creates a signal handler that results in an error */
  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[F, TestState, Error, TestState, ctx.Ctx]) = {
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    val err       = s"error-${UUID.randomUUID()}"
    val wio       = ctx.WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => ctx.SignalErrored(req, err))
      .handleEventWithError((_, evt: ctx.SignalErrored) => Left(evt.error))
      .produceResponse((_, evt: ctx.SignalErrored) => evt.req)
      .done
    (signalDef, err, wio)
  }

  /** Creates a timer step */
  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO.Timer[ctx.Eff, ctx.Ctx, TestState, Nothing, TestState]) = {
    val duration = secs.seconds
    val wio      = ctx.WIO
      .await[TestState](duration)
      .persistStartThrough(x => ctx.TimerStarted(x.at))(_.instant)
      .persistReleaseThrough(x => ctx.TimerReleased(x.at))(_.instant)
      .done
    (duration.plus(1.milli), wio)
  }
}
