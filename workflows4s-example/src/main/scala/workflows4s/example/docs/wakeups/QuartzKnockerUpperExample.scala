package workflows4s.example.docs.wakeups

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.cats.CatsEffect.{ioEffect, ioUnsafeRun}
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

object QuartzKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper

  val scheduler: org.quartz.Scheduler = ???

  scheduler.start()

  // Effect-polymorphic knocker-upper (works with IO, Task, Direct, etc.)
  val knockerUpper = new QuartzKnockerUpper[IO](scheduler)

  val runtime: WorkflowRuntime[IO, MyWorkflowCtx] = createRuntime(knockerUpper)

  val initialization: IO[Unit] = knockerUpper.initialize(Seq(runtime))
// docs_end

}
