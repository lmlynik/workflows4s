package workflows4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflows4s.effect.Effect
import workflows4s.wio.*
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.{ModelUtils, WIOMeta}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

object DraftBuilder {
  private val draftSignal = SignalDef[Unit, Unit]()

  // Dummy effect for draft nodes - never actually executed
  private type DraftF[A] = Nothing
  private given draftEffect: Effect[DraftF] = new Effect[DraftF] {
    def pure[A](a: A): DraftF[A]                                       = ???
    def flatMap[A, B](fa: DraftF[A])(f: A => DraftF[B]): DraftF[B]     = ???
    def map[A, B](fa: DraftF[A])(f: A => B): DraftF[B]                 = ???
    def raiseError[A](e: Throwable): DraftF[A]                         = ???
    def handleErrorWith[A](fa: DraftF[A])(f: Throwable => DraftF[A]): DraftF[A] = ???
    def sleep(duration: scala.concurrent.duration.FiniteDuration): DraftF[Unit] = ???
    def realTimeInstant: DraftF[java.time.Instant]                     = ???
    def delay[A](a: => A): DraftF[A]                                   = ???
    def liftIO[A](io: cats.effect.IO[A]): DraftF[A]                    = ???
    def toIO[A](fa: DraftF[A]): cats.effect.IO[A]                      = ???
  }

  trait Step0[Ctx <: WorkflowContext]() {

    val draft: DraftBuilderStep1.type = DraftBuilderStep1

    object DraftBuilderStep1 {
      def signal(name: String = null, error: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx]                                  =
        WIO.HandleSignal(
          draftSignal,
          SignalHandler[DraftF, Unit, Unit, Any]((_, _) => ???),
          dummyEventHandler,
          WIO.HandleSignal.Meta(
            Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
            getEffectiveName(name, autoName),
            None,
          ),
        )
      def timer(name: String = null, duration: FiniteDuration = null)(using autoName: sourcecode.Name): WIO.Timer[Ctx, Any, Nothing, Nothing] =
        WIO.Timer(
          Option(duration) match {
            case Some(value) => WIO.Timer.DurationSource.Static(value.toJava)
            case None        => WIO.Timer.DurationSource.Dynamic(_ => ???)
          },
          dummyEventHandler,
          getEffectiveName(name, autoName).some,
          dummyEventHandler,
        )

      def step(name: String = null, error: String = null, description: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = WIO.RunIO[Ctx, DraftF, Any, Nothing, Nothing, Nothing](
        _ => ???,
        dummyEventHandler,
        WIO.RunIO.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          getEffectiveName(name, autoName).some,
          Option(description),
        ),
      )

      def choice(name: String = null)(branches: (String, WIO.Draft[Ctx])*)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = {
        val branchWios = branches.map { case (branchName, wio) =>
          WIO.Branch(_ => None, wio, Some(branchName))
        }
        WIO.Fork(branchWios.toVector, getEffectiveName(name, autoName).some, None)
      }

      def forEach(forEach: WIO.Draft[Ctx], name: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = {
        val effName = getEffectiveName(name, autoName).some
        WIO.ForEach(_ => ???, forEach, () => ???, null, (_, _) => ???, (_, _) => ???, None, null, WIOMeta.ForEach(effName))
      }

      def repeat(conditionName: String = null, releaseBranchName: String = null, restartBranchName: String = null)(
          body: WIO.Draft[Ctx],
          onRestart: WIO.Draft[Ctx] = null,
      ): WIO.Draft[Ctx] = {
        val base: WIO[WCState[Ctx], Nothing, WCState[Ctx], Ctx] = Option(onRestart) match {
          case Some(_) => WIO.build[Ctx, DraftF].repeat(body).until(_ => ???).onRestart(onRestart).named(conditionName, releaseBranchName, restartBranchName)
          case None    => WIO.build[Ctx, DraftF].repeat(body).until(_ => ???).onRestartContinue.named(conditionName, releaseBranchName, restartBranchName)
        }
        base.transformInput((_: Any) => ???).map(_ => ???)
      }

      def parallel(elements: WIO.Draft[Ctx]*): WIO.Draft[Ctx] = {
        val parallelElements = elements.map { element =>
          WIO.Parallel.Element(element.map(_ => ???), (interimState: WCState[Ctx], _: WCState[Ctx]) => interimState)
        }
        WIO
          .Parallel[Ctx, Any, Nothing, WCState[Ctx], WCState[Ctx]](
            elements = parallelElements,
            formResult = _ => ???,
            initialInterimState = (_: Any) => ???,
          )
          .transformInput((_: Any) => ???)
          .map(_ => ???)
      }

      def recovery: WIO.Draft[Ctx] = WIO.Recovery(dummyEventHandler)

      def interruptionSignal(
          signalName: String = null,
          operationName: String = null,
          error: String = null,
      )(using autoName: sourcecode.Name): WIO.Interruption[Ctx, DraftF, Nothing, Nothing] = {
        val draftSignalHandling = WIO
          .HandleSignal(
            draftSignal,
            SignalHandler[DraftF, Unit, Unit, WCState[Ctx]]((_, _) => ???),
            dummyEventHandler[WCEvent[Ctx], Unit],
            WIO.HandleSignal.Meta(
              Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
              Option(signalName).getOrElse(getEffectiveName(null, autoName)),
              Option(operationName),
            ),
          )
          .transformInput((_: WCState[Ctx]) => ???)
          .map(_ => ???)
        WIO.Interruption(draftSignalHandling, WIO.HandleInterruption.InterruptionType.Signal)
      }

      def interruptionTimeout(
          timerName: String = null,
          duration: FiniteDuration = null,
      )(using autoName: sourcecode.Name): WIO.Interruption[Ctx, DraftF, Nothing, Nothing] = {
        val draftTimer = WIO
          .Timer(
            Option(duration) match {
              case Some(value) => WIO.Timer.DurationSource.Static(value.toJava)
              case None        => WIO.Timer.DurationSource.Dynamic(_ => ???)
            },
            dummyEventHandler[WCEvent[Ctx], WIO.Timer.Started],
            Option(timerName).orElse(getEffectiveName(null, autoName).some),
            dummyEventHandler[WCEvent[Ctx], WIO.Timer.Released],
          )
          .transformInput((_: WCState[Ctx]) => ???)
          .map(_ => ???)
        WIO.Interruption(draftTimer, WIO.HandleInterruption.InterruptionType.Timer)
      }

      def retry(base: WIO.Draft[Ctx]): WIO.Draft[Ctx]      = {
        WIO
          .Retry[Ctx, DraftF, Any, Nothing, Nothing](
            base,
            (_: Throwable, _: WCState[Ctx], _: java.time.Instant) => ???,
          )
          .transformInput((_: Any) => ???)
          .map(_ => ???)
      }
      def checkpoint(base: WIO.Draft[Ctx]): WIO.Draft[Ctx] = WIO.Checkpoint[Ctx, DraftF, Any, Nothing, Nothing, Nothing](base, (_, _) => ???, dummyEventHandler)

      object syntax {
        extension (base: WIO.Draft[Ctx]) {
          def draftCheckpointed: WIO.Draft[Ctx] = checkpoint(base)
          def draftRetry: WIO.Draft[Ctx]        = retry(base)
        }
      }

    }

  }

  private def dummyEventHandler[EventBase, Evt]: EventHandler[Any, Nothing, EventBase, Evt] = EventHandler(_ => ???, _ => ???, (_, _) => ???)

  private def getEffectiveName(name: String, autoName: sourcecode.Name): String =
    Option(name).getOrElse(ModelUtils.prettifyName(autoName.value))

}
