package workflows4s.example.checks

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalamock.scalatest.MockFactory
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.checks.{ChecksEvent, IOChecksEngine}
import workflows4s.testing.Runner

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with MockFactory with ChecksEngineTest.Suite {

  // Provide the bridge to run IO for the tests
  implicit val runner: Runner[IO] = new Runner[IO] {
    import cats.effect.unsafe.implicits.global
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "postgres" - {
    // Explicitly typed adapter to avoid any confusion with shadowed packages
    val adapter: workflows4s.testing.WorkflowTestAdapter[IO, IOChecksEngine.Context.Ctx] =
      new PostgresRuntimeAdapter[IOChecksEngine.Context.Ctx](xa, eventCodec)

    checkEngineTests(adapter)
  }

  lazy val eventCodec: ByteCodec[ChecksEvent] = CirceEventCodec.get()
}
