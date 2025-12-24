package workflows4s.ox.magnum

import scala.util.Try

/** Codec for serializing workflow events to/from bytes.
  *
  * Users must provide implementations for their event types. Common strategies:
  *   - Java serialization (for development/testing only)
  *   - JSON (Circe, uPickle)
  *   - Protocol Buffers
  *   - Avro
  *
  * Example with Circe:
  * {{{
  * import io.circe.syntax.*
  * import io.circe.parser.decode
  *
  * given ByteCodec[MyEvent] = new ByteCodec[MyEvent] {
  *   def read(bytes: IArray[Byte]): Try[MyEvent] =
  *     Try(decode[MyEvent](new String(bytes.toArray, "UTF-8")).toTry.get)
  *
  *   def write(event: MyEvent): IArray[Byte] =
  *     IArray.unsafeFromArray(event.asJson.noSpaces.getBytes("UTF-8"))
  * }
  * }}}
  */
trait ByteCodec[Event] {

  /** Deserialize event from bytes.
    *
    * @param bytes
    *   Serialized event data
    * @return
    *   Success with deserialized event, or Failure with exception
    */
  def read(bytes: IArray[Byte]): Try[Event]

  /** Serialize event to bytes.
    *
    * @param event
    *   Event to serialize
    * @return
    *   Serialized event data
    */
  def write(event: Event): IArray[Byte]
}
