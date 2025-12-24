package workflows4s.ox.magnum

import workflows4s.ox.Direct
import workflows4s.runtime.WorkflowInstanceId

/** Storage abstraction for workflow events using Ox's Direct effect type.
  *
  * Provides event persistence and retrieval with locking support for concurrent access. Events are stored in order and can be replayed to reconstruct
  * workflow state.
  *
  * Example usage:
  * {{{
  * val storage = PostgresWorkflowStorage(transactor, eventCodec)
  * val id = WorkflowInstanceId("myWorkflow", "instance-123")
  *
  * // Store event
  * storage.saveEvent(id, MyEvent.Created("data")).run
  *
  * // Replay events to reconstruct state
  * val events = storage.getEvents(id).run
  * val currentState = events.foldLeft(initialState)((state, event) =>
  *   applyEvent(state, event)
  * )
  *
  * // Safe concurrent access with locking
  * storage.withLock(id) {
  *   val state = reconstructState(storage.getEvents(id).run)
  *   val newState = processOperation(state)
  *   storage.saveEvent(id, stateToEvent(newState)).run
  *   newState
  * }.run
  * }}}
  */
trait WorkflowStorage[Event] {

  /** Retrieve all events for a workflow instance in order.
    *
    * Events are returned in the order they were persisted (ascending by event_id). The returned LazyList allows for efficient streaming of large
    * event histories without loading everything into memory.
    *
    * @param id
    *   Workflow instance identifier
    * @return
    *   Direct effect containing LazyList of events in order
    */
  def getEvents(id: WorkflowInstanceId): Direct[LazyList[Event]]

  /** Persist a new event for a workflow instance.
    *
    * Events are appended to the event log and can be replayed later to reconstruct state. This operation should be atomic and ensure events are
    * persisted in the order they are saved.
    *
    * @param id
    *   Workflow instance identifier
    * @param event
    *   Event to persist
    * @return
    *   Direct effect completing when event is persisted
    */
  def saveEvent(id: WorkflowInstanceId, event: Event): Direct[Unit]

  /** Execute an operation with an exclusive lock on the workflow instance.
    *
    * Acquires a lock before executing the operation and releases it afterwards (even on failure). This ensures only one thread/process can modify a
    * workflow instance at a time.
    *
    * For PostgreSQL, this uses advisory locks which are automatically released when the transaction ends or connection closes.
    *
    * @param id
    *   Workflow instance identifier
    * @param f
    *   Operation to execute while holding the lock
    * @return
    *   Direct effect with the operation's result
    */
  def withLock[A](id: WorkflowInstanceId)(f: => Direct[A]): Direct[A]
}
