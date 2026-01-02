package workflows4s.example

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalamock.scalatest.MockFactory
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*
import workflows4s.testing.Runner
import cats.effect.unsafe.implicits.global

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite with MockFactory with WithdrawalWorkflowTest.Suite {

  given Runner[IO] = new Runner[IO] {
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "sqlite" - {
    withdrawalTests(new SqliteRuntimeAdapter(workdir, eventCodec))
  }

  lazy val eventCodec: ByteCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()
}
