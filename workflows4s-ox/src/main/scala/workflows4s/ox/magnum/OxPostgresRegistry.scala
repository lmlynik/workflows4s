package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import workflows4s.ox.Direct
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.{ExecutionStatus, Tagger}
import workflows4s.wio.ActiveWorkflow
import java.sql.Timestamp
import java.time.{Clock, Instant}
import scala.concurrent.duration.FiniteDuration

/** DbCodec for java.time.Instant, storing as TIMESTAMP in PostgreSQL */
given instantCodec: DbCodec[Instant] = DbCodec[Timestamp].biMap(
  ts => if ts == null then null else ts.toInstant,
  Timestamp.from,
)

/** Magnum entity and repository definitions for workflow_registry table.
  *
  * Encapsulated in an object to avoid naming conflicts with WorkflowRegistry trait.
  */
object WorkflowRegistryTable {

  /** Entity class representing a row in the workflow_registry table.
    *
    * Uses Magnum's @Table annotation for compile-time repository generation. The tags field is stored as a JSON string (Option[String]) rather than
    * Map[String, String] to simplify DbCodec handling - serialization/deserialization happens at the service layer.
    *
    * Note: The class is named `WorkflowRegistry` so that Magnum's CamelToSnakeCase mapper generates the correct table name `workflow_registry`.
    *
    * @param templateId
    *   Workflow template identifier
    * @param instanceId
    *   Workflow instance identifier
    * @param status
    *   Execution status (Running, Awaiting, Finished)
    * @param createdAt
    *   Timestamp when the workflow was created
    * @param updatedAt
    *   Timestamp when the workflow was last updated
    * @param wakeupAt
    *   Optional timestamp for scheduled wakeup
    * @param tags
    *   Optional JSON string containing workflow tags
    */
  @Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
  case class WorkflowRegistry(
      @Id templateId: String,
      @Id instanceId: String,
      status: String,
      createdAt: Instant,
      updatedAt: Instant,
      wakeupAt: Option[Instant],
      tags: Option[String],
  ) derives DbCodec

  /** Entity-creator class for inserting new workflow registry rows.
    *
    * Has the same fields as WorkflowRegistry since there are no auto-generated columns. This is an 'effective' subclass of WorkflowRegistry as
    * required by Magnum's Repo pattern.
    *
    * @param templateId
    *   Workflow template identifier
    * @param instanceId
    *   Workflow instance identifier
    * @param status
    *   Execution status (Running, Awaiting, Finished)
    * @param createdAt
    *   Timestamp when the workflow was created
    * @param updatedAt
    *   Timestamp when the workflow was last updated
    * @param wakeupAt
    *   Optional timestamp for scheduled wakeup
    * @param tags
    *   Optional JSON string containing workflow tags
    */
  case class Creator(
      templateId: String,
      instanceId: String,
      status: String,
      createdAt: Instant,
      updatedAt: Instant,
      wakeupAt: Option[Instant],
      tags: Option[String],
  ) derives DbCodec

  /** Repository for workflow registry operations.
    *
    * Extends Magnum's Repo to auto-generate common SQL methods at compile-time:
    *   - findById(id: (String, String)): Option[WorkflowRegistry]
    *   - findAll: Vector[WorkflowRegistry]
    *   - insert(creator: Creator): Unit
    *   - update(entity: WorkflowRegistry): Unit
    *   - delete(entity: WorkflowRegistry): Unit
    *   - deleteById(id: (String, String)): Unit
    *
    * The composite primary key is (templateId, instanceId).
    */
  object Repo extends com.augustnagro.magnum.Repo[Creator, WorkflowRegistry, (String, String)]
}

/** PostgreSQL-backed workflow registry using Magnum.
  *
  * Tracks workflow instances with their execution status, timestamps, and optional tags. Uses Magnum's synchronous JDBC approach which aligns
  * perfectly with Ox's Direct effect type.
  *
  * Unlike simpler registries that only track running workflows, this enhanced registry preserves all workflow states (Running/Awaiting/Finished) for
  * observability and debugging purposes.
  *
  * Example usage:
  * {{{
  * // Create registry
  * val transactor = MagnumTransactor.fromJdbcUrl(
  *   "jdbc:postgresql://localhost:5432/workflows",
  *   "postgres",
  *   "password"
  * )
  * val registry = OxPostgresRegistry(transactor).run
  *
  * // Use with workflow engine
  * val engine = WorkflowInstanceEngine
  *   .builder[Direct]
  *   .withJavaTime(Clock.systemUTC())
  *   .withWakeUps(knockerUpper)
  *   .withRegistering(registry)  // Automatically tracks all state changes
  *   .withGreedyEvaluation
  *   .withLogging
  *   .get
  * }}}
  *
  * @param transactor
  *   Magnum Transactor wrapping a DataSource (e.g., HikariCP)
  * @param tableName
  *   Database table name (default: "workflow_registry")
  * @param clock
  *   Clock for timestamps (default: system UTC, injectable for testing)
  * @param tagger
  *   Optional tagger for extracting custom metadata from workflow state
  */
class OxPostgresRegistry(
    transactor: Transactor,
    tableName: String = "workflow_registry",
    clock: Clock = Clock.systemUTC(),
    tagger: Option[Tagger[Any]] = None,
) extends WorkflowRegistry.Agent[Direct] {

  /** Serialize tags to JSON string for database storage.
    *
    * @param tags
    *   Optional map of tag key-value pairs
    * @return
    *   Optional JSON string (None if tags are empty or None)
    */
  private def serializeTags(tags: Option[Map[String, String]]): Option[String] =
    tags.filter(_.nonEmpty).map(MagnumJsonCodec.writeJsonString)

  /** Upsert workflow instance with current status.
    *
    * Behavior differs based on execution status:
    *   - **Running**: INSERT or UPDATE status to 'Running', update timestamp
    *   - **Awaiting**: UPDATE status to 'Awaiting', preserve record
    *   - **Finished**: UPDATE status to 'Finished', preserve record
    *
    * Also stores:
    *   - wakeup_at: Extracted from ActiveWorkflow.wakeupAt
    *   - tags: Extracted via Tagger interface (if provided)
    *
    * Uses Magnum's `connect` context and `sql` interpolator for clean, type-safe database operations. Tags are cast to `::jsonb` in SQL and COALESCE
    * is used to preserve existing tags when new tags are NULL.
    *
    * @param inst
    *   Active workflow instance
    * @param executionStatus
    *   Current execution status (Running/Awaiting/Finished)
    * @return
    *   Direct[Unit] effect
    */
  override def upsertInstance(
      inst: ActiveWorkflow[Direct, ?],
      executionStatus: ExecutionStatus,
  ): Direct[Unit] = Direct {
    val id  = inst.id
    val now = Instant.now(clock)

    // Extract tags if tagger is provided
    val tags: Option[Map[String, String]] = tagger.flatMap { t =>
      try {
        val tagMap = t.getTags(id, inst.liveState.asInstanceOf)
        if tagMap.isEmpty then None else Some(tagMap)
      } catch {
        case _: Exception => None // Ignore tag extraction errors
      }
    }

    // Extract wakeup time from workflow
    val wakeupAt: Option[Instant] = inst.wakeupAt

    // Convert status to string
    val statusStr = executionStatus match {
      case ExecutionStatus.Running  => "Running"
      case ExecutionStatus.Awaiting => "Awaiting"
      case ExecutionStatus.Finished => "Finished"
    }

    // Serialize tags to JSON string
    val tagsJson: Option[String] = serializeTags(tags)

    // Use Magnum's transact context for upsert (auto-commits on success)
    // For dynamic table names, we use SqlLiteral to splice the table name directly
    // into the SQL without parameter binding. This is safe because tableName is
    // a constructor parameter, not user input.
    transact(transactor) {
      val tableNameLit = SqlLiteral(tableName)
      sql"""
        INSERT INTO $tableNameLit
          (instance_id, template_id, status, created_at, updated_at, wakeup_at, tags)
        VALUES (${id.instanceId}, ${id.templateId}, $statusStr, $now, $now, $wakeupAt, $tagsJson::jsonb)
        ON CONFLICT (template_id, instance_id) DO UPDATE SET
          status = EXCLUDED.status,
          updated_at = EXCLUDED.updated_at,
          wakeup_at = EXCLUDED.wakeup_at,
          tags = COALESCE(EXCLUDED.tags, $tableNameLit.tags)
      """.update.run(): Unit
    }
  }

  /** Get workflows that haven't been updated recently (potential stuck/crashed workflows).
    *
    * Filters for Running status only - Awaiting workflows are expected to be inactive.
    *
    * @param notUpdatedFor
    *   Duration threshold - workflows not updated for this long are considered stale
    * @return
    *   List of stale workflow instance IDs
    */
  def getStaleWorkflows(notUpdatedFor: FiniteDuration): Direct[List[WorkflowInstanceId]] = Direct {
    val cutoffTime   = Instant.now(clock).minusMillis(notUpdatedFor.toMillis)
    val tableNameLit = SqlLiteral(tableName)

    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM $tableNameLit
        WHERE status = 'Running'
          AND updated_at <= $cutoffTime
        ORDER BY updated_at ASC
      """
        .query[(String, String)]
        .run()
        .map { case (templateId, instanceId) =>
          WorkflowInstanceId(templateId, instanceId)
        }
        .toList
    }
  }

  /** Get all workflows with specific status.
    *
    * @param status
    *   Execution status to filter by
    * @return
    *   List of workflow instance IDs with the given status
    */
  def getWorkflowsByStatus(status: ExecutionStatus): Direct[List[WorkflowInstanceId]] = Direct {
    val statusStr    = status match {
      case ExecutionStatus.Running  => "Running"
      case ExecutionStatus.Awaiting => "Awaiting"
      case ExecutionStatus.Finished => "Finished"
    }
    val tableNameLit = SqlLiteral(tableName)

    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM $tableNameLit
        WHERE status = $statusStr
        ORDER BY updated_at DESC
      """
        .query[(String, String)]
        .run()
        .map { case (templateId, instanceId) =>
          WorkflowInstanceId(templateId, instanceId)
        }
        .toList
    }
  }

  /** Get all awaiting workflows (waiting for signals or timers).
    *
    * @return
    *   List of workflow instance IDs in Awaiting status
    */
  def getAwaitingWorkflows: Direct[List[WorkflowInstanceId]] =
    getWorkflowsByStatus(ExecutionStatus.Awaiting)

  /** Get workflows with pending wakeups (timers scheduled to fire).
    *
    * @param asOf
    *   Check for wakeups scheduled at or before this time (default: now)
    * @return
    *   List of workflow instance IDs with wakeups due
    */
  def getWorkflowsWithPendingWakeups(asOf: Instant = Instant.now(clock)): Direct[List[WorkflowInstanceId]] = Direct {
    val tableNameLit = SqlLiteral(tableName)

    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM $tableNameLit
        WHERE wakeup_at IS NOT NULL
          AND wakeup_at <= $asOf
          AND status IN ('Running', 'Awaiting')
        ORDER BY wakeup_at ASC
      """
        .query[(String, String)]
        .run()
        .map { case (templateId, instanceId) =>
          WorkflowInstanceId(templateId, instanceId)
        }
        .toList
    }
  }

  /** Get workflows matching specific tags.
    *
    * Uses PostgreSQL JSONB containment operator (@>) to efficiently query tags.
    *
    * @param tagFilters
    *   Map of tag key-value pairs to match (AND logic)
    * @return
    *   List of workflow instance IDs matching all specified tags
    */
  def getWorkflowsByTags(tagFilters: Map[String, String]): Direct[List[WorkflowInstanceId]] = Direct {
    if tagFilters.isEmpty then {
      List.empty
    } else {
      val tableNameLit = SqlLiteral(tableName)
      val tagsJson     = MagnumJsonCodec.writeJsonString(tagFilters)

      connect(transactor) {
        sql"""
          SELECT template_id, instance_id
          FROM $tableNameLit
          WHERE tags @> $tagsJson::jsonb
          ORDER BY updated_at DESC
        """
          .query[(String, String)]
          .run()
          .map { case (templateId, instanceId) =>
            WorkflowInstanceId(templateId, instanceId)
          }
          .toList
      }
    }
  }

  /** Get workflow registry statistics for monitoring.
    *
    * Provides counts by status and timestamp information for observability.
    *
    * @return
    *   RegistryStats with workflow counts and timestamps
    */
  def getStats: Direct[RegistryStats] = Direct {
    val tableNameLit = SqlLiteral(tableName)

    connect(transactor) {
      sql"""
        SELECT
          COUNT(*) as total,
          COUNT(*) FILTER (WHERE status = 'Running') as running,
          COUNT(*) FILTER (WHERE status = 'Awaiting') as awaiting,
          COUNT(*) FILTER (WHERE status = 'Finished') as finished,
          MIN(updated_at) FILTER (WHERE status = 'Running') as oldest_running,
          MAX(updated_at) FILTER (WHERE status = 'Running') as newest_running
        FROM $tableNameLit
      """.query[(Int, Int, Int, Int, Option[Instant], Option[Instant])].run().headOption match {
        case Some((total, running, awaiting, finished, oldestRunning, newestRunning)) =>
          RegistryStats(total, running, awaiting, finished, oldestRunning, newestRunning)
        case None                                                                     =>
          RegistryStats(0, 0, 0, 0, None, None)
      }
    }
  }
}

/** Registry statistics for monitoring and dashboard.
  *
  * @param total
  *   Total number of workflows in registry
  * @param running
  *   Number of workflows actively executing
  * @param awaiting
  *   Number of workflows waiting for signals/timers
  * @param finished
  *   Number of completed workflows
  * @param oldestRunning
  *   Timestamp of the oldest running workflow (for staleness detection)
  * @param newestRunning
  *   Timestamp of the newest running workflow
  */
case class RegistryStats(
    total: Int,
    running: Int,
    awaiting: Int,
    finished: Int,
    oldestRunning: Option[Instant],
    newestRunning: Option[Instant],
)

object OxPostgresRegistry {

  /** Create an OxPostgresRegistry with default settings.
    *
    * @param transactor
    *   Magnum Transactor wrapping a DataSource
    * @param tableName
    *   Database table name (default: "workflow_registry")
    * @param clock
    *   Clock for timestamps (default: system UTC)
    * @param tagger
    *   Optional tagger for extracting workflow metadata
    * @return
    *   Direct effect containing the registry instance
    */
  def apply(
      transactor: Transactor,
      tableName: String = "workflow_registry",
      clock: Clock = Clock.systemUTC(),
      tagger: Option[Tagger[Any]] = None,
  ): Direct[OxPostgresRegistry] = Direct {
    new OxPostgresRegistry(transactor, tableName, clock, tagger)
  }

  /** Create registry with HikariCP connection pool from JDBC URL.
    *
    * Convenience method that creates both the DataSource and registry in one call.
    *
    * @param jdbcUrl
    *   JDBC connection URL
    * @param username
    *   Database username
    * @param password
    *   Database password
    * @param tableName
    *   Database table name (default: "workflow_registry")
    * @param clock
    *   Clock for timestamps (default: system UTC)
    * @param tagger
    *   Optional tagger for extracting workflow metadata
    * @return
    *   Direct effect containing the registry instance
    */
  def fromJdbcUrl(
      jdbcUrl: String,
      username: String,
      password: String,
      tableName: String = "workflow_registry",
      clock: Clock = Clock.systemUTC(),
      tagger: Option[Tagger[Any]] = None,
  ): Direct[OxPostgresRegistry] = Direct {
    val transactor = MagnumTransactor.fromJdbcUrl(jdbcUrl, username, password)
    new OxPostgresRegistry(transactor, tableName, clock, tagger)
  }
}
