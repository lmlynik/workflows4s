package workflows4s.runtime

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

import java.time.Instant
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining.scalaUtilChainingOps

/** Synchronous in-memory workflow instance implementation using Id as the effect type.
  *
  * Note: This provides a synchronous Effect[Id] that runs IO effects via unsafeRunSync(). This is useful for testing
  * but should not be used in production where asynchronous execution is important.
  *
  * TODO: Move to workflows4s-cats-effect module
  */
class InMemorySyncWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[Ctx],
    protected val engine: WorkflowInstanceEngine[IO],
)(implicit ioRuntime: IORuntime)
    extends WorkflowInstanceBase[Id, Ctx](using InMemorySyncWorkflowInstance.syncEffect(using ioRuntime))
    with StrictLogging {

  private var wf: ActiveWorkflow[Ctx]              = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  def getEvents: Seq[WCEvent[Ctx]]                 = events.toList

  def recover(events: Seq[WCEvent[Ctx]]): Unit = super.recover(wf, events).pipe(updateState(_, events))

  override protected def getWorkflow: workflows4s.wio.ActiveWorkflow[Ctx] = wf

  private val lock = new Object

  override protected def persistEvent(event: WCEvent[Ctx]): Id[Unit] = events += event

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Id[Unit] = wf = newState

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Id[T]): Id[T] = lock.synchronized { update(wf) }

  private def updateState(workflow: workflows4s.wio.ActiveWorkflow[Ctx], _events: Seq[WCEvent[Ctx]]): Unit = {
    events ++= _events
    wf = workflow
  }

}

object InMemorySyncWorkflowInstance {

  /** Creates a synchronous Effect[Id] that runs IO effects via unsafeRunSync(). */
  def syncEffect(implicit ioRuntime: IORuntime): Effect[Id] = new Effect[Id] {
    def pure[A](a: A): Id[A]                                         = a
    def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B]               = f(fa)
    def map[A, B](fa: Id[A])(f: A => B): Id[B]                       = f(fa)
    def raiseError[A](e: Throwable): Id[A]                           = throw e
    def handleErrorWith[A](fa: Id[A])(f: Throwable => Id[A]): Id[A]  =
      try fa
      catch { case e: Throwable => f(e) }
    def sleep(duration: FiniteDuration): Id[Unit]                    = Thread.sleep(duration.toMillis)
    def realTimeInstant: Id[Instant]                                 = Instant.now()
    def delay[A](a: => A): Id[A]                                     = a
    def liftIO[A](io: IO[A]): Id[A]                                  = io.unsafeRunSync()
  }
}
