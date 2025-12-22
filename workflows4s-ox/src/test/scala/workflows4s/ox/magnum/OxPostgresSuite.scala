package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.{BeforeAndAfterEach, Suite}

import scala.io.Source

/** Test suite helper for Ox + Magnum + PostgreSQL workflow tests.
  *
  * Provides:
  *   - PostgreSQL testcontainer
  *   - Magnum Transactor with HikariCP
  *   - Schema initialization (both journal and registry tables)
  *   - Table cleanup between tests
  *
  * Example usage:
  * {{{
  * class MyWorkflowTest extends AnyFreeSpec with OxPostgresSuite with Matchers {
  *   "My workflow" - {
  *     "should process events" in {
  *       val runtime = DatabaseRuntime.create(
  *         workflow,
  *         initialState,
  *         transactor,
  *         engine,
  *         eventCodec,
  *         "my-workflow"
  *       ).run
  *       // ... test code ...
  *     }
  *   }
  * }
  * }}}
  */
trait OxPostgresSuite extends TestContainerForAll with BeforeAndAfterEach { self: Suite =>

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def()

  var transactor: Transactor = scala.compiletime.uninitialized

  /** Called after container starts - initializes transactor and schema */
  override def afterContainersStart(container: Containers): Unit = {
    super.afterContainersStart(container)

    // Create HikariCP DataSource
    val dataSource = MagnumTransactor.createHikariDataSource(
      jdbcUrl = container.jdbcUrl,
      username = container.username,
      password = container.password,
      maxPoolSize = 5, // Small pool for tests
    )

    transactor = Transactor(dataSource)

    // Initialize all schemas
    createSchemas()
  }

  /** Called after each test - truncates tables to isolate tests */
  override def afterEach(): Unit = {
    super.afterEach()
    // Truncate tables between tests
    val conn = transactor.dataSource.getConnection.nn
    try {
      val stmt = conn.createStatement()
      try {
        stmt.execute("TRUNCATE TABLE workflow_journal, workflow_registry CASCADE"): @scala.annotation.nowarn
        conn.commit()
        ()
      } finally {
        stmt.close()
      }
    } finally {
      conn.close()
    }
  }

  /** Initialize database schemas from SQL files */
  private def createSchemas(): Unit = {
    val schemaFiles = List(
      "schema/ox-postgres-journal-schema.sql",
      "schema/ox-postgres-registry-schema.sql",
    )

    schemaFiles.foreach { file =>
      val schemaResource = getClass.getClassLoader.getResourceAsStream(file)
      if schemaResource == null then {
        throw new RuntimeException(s"Schema file not found: $file")
      }

      val schemaSql = Source.fromInputStream(schemaResource).mkString

      // Split SQL into individual statements and execute each separately
      // JDBC can only execute one statement at a time
      val statements = schemaSql
        .split(";")
        .map(_.trim)
        .filter(_.nonEmpty)
        .filterNot(s => s.startsWith("--") && !s.contains("\n")) // Remove single-line comments

      // Execute schema directly without connect wrapper to avoid transaction/connection pool issues
      val conn = transactor.dataSource.getConnection.nn
      try {
        // Disable autocommit and manually commit at the end to ensure schema changes are persisted
        conn.setAutoCommit(false)
        val stmt = conn.createStatement()
        try {
          statements.foreach { sql =>
            if sql.nonEmpty then {
              stmt.execute(sql): @scala.annotation.nowarn
              ()
            }
          }
          // Commit all schema changes
          conn.commit()
        } catch {
          case e: Exception =>
            conn.rollback()
            throw e
        } finally {
          stmt.close()
        }
      } finally {
        conn.close()
      }
    }
  }

}
