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
import MagnumJsonCodec.given
import scala.annotation.nowarn

/** DbCodec for java.time.Instant, storing as TIMESTAMP in PostgreSQL */
given instantCodec: DbCodec[Instant] = DbCodec[Timestamp].biMap(
  ts => if ts == null then null else ts.toInstant,
  Timestamp.from,
)

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
    * @param inst
    *   Active workflow instance
    * @param executionStatus
    *   Current execution status (Running/Awaiting/Finished)
    * @return
    *   Direct[Unit] effect
    */
  override def upsertInstance(
      inst: ActiveWorkflow[?, ?],
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

    executionStatus match {
      case ExecutionStatus.Running =>
        // For Running status, use INSERT...ON CONFLICT to upsert
        connect(transactor) {
          val query = sql"""
            INSERT INTO ${SqlLiteral(tableName)}
              (instance_id, template_id, status, created_at, updated_at, wakeup_at, tags)
            VALUES (
              ${id.instanceId},
              ${id.templateId},
              $statusStr,
              $now,
              $now,
              $wakeupAt,
              $tags::jsonb
            )
            ON CONFLICT (template_id, instance_id) DO UPDATE SET
              status = EXCLUDED.status,
              updated_at = EXCLUDED.updated_at,
              wakeup_at = EXCLUDED.wakeup_at,
              tags = COALESCE(EXCLUDED.tags, ${SqlLiteral(tableName)}.tags)
          """
          query.update.run(): @nowarn
          ()
        }

      case ExecutionStatus.Awaiting | ExecutionStatus.Finished =>
        // For Awaiting/Finished, update existing record
        connect(transactor) {
          val query = sql"""
            UPDATE ${SqlLiteral(tableName)}
            SET status = $statusStr,
                updated_at = $now,
                wakeup_at = $wakeupAt,
                tags = COALESCE($tags::jsonb, tags)
            WHERE instance_id = ${id.instanceId}
              AND template_id = ${id.templateId}
          """
          query.update.run(): @nowarn
          ()
        }
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
    val cutoffTime = Instant.now(clock).minusMillis(notUpdatedFor.toMillis)

    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM ${SqlLiteral(tableName)}
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
    val statusStr = status match {
      case ExecutionStatus.Running  => "Running"
      case ExecutionStatus.Awaiting => "Awaiting"
      case ExecutionStatus.Finished => "Finished"
    }

    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM ${SqlLiteral(tableName)}
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
    connect(transactor) {
      sql"""
        SELECT template_id, instance_id
        FROM ${SqlLiteral(tableName)}
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
      connect(transactor) {
        // Use JSONB containment operator @>
        sql"""
          SELECT template_id, instance_id
          FROM ${SqlLiteral(tableName)}
          WHERE tags @> $tagFilters::jsonb
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
    connect(transactor) {
      sql"""
        SELECT
          COUNT(*) as total,
          COUNT(*) FILTER (WHERE status = 'Running') as running,
          COUNT(*) FILTER (WHERE status = 'Awaiting') as awaiting,
          COUNT(*) FILTER (WHERE status = 'Finished') as finished,
          MIN(updated_at) FILTER (WHERE status = 'Running') as oldest_running,
          MAX(updated_at) FILTER (WHERE status = 'Running') as newest_running
        FROM ${SqlLiteral(tableName)}
      """
        .query[(Int, Int, Int, Int, Option[Instant], Option[Instant])]
        .run()
        .headOption
        .map { case (total, running, awaiting, finished, oldestRunning, newestRunning) =>
          RegistryStats(total, running, awaiting, finished, oldestRunning, newestRunning)
        }
        .getOrElse(RegistryStats(0, 0, 0, 0, None, None))
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
