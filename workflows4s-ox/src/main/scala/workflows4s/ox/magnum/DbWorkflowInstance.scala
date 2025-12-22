package workflows4s.ox.magnum

import workflows4s.ox.Direct
import workflows4s.ox.OxEffect.given
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.{ActiveWorkflow, WCEvent, WorkflowContext}

/** Database-backed workflow instance using event sourcing.
  *
  * This implementation stores no state in memory - all state is reconstructed from events stored in the database. This provides:
  *   - **Crash recovery**: Workflows resume from last persisted state
  *   - **Audit trail**: Complete history of all state changes
  *   - **Time travel**: Can replay events to any point in time
  *   - **Distributed execution**: Multiple processes can safely interact with the same workflow
  *
  * ## Architecture
  *
  * On each operation:
  *   1. Acquire database lock for the workflow instance
  *   2. Load all events from database
  *   3. Replay events through engine to reconstruct current state
  *   4. Process operation (signal/wakeup)
  *   5. Persist any resulting events
  *   6. Release lock
  *
  * This is more expensive than in-memory workflows but provides strong durability guarantees.
  *
  * ## Locking
  *
  * PostgreSQL advisory locks ensure only one process can modify a workflow instance at a time. Locks are automatically released when the transaction
  * ends or connection closes.
  *
  * @param id
  *   Workflow instance identifier
  * @param baseWorkflow
  *   Base workflow definition with initial state (used as starting point for event replay)
  * @param storage
  *   Event storage backend
  * @param engine
  *   Workflow instance engine
  */
class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Direct, Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine[Direct],
) extends WorkflowInstanceBase[Direct, Ctx] {

  /** Reconstruct workflow state from events.
    *
    * Loads all events from storage and replays them through the engine to compute the current state. This is called on every operation to ensure we
    * have the latest state.
    *
    * @return
    *   Current workflow state
    */
  override protected def getWorkflow: Direct[ActiveWorkflow[Direct, Ctx]] = Direct {
    val events = storage.getEvents(id).run

    // Fold events into state, starting from base workflow
    events.foldLeft(baseWorkflow) { (workflow, event) =>
      engine.processEvent(workflow, event).run
    }
  }

  /** Persist a new event to storage.
    *
    * Events are appended to the event journal and can be replayed later to reconstruct state.
    *
    * @param event
    *   Event to persist
    */
  override protected def persistEvent(event: WCEvent[Ctx]): Direct[Unit] = {
    storage.saveEvent(id, event)
  }

  /** Update in-memory state (no-op for database-backed instances).
    *
    * Database-backed instances are stateless - state is always reconstructed from events. This method is called by WorkflowInstanceBase after
    * processing events, but we don't need to do anything since there's no in-memory state to update.
    *
    * @param newState
    *   New workflow state (ignored)
    */
  override protected def updateState(newState: ActiveWorkflow[Direct, Ctx]): Direct[Unit] = {
    Direct(()) // No in-memory state to update
  }

  /** Execute an operation with exclusive lock on the workflow.
    *
    * Acquires a database lock, loads current state, executes the operation, and releases the lock. This ensures only one process can modify the
    * workflow at a time.
    *
    * @param update
    *   Operation to execute with current workflow state
    * @return
    *   Result of the operation
    */
  override protected def lockState[T](update: ActiveWorkflow[Direct, Ctx] => Direct[T]): Direct[T] = {
    storage.withLock(id) {
      Direct {
        val workflow = getWorkflow.run
        update(workflow).run
      }
    }
  }
}
