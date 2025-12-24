package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.cats.IOWorkflowContext
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, DatabaseRuntime, WorkflowStorage}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.WCState

import scala.annotation.nowarn

@nowarn
object DatabaseExample {

  object MyWorkflowCtx extends IOWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  import MyWorkflowCtx.*
  {
    // doc_start
    val workflow: WIO.Initial              = ???
    val initialState: State                = ???
    val transactor: Transactor[IO]         = ???
    given eventCodec: ByteCodec[Event]     = ???
    val engine: WorkflowInstanceEngine[IO] = ???
    val templateId                         = "my-workflow"

    val runtime: DatabaseRuntime[Ctx]                      = DatabaseRuntime.create(workflow, initialState, transactor, engine, eventCodec, templateId)
    val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
    // doc_end
  }
  type Ctx = MyCtx

  val transactor: Transactor[IO]           = ???
  val storage: WorkflowStorage[String] = ???
  val templateId                           = "my-workflow"

  // For actual usage, see workflows4s-doobie tests
  // DatabaseRuntime.create takes:
  // - workflow: WIO.Initial[IO, Ctx]
  // - initialState: WCState[Ctx]
  // - transactor: Transactor[IO]
  // - engine: WorkflowInstanceEngine[IO]
  // - eventCodec: ByteCodec[WCEvent[Ctx]]
  // - templateId: String

  // After creation:
  // val runtime: DatabaseRuntime[Ctx] = DatabaseRuntime.create(...)
  // val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
  // doc_end

  {
    // doc_postgres_start
    given eventCodec: ByteCodec[String] = ???

    val postgresStorage: WorkflowStorage[String] = new PostgresWorkflowStorage[String]()
    // doc_postgres_end
  }

}
