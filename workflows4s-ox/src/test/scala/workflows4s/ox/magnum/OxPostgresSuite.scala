package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.{BeforeAndAfterEach, Suite}
import workflows4s.ox.Direct
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
  *       ).runSync
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
    connect(transactor) {
      sql"TRUNCATE TABLE workflow_journal, workflow_registry CASCADE".update.run(): @scala.annotation.nowarn
      ()
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

      // Split by semicolon and execute each statement
      val statements = schemaSql
        .split(";")
        .map(_.trim)
        .filter(_.nonEmpty)
        .filterNot(_.startsWith("--")) // Remove comment-only lines

      connect(transactor) {
        statements.foreach { stmt =>
          val query = sql"#$stmt"
          query.update.run(): @scala.annotation.nowarn
          ()
        }
      }
    }
  }

  /** Helper extension method to run Direct computations synchronously in tests */
  extension [A](d: Direct[A]) {
    def runSync: A = d.run
  }
}
