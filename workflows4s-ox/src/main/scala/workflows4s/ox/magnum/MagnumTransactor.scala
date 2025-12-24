package workflows4s.ox.magnum

import com.augustnagro.magnum.Transactor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import javax.sql.DataSource

/** Helper for creating Magnum Transactors with HikariCP connection pools.
  *
  * Provides factory methods to create properly configured HikariCP DataSources and Magnum Transactors for PostgreSQL databases. HikariCP is the
  * industry-standard JDBC connection pool, recommended by Magnum for production use.
  *
  * Example usage:
  * {{{
  * // Create transactor from JDBC URL
  * val transactor = MagnumTransactor.fromJdbcUrl(
  *   "jdbc:postgresql://localhost:5432/workflows",
  *   "postgres",
  *   "password"
  * )
  *
  * // Use with Magnum
  * connect(transactor) {
  *   sql"SELECT * FROM workflow_registry".query[...].run()
  * }
  *
  * // Don't forget to close DataSource on shutdown
  * transactor.dataSource.close()
  * }}}
  */
object MagnumTransactor {

  /** Create a HikariCP DataSource with recommended settings for PostgreSQL.
    *
    * @param jdbcUrl
    *   JDBC connection URL (e.g., "jdbc:postgresql://localhost:5432/mydb")
    * @param username
    *   Database username
    * @param password
    *   Database password
    * @param maxPoolSize
    *   Maximum number of connections in the pool (default: 10)
    * @param connectionTimeout
    *   Maximum time to wait for a connection in milliseconds (default: 30 seconds)
    * @param idleTimeout
    *   Maximum time a connection can sit idle in milliseconds (default: 10 minutes)
    * @return
    *   Configured HikariDataSource. Call close() on application shutdown.
    */
  def createHikariDataSource(
      jdbcUrl: String,
      username: String,
      password: String,
      maxPoolSize: Int = 10,
      connectionTimeout: Long = 30000, // 30 seconds
      idleTimeout: Long = 600000,      // 10 minutes
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(jdbcUrl)
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("org.postgresql.Driver") // Explicitly set PostgreSQL driver
    config.setMaximumPoolSize(maxPoolSize)
    config.setConnectionTimeout(connectionTimeout)
    config.setIdleTimeout(idleTimeout)
    config.setAutoCommit(false)                        // Magnum handles transactions

    // PostgreSQL-specific optimizations
    config.addDataSourceProperty("cachePrepStmts", "true")
    config.addDataSourceProperty("prepStmtCacheSize", "250")
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")

    new HikariDataSource(config)
  }

  /** Create a Transactor from an existing DataSource.
    *
    * Use this when you already have a DataSource configured (e.g., from application configuration or DI container).
    *
    * @param dataSource
    *   JDBC DataSource
    * @return
    *   Magnum Transactor wrapping the DataSource
    */
  def fromDataSource(dataSource: DataSource): Transactor = {
    Transactor(dataSource)
  }

  /** Create a Transactor with HikariCP from JDBC URL.
    *
    * Convenience method that creates both HikariDataSource and Transactor in one call. The DataSource will need to be closed on application shutdown.
    *
    * @param jdbcUrl
    *   JDBC connection URL
    * @param username
    *   Database username
    * @param password
    *   Database password
    * @param maxPoolSize
    *   Maximum number of connections in the pool (default: 10)
    * @return
    *   Magnum Transactor with HikariCP connection pool
    */
  def fromJdbcUrl(
      jdbcUrl: String,
      username: String,
      password: String,
      maxPoolSize: Int = 10,
  ): Transactor = {
    val dataSource = createHikariDataSource(jdbcUrl, username, password, maxPoolSize)
    fromDataSource(dataSource)
  }
}
