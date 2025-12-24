package workflows4s.runtime.wakeup.quartz

import scala.util.{Failure, Success, Try}
import org.quartz.{Job, JobExecutionContext, Scheduler}
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.UnsafeRun
import workflows4s.runtime.wakeup.quartz.WakeupJob.{instanceIdKey, templateIdKey, wakeupContextsKey}

class WakeupJob extends Job {
  override def execute(context: JobExecutionContext): Unit = {
    val id         = context.getJobDetail.getJobDataMap.getString(instanceIdKey)
    val templateId = context.getJobDetail.getJobDataMap.getString(templateIdKey)
    val wakeupCtx  = context.getScheduler.getWakeupContext
    wakeup(WorkflowInstanceId(templateId, id), wakeupCtx.get)
  }

  private def wakeup[F[_]](id: WorkflowInstanceId, ctx: WakeupJob.Context[F]): Unit = {
    ctx.unsafeRun.unsafeRunSync(ctx.wakeup(id))
  }
}

object WakeupJob {
  val wakeupContextsKey = "workflows4s-wakeups"
  val instanceIdKey     = "instance-id"
  val templateIdKey     = "template-id"

  case class Context[F[_]](wakeup: WorkflowInstanceId => F[Unit], unsafeRun: UnsafeRun[F])
}

extension (scheduler: Scheduler) {

  def getWakeupContext: Try[WakeupJob.Context[?]] = {
    Option(scheduler.getContext.get(wakeupContextsKey))
      .map(_.asInstanceOf[WakeupJob.Context[?]]) match {
      case Some(ctx) => Success(ctx)
      case None      => Failure(new RuntimeException(s"No wakeup context available"))
    }
  }

  def setWakeupContext[F[_]](ctx: WakeupJob.Context[F]): Try[Unit] = {
    val ctxOpt = Option(scheduler.getContext.get(wakeupContextsKey))

    ctxOpt match {
      case Some(_) => Failure(new RuntimeException(s"Wakeup context already set"))
      case None    =>
        scheduler.getContext.put(wakeupContextsKey, ctx)
        Success(())
    }
  }

}
