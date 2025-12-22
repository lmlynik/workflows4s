package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.{BeforeAndAfterEach, Suite}
import workflows4s.ox.Direct

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
  *       val registry = OxPostgresRegistry(transactor).runSync
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
    connect(transactor) {
      sql"TRUNCATE TABLE workflow_registry".update.run(): @scala.annotation.nowarn
      ()
    }
  }

  /** Initialize database schema from SQL file */
  private def createSchema(): Unit = {
    val schemaResource = getClass.getClassLoader.getResourceAsStream("schema/ox-postgres-registry-schema.sql")
    if schemaResource == null then {
      throw new RuntimeException("Schema file not found: schema/ox-postgres-registry-schema.sql")
    }

    val schemaSql = Source.fromInputStream(schemaResource).mkString

    // Split by semicolon and execute each statement
    val statements = schemaSql
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.startsWith("--")) // Remove comment-only lines

    connect(transactor) {
      statements.foreach { stmt =>
        val query = sql"#$stmt"
        query.update.run()
      }
    }
  }

  /** Helper extension method to run Direct computations synchronously in tests */
  extension [A](d: Direct[A]) {
    def runSync: A = d.run
  }
}
