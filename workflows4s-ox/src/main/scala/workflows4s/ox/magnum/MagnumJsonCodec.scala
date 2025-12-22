package workflows4s.ox.magnum

import com.augustnagro.magnum.*
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

/** JSON codec for Magnum using Circe.
  *
  * Provides DbCodec instances for Map[String, String] stored as PostgreSQL JSONB. This enables type-safe serialization of workflow tags to the
  * database and supports JSONB operators like @> for containment queries.
  *
  * Example usage:
  * {{{
  * import workflows4s.ox.magnum.MagnumJsonCodec.given
  *
  * val tags: Map[String, String] = Map("env" -> "prod", "region" -> "us-east")
  * connect(transactor) {
  *   sql"INSERT INTO workflow_registry (tags) VALUES (\${tags})".update.run()
  * }
  *
  * // Query with JSONB containment
  * connect(transactor) {
  *   sql"SELECT * FROM workflow_registry WHERE tags @> \${Map("env" -> "prod")}::jsonb"
  *     .query[WorkflowRow]
  *     .run()
  * }
  * }}}
  */
object MagnumJsonCodec {

  /** Write a Map to JSON string using Circe */
  def writeJsonString(map: Map[String, String]): String = {
    map.asJson.noSpaces
  }

  /** Read a Map from JSON string using Circe */
  def readJsonString(json: String): Map[String, String] = {
    decode[Map[String, String]](json).getOrElse(Map.empty)
  }

  /** DbCodec for Map[String, String] as JSONB using biMap over String */
  given jsonbMapCodec: DbCodec[Map[String, String]] = DbCodec[String].biMap(
    readJsonString,
    writeJsonString,
  )
}
