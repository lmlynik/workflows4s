package workflows4s.runtime.wakeup.quartz

import org.quartz.*
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.{Effect, UnsafeRun}
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant
import java.util.Date

class QuartzKnockerUpper[F[_]](scheduler: Scheduler)(using E: Effect[F], U: UnsafeRun[F])
    extends KnockerUpper.Agent[F]
    with KnockerUpper.Process[F, F[Unit]] {

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = E.delay {
    val jobKey = new JobKey(id.instanceId)
    at match {
      case Some(instant) =>
        val trigger = TriggerBuilder
          .newTrigger()
          .withIdentity(id.instanceId)
          .startAt(Date.from(instant))
          .build()

        if scheduler.checkExists(jobKey) then {
          scheduler.rescheduleJob(trigger.getKey, trigger)
          ()
        } else {
          val jobDetail = JobBuilder
            .newJob(classOf[WakeupJob])
            .withIdentity(jobKey)
            .usingJobData(WakeupJob.instanceIdKey, id.instanceId)
            .usingJobData(WakeupJob.templateIdKey, id.templateId)
            .build()
          scheduler.scheduleJob(jobDetail, java.util.Set.of(trigger), true)
        }
      case None          =>
        scheduler.deleteJob(jobKey)
        ()
    }
  }

  override def initialize(wakeUp: WorkflowInstanceId => F[Unit]): F[Unit] =
    E.fromEither(scheduler.setWakeupContext(WakeupJob.Context(wakeUp, U)).toEither)

}
