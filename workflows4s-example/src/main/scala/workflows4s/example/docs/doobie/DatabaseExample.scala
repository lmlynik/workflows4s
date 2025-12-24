package workflows4s.example.docs.doobie

import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, WorkflowStorage}

import scala.annotation.nowarn

@nowarn
object DatabaseExample {

  // doc_start
  // For actual usage, see workflows4s-doobie tests
  // DatabaseRuntime.create takes:
  // - workflow: WIO.Initial[IO, Ctx]
  // - initialState: WCState[Ctx]
  // - transactor: Transactor[IO]
  // - engine: WorkflowInstanceEngine[IO]
  // - eventCodec: ByteCodec[WCEvent[Ctx]]
  // - templateId: String

  // Example:
  // val runtime: DatabaseRuntime[Ctx] = DatabaseRuntime.create(workflow, initialState, transactor, engine, eventCodec, templateId)
  // val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
  // doc_end

  {
    // doc_postgres_start
    given eventCodec: ByteCodec[String] = ???

    val postgresStorage: WorkflowStorage[String] = new PostgresWorkflowStorage[String]()
    // doc_postgres_end
  }

}
