package workflows4s.testing

import cats.effect.IO
import workflows4s.cats.CatsEffect
import workflows4s.runtime.instanceengine.Effect
import cats.effect.unsafe.implicits.global

class IOWorkflowTest extends WorkflowRuntimeTest[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override def unsafeRun(program: => IO[Unit]): Unit =
    program.timeout(testTimeout).unsafeRunSync()

  implicit val runner: Runner[IO] = new Runner[IO] {
    def run[A](fa: IO[A]): A = fa.unsafeRunSync()
  }

  def getAdapter: Adapter = new WorkflowTestAdapter.InMemory[IO, ctx.type]()

  workflowTests(getAdapter)
}
