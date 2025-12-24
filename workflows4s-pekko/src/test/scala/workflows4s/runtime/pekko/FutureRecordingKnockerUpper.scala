package workflows4s.runtime.pekko

import com.typesafe.scalalogging.StrictLogging
import _root_.workflows4s.runtime.WorkflowInstanceId
import _root_.workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant
import scala.concurrent.Future

/** Future-based knocker-upper for recording wakeups in tests.
  */
class FutureRecordingKnockerUpper extends KnockerUpper.Agent[Future] with StrictLogging {
  private var wakeups: Map[WorkflowInstanceId, Option[Instant]] = Map()

  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = wakeups.get(id).flatten

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): Future[Unit] = Future.successful {
    logger.debug(s"Registering wakeup for $id at $at")
    this.wakeups = wakeups.updatedWith(id)(_ => Some(at))
  }
}

object FutureRecordingKnockerUpper {
  def apply(): FutureRecordingKnockerUpper = new FutureRecordingKnockerUpper()
}
