package workflows4s.doobie.postgres.testing

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.runtime.{DelegateWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.{EventIntrospection, Runner, WorkflowTestAdapter}
import workflows4s.utils.StringUtils
import workflows4s.wio.*
import workflows4s.cats.CatsEffect.ioEffect

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](
    xa: Transactor[IO],
    eventCodec: ByteCodec[WCEvent[Ctx]],
) extends WorkflowTestAdapter[IO, Ctx] {

  // Provide the IO-specific effect and runner
  implicit override val effect: Effect[IO] = ioEffect
  implicit override val runner: Runner[IO] = new Runner[IO] {
    import cats.effect.unsafe.implicits.global
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  // Define the Actor type for this adapter
  case class PostgresTestActor(
      delegate: WorkflowInstance[IO, WCState[Ctx]],
      override val id: WorkflowInstanceId,
  ) extends DelegateWorkflowInstance[IO, WCState[Ctx]]
      with EventIntrospection[WCEvent[Ctx]] {
    // In DB runtime, events are in the DB. We could query them via Doobie if needed.
    override def getEvents: Seq[WCEvent[Ctx]] = Nil

    override def getExpectedSignals = delegate.getExpectedSignals
  }

  override type Actor = PostgresTestActor

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    // DatabaseRuntime usually handles the engine and persistence logic
    val runtime  = DatabaseRuntime.create[Ctx](workflow, state, xa, engine, eventCodec, "test")
    val idString = StringUtils.randomAlphanumericString(12)

    val instance = runner.run(runtime.createInstance(idString))
    PostgresTestActor(instance, WorkflowInstanceId("test", idString))
  }

  override def recover(first: Actor): Actor = {
    first
  }
}
