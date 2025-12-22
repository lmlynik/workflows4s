package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import workflows4s.ox.Direct
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.utils.StringUtils

/** PostgreSQL-based workflow event storage using Magnum.
  *
  * Stores workflow events in a journal table with support for:
  *   - Event persistence in order
  *   - Event replay for state reconstruction
  *   - PostgreSQL advisory locks for concurrent safety
  *
  * Example usage:
  * {{{
  * val transactor = MagnumTransactor.fromJdbcUrl(
  *   "jdbc:postgresql://localhost:5432/workflows",
  *   "user",
  *   "password"
  * )
  *
  * given ByteCodec[MyEvent] = MyEventCodec()
  * val storage = PostgresWorkflowStorage[MyEvent](transactor)
  *
  * // Save and replay events
  * val id = WorkflowInstanceId("myWorkflow", "instance-1")
  * storage.saveEvent(id, MyEvent.Created("data")).run
  * val events = storage.getEvents(id).run
  * }}}
  *
  * @param transactor
  *   Magnum Transactor for database access
  * @param tableName
  *   Name of the journal table (default: "workflow_journal")
  * @param eventCodec
  *   Codec for serializing/deserializing events
  */
class PostgresWorkflowStorage[Event](
    transactor: Transactor,
    tableName: String = "workflow_journal",
)(using eventCodec: ByteCodec[Event])
    extends WorkflowStorage[Event] {

  /** Retrieve all events for a workflow instance in chronological order.
    *
    * Events are streamed as a LazyList, allowing efficient processing of large event histories without loading everything into memory at once.
    *
    * @param id
    *   Workflow instance identifier
    * @return
    *   LazyList of events in the order they were persisted
    */
  override def getEvents(id: WorkflowInstanceId): Direct[LazyList[Event]] = Direct {
    connect(transactor) {
      sql"""
        SELECT event_data
        FROM ${SqlLiteral(tableName)}
        WHERE instance_id = ${id.instanceId}
          AND template_id = ${id.templateId}
        ORDER BY event_id
      """
        .query[Array[Byte]]
        .run()
        .to(LazyList)
        .map { bytes =>
          eventCodec.read(IArray.unsafeFromArray(bytes)).get
        }
    }
  }

  /** Persist a new event for a workflow instance.
    *
    * The event is appended to the journal with an auto-incrementing event_id to maintain ordering.
    *
    * @param id
    *   Workflow instance identifier
    * @param event
    *   Event to persist
    */
  override def saveEvent(id: WorkflowInstanceId, event: Event): Direct[Unit] = Direct {
    val bytes = IArray.genericWrapArray(eventCodec.write(event)).toArray

    connect(transactor) {
      sql"""
        INSERT INTO ${SqlLiteral(tableName)} (instance_id, template_id, event_data)
        VALUES (${id.instanceId}, ${id.templateId}, $bytes)
      """.update
        .run(): @scala.annotation.nowarn
      ()
    }
  }

  /** Execute an operation with an exclusive lock on the workflow instance.
    *
    * Uses PostgreSQL's advisory transaction-level locks (`pg_try_advisory_xact_lock`). The lock is automatically released when the transaction ends,
    * even if an exception occurs.
    *
    * The lock key is computed by hashing the workflow ID to a Long using SHA-256.
    *
    * @param id
    *   Workflow instance identifier
    * @param f
    *   Operation to execute while holding the lock
    * @return
    *   Result of the operation
    * @throws Exception
    *   if the lock cannot be acquired (workflow is locked by another process)
    */
  override def withLock[A](id: WorkflowInstanceId)(f: => Direct[A]): Direct[A] = Direct {
    val lockKey = computeLockKey(id)

    // Acquire lock
    val lockAcquired = connect(transactor) {
      sql"SELECT pg_try_advisory_xact_lock($lockKey)"
        .query[Boolean]
        .run()
        .head
    }

    if !lockAcquired then {
      throw new Exception(s"Couldn't acquire lock $lockKey for $id")
    }

    // Execute operation while lock is held
    // Note: Lock is transaction-level and will be released automatically
    f.run
  }

  /** Compute a lock key from workflow ID.
    *
    * Uses SHA-256 hash of "templateId-instanceId" and converts the first 8 bytes to a Long. This ensures consistent lock keys for the same workflow
    * across all processes.
    *
    * @param id
    *   Workflow instance identifier
    * @return
    *   64-bit lock key for PostgreSQL advisory locks
    */
  protected def computeLockKey(id: WorkflowInstanceId): Long = {
    StringUtils.stringToLong(s"${id.templateId}-${id.instanceId}")
  }
}

object PostgresWorkflowStorage {

  /** Create a PostgresWorkflowStorage with default table name.
    *
    * @param transactor
    *   Magnum Transactor for database access
    * @param eventCodec
    *   Codec for event serialization
    * @return
    *   PostgresWorkflowStorage instance
    */
  def apply[Event](
      transactor: Transactor,
  )(using eventCodec: ByteCodec[Event]): PostgresWorkflowStorage[Event] = {
    new PostgresWorkflowStorage[Event](transactor)
  }

  /** Create a PostgresWorkflowStorage with custom table name.
    *
    * @param transactor
    *   Magnum Transactor for database access
    * @param tableName
    *   Custom table name for the journal
    * @param eventCodec
    *   Codec for event serialization
    * @return
    *   PostgresWorkflowStorage instance
    */
  def apply[Event](
      transactor: Transactor,
      tableName: String,
  )(using eventCodec: ByteCodec[Event]): PostgresWorkflowStorage[Event] = {
    new PostgresWorkflowStorage[Event](transactor, tableName)
  }
}
