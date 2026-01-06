package workflows4s.example

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalamock.scalatest.MockFactory
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.*
import workflows4s.testing.Runner

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with MockFactory with WithdrawalWorkflowTest.Suite {

  // Define how to run IOs in this test
  implicit val runner: Runner[IO] = new Runner[IO] {
    import cats.effect.unsafe.implicits.global
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  "postgres" - {
    withdrawalTests(new PostgresRuntimeAdapter[IOWithdrawalWorkflow.Context.Ctx](xa, eventCodec))
  }

  lazy val eventCodec: ByteCodec[IOWithdrawalWorkflow.Context.Event] = CirceEventCodec.get()
}
