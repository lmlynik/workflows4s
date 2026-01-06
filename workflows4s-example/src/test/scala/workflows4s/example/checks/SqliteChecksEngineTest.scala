package workflows4s.example.checks

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.checks.{ChecksEvent, IOChecksEngine}
import workflows4s.testing.Runner

class SqliteChecksEngineTest extends AnyFreeSpec with SqliteWorkdirSuite with ChecksEngineTest.Suite {

  // Bridge for executing IO during assertions and signals
  implicit val runner: Runner[IO] = new Runner[IO] {
    import cats.effect.unsafe.implicits.global
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "sqlite" - {
    checkEngineTests(new SqliteRuntimeAdapter[IOChecksEngine.Context.Ctx](workdir, eventCodec))
  }

  lazy val eventCodec: ByteCodec[ChecksEvent] = CirceEventCodec.get()

}
