package workflows4s.doobie.sqlite.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import _root_.workflows4s.doobie.ByteCodec
import _root_.workflows4s.doobie.sqlite.SqliteRuntime
import _root_.workflows4s.runtime.WorkflowInstance
import _root_.workflows4s.testing.IOTestRuntimeAdapter
import _root_.workflows4s.wio.*

import java.nio.file.Path
import scala.util.Random

/** IO-based test adapter for SQLite runtime. Use this for tests that expect IOTestRuntimeAdapter.
  */
class SqliteIOTestRuntimeAdapter[Ctx <: WorkflowContext](workdir: Path, eventCodec: ByteCodec[WCEvent[Ctx]]) extends IOTestRuntimeAdapter[Ctx] {

  override type Actor = WorkflowInstance[IO, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val id      = s"sqlruntime-workflow-${Random.nextLong()}"
    val runtime = SqliteRuntime.create[Ctx](workflow, state, eventCodec, engine, workdir).unsafeRunSync()
    runtime.createInstance(id).unsafeRunSync()
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
