package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.{BeforeAndAfterEach, Suite}

import scala.io.Source

/** Test suite helper for Ox + Magnum + PostgreSQL tests.
  *
  * Provides:
  *   - PostgreSQL testcontainer
  *   - Magnum Transactor with HikariCP
  *   - Schema initialization
  *   - Table cleanup between tests
  *
  * Example usage:
  * {{{
  * class OxPostgresRegistryTest extends AnyFreeSpec with OxMagnumTestSuite with Matchers {
  *   "OxPostgresRegistry" - {
  *     "should store workflows" in {
  *       val registry = OxPostgresRegistry(transactor).run
  *       // ... test code ...
  *     }
  *   }
  * }
  * }}}
  */
trait OxMagnumTestSuite extends TestContainerForAll with BeforeAndAfterEach { self: Suite =>

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

    // Initialize schema
    createSchema()
  }

  /** Called after each test - truncates table to isolate tests */
  override def afterEach(): Unit = {
    super.afterEach()
    // Truncate table between tests
    val conn = transactor.dataSource.getConnection.nn
    try {
      val stmt = conn.createStatement()
      try {
        stmt.execute("TRUNCATE TABLE workflow_registry"): @scala.annotation.nowarn
        conn.commit()
        ()
      } finally {
        stmt.close()
      }
    } finally {
      conn.close()
    }
  }

  /** Initialize database schema from SQL file */
  private def createSchema(): Unit = {
    val schemaResource = getClass.getClassLoader.getResourceAsStream("schema/ox-postgres-registry-schema.sql")
    if schemaResource == null then {
      throw new RuntimeException("Schema file not found: schema/ox-postgres-registry-schema.sql")
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
