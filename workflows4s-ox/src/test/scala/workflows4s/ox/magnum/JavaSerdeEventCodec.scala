package workflows4s.ox.magnum

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.util.Try

/** ByteCodec using Java serialization.
  *
  * WARNING: This is for testing only. Production code should use proper serialization (JSON, Protobuf, Avro, etc.) as Java serialization is:
  *   - Fragile (breaks on class changes)
  *   - Not secure (deserialization vulnerabilities)
  *   - Not cross-platform (JVM only)
  *   - Verbose (large payload size)
  *
  * Example for production with Circe:
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
object JavaSerdeEventCodec {

  /** Create a Java serialization-based ByteCodec for any Serializable type.
    *
    * @tparam T
    *   Event type (must be Serializable)
    * @return
    *   ByteCodec using Java serialization
    */
  def get[T]: ByteCodec[T] = new ByteCodec[T] {

    override def read(bytes: IArray[Byte]): Try[T] = Try {
      val bais = new ByteArrayInputStream(IArray.genericWrapArray(bytes).toArray)
      val ois  = new ObjectInputStream(bais)
      try {
        val obj = ois.readObject()
        obj.asInstanceOf[T]
      } finally {
        ois.close()
      }
    }

    override def write(event: T): IArray[Byte] = {
      val baos = new ByteArrayOutputStream()
      val oos  = new ObjectOutputStream(baos)
      try {
        oos.writeObject(event)
        oos.flush()
        IArray.unsafeFromArray(baos.toByteArray)
      } finally {
        oos.close()
      }
    }
  }
}
