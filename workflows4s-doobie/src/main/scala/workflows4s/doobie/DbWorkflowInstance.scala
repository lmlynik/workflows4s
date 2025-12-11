package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO, Sync}
import cats.{Monad, ~>}
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

private type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

object ResultEffect {
  given Effect[Result] with {
    private val M: Monad[Result]       = summon
    private val S: Sync[ConnectionIO]  = summon

    def pure[A](a: A): Result[A]                                            = M.pure(a)
    def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B]          = M.flatMap(fa)(f)
    def map[A, B](fa: Result[A])(f: A => B): Result[B]                      = M.map(fa)(f)
    def raiseError[A](e: Throwable): Result[A]                              = Kleisli(_ => S.raiseError(e))
    def handleErrorWith[A](fa: Result[A])(f: Throwable => Result[A]): Result[A] =
      Kleisli(liftIO => S.handleErrorWith(fa.run(liftIO))(e => f(e).run(liftIO)))
    def sleep(duration: FiniteDuration): Result[Unit]                       =
      // Note: sleep in database context is implemented via delay + Thread.sleep
      // This should rarely be used in practice for DB operations
      Kleisli(_ => S.delay(Thread.sleep(duration.toMillis)))
    def realTimeInstant: Result[Instant]                                    = Kleisli(_ => S.delay(Instant.now()))
    def delay[A](a: => A): Result[A]                                        = Kleisli(_ => S.delay(a))
    def liftIO[A](io: IO[A]): Result[A]                                     = Kleisli(liftIO => liftIO.liftIO(io))
  }
}

class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine[IO],
)(using Effect[Result])
    extends WorkflowInstanceBase[Result, Ctx]
    with StrictLogging {

  private val connIOToResult: ConnectionIO ~> Result = new FunctionK {
    override def apply[A](fa: ConnectionIO[A]): Result[A] = Kleisli(_ => fa)
  }

  override protected def getWorkflow: Result[ActiveWorkflow[Ctx]] = {
    Kleisli(connLiftIO =>
      storage
        .getEvents(id)
        .evalFold(baseWorkflow)((state, event) => connLiftIO.liftIO(IO.pure(engine.processEvent(state, event))))
        .compile
        .lastOrError,
    )
  }

  override protected def persistEvent(event: WCEvent[Ctx]): Result[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Result[Unit] = summon[Effect[Result]].unit

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Result[T]): Result[T] =
    storage.lockWorkflow(id).mapK(connIOToResult).use(_ => getWorkflow.flatMap(update))
}
