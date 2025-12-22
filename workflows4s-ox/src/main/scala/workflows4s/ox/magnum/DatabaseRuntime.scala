package workflows4s.ox.magnum

import com.augustnagro.magnum.Transactor
import workflows4s.ox.Direct
import workflows4s.ox.OxEffect.given
import workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WIO, WorkflowContext}

/** Database-backed workflow runtime using PostgreSQL and Magnum.
  *
  * Provides persistent workflow execution with event sourcing. All workflow state is reconstructed from events stored in the database, enabling:
  *   - Crash recovery (workflows resume from last persisted state)
  *   - Audit trail (complete history of all state changes)
  *   - Time travel debugging (replay events to any point)
  *   - Distributed execution (multiple processes can run workflows)
  *
  * Example usage:
  * {{{
  * import workflows4s.ox.{Direct, DirectWorkflowContext}
  * import workflows4s.ox.magnum.*
  *
  * object MyWorkflow extends DirectWorkflowContext {
  *   case class State(count: Int)
  *   enum Event { case Incremented }
  *   type State = State
  *   type Event = Event
  * }
  *
  * // Define workflow
  * val workflow = WIO
  *   .pure[Direct, MyWorkflow.type]
  *   .map(_ => MyWorkflow.Event.Incremented)
  *   .handleEvent((state, event) => state.copy(count = state.count + 1))
  *
  * // Setup database
  * val transactor = MagnumTransactor.fromJdbcUrl(
  *   "jdbc:postgresql://localhost:5432/workflows",
  *   "user",
  *   "password"
  * )
  *
  * given ByteCodec[MyWorkflow.Event] = MyEventCodec()
  *
  * // Create runtime
  * val runtime = DatabaseRuntime.create(
  *   workflow = workflow,
  *   initialState = MyWorkflow.State(0),
  *   transactor = transactor,
  *   engine = WorkflowInstanceEngine.basic[Direct](),
  *   eventCodec = summon[ByteCodec[MyWorkflow.Event]],
  *   templateId = "my-workflow"
  * ).run
  *
  * // Create and run instance
  * val instance = runtime.createInstance("instance-1").run
  * instance.wakeup().run
  * }}}
  *
  * @param workflow
  *   Workflow definition
  * @param initialState
  *   Initial state for new workflow instances
  * @param engine
  *   Workflow instance engine (handles execution logic)
  * @param storage
  *   Event storage backend
  * @param templateId
  *   Unique identifier for this workflow type
  */
class DatabaseRuntime[Ctx <: WorkflowContext](
    override val workflow: WIO.Initial[Direct, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[Direct],
    storage: WorkflowStorage[WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[Direct, Ctx] {

  /** Create a new workflow instance.
    *
    * The instance is backed by the database - state is reconstructed from events on each operation. Multiple processes can interact with the same
    * instance safely via database locking.
    *
    * @param id
    *   Unique identifier for this instance (scoped to templateId)
    * @return
    *   Direct effect containing the workflow instance
    */
  override def createInstance(id: String): Direct[WorkflowInstance[Direct, WCState[Ctx]]] = Direct {
    val instanceId = WorkflowInstanceId(templateId, id)
    new DbWorkflowInstance(
      instanceId,
      ActiveWorkflow(instanceId, workflow, initialState),
      storage,
      engine,
    )
  }
}

object DatabaseRuntime {

  /** Create a database-backed workflow runtime with PostgreSQL storage.
    *
    * This is the recommended way to create a DatabaseRuntime. It automatically sets up PostgresWorkflowStorage with the provided transactor and
    * codec.
    *
    * @param workflow
    *   Workflow definition
    * @param initialState
    *   Initial state for new workflow instances
    * @param transactor
    *   Magnum Transactor for database access
    * @param engine
    *   Workflow instance engine (use WorkflowInstanceEngine.default for production)
    * @param eventCodec
    *   Codec for serializing workflow events
    * @param templateId
    *   Unique identifier for this workflow type
    * @param tableName
    *   Database table name (default: "workflow_journal")
    * @return
    *   Direct effect containing the runtime
    */
  def create[Ctx <: WorkflowContext](
      workflow: WIO.Initial[Direct, Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor,
      engine: WorkflowInstanceEngine[Direct],
      eventCodec: ByteCodec[WCEvent[Ctx]],
      templateId: String,
      tableName: String = "workflow_journal",
  ): Direct[DatabaseRuntime[Ctx]] = Direct {
    val storage = new PostgresWorkflowStorage[WCEvent[Ctx]](transactor, tableName)(using eventCodec)

    new DatabaseRuntime[Ctx](
      workflow,
      initialState,
      engine,
      storage,
      templateId,
    )
  }

  /** Create a database-backed workflow runtime with custom storage.
    *
    * Use this when you need to provide a custom WorkflowStorage implementation (e.g., for testing or alternative storage backends).
    *
    * @param workflow
    *   Workflow definition
    * @param initialState
    *   Initial state for new workflow instances
    * @param storage
    *   Custom workflow storage implementation
    * @param engine
    *   Workflow instance engine
    * @param templateId
    *   Unique identifier for this workflow type
    * @return
    *   Direct effect containing the runtime
    */
  def createWithStorage[Ctx <: WorkflowContext](
      workflow: WIO.Initial[Direct, Ctx],
      initialState: WCState[Ctx],
      storage: WorkflowStorage[WCEvent[Ctx]],
      engine: WorkflowInstanceEngine[Direct],
      templateId: String,
  ): Direct[DatabaseRuntime[Ctx]] = Direct {
    new DatabaseRuntime[Ctx](
      workflow,
      initialState,
      engine,
      storage,
      templateId,
    )
  }
}
