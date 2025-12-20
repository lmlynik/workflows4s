package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

/** Shared state for recording wakeups that can be accessed by knocker uppers of different effect types. This enables tests that need both IO and Id
  * views of the same data.
  */
class RecordingKnockerUpperState extends StrictLogging {
  @volatile private var wakeups: Map[WorkflowInstanceId, Option[Instant]] = Map()

  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = wakeups.get(id).flatten

  def recordWakeup(id: WorkflowInstanceId, at: Option[Instant]): Unit = {
    logger.debug(s"Registering wakeup for $id at $at")
    this.wakeups = wakeups.updatedWith(id)(_ => Some(at))
  }
}

/** Effect-polymorphic KnockerUpper that records wakeup times for testing. Can be used with any effect type that has an Effect instance.
  *
  * Multiple instances can share the same underlying state by passing the same `RecordingKnockerUpperState`.
  */
class RecordingKnockerUpper[F[_]](val state: RecordingKnockerUpperState)(using E: Effect[F]) extends KnockerUpper.Agent[F] {

  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = state.lastRegisteredWakeup(id)

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = E.delay {
    state.recordWakeup(id, at)
  }

  /** Create a view of this knocker upper for a different effect type, sharing the same state. Useful for tests that need both IO and Id views.
    */
  def as[G[_]](using E2: Effect[G]): RecordingKnockerUpper[G] = new RecordingKnockerUpper[G](state)
}

object RecordingKnockerUpper {

  /** Create a new RecordingKnockerUpper with its own state */
  def apply[F[_]](using E: Effect[F]): RecordingKnockerUpper[F] =
    new RecordingKnockerUpper[F](new RecordingKnockerUpperState)

  /** Create a new RecordingKnockerUpper with shared state */
  def withState[F[_]](state: RecordingKnockerUpperState)(using E: Effect[F]): RecordingKnockerUpper[F] =
    new RecordingKnockerUpper[F](state)
}
