package workflows4s.runtime

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

/** In-memory synchronous runtime for workflow instances.
  *
  * This runtime executes workflows synchronously using Id as the effect type, with IO-based engine internally.
  *
  * TODO: Move to workflows4s-cats-effect module
  */
class InMemorySyncRuntime[Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[IO],
    val templateId: String,
)(using IORuntime)
    extends WorkflowRuntime[Id, Ctx] {
  val instances = new java.util.concurrent.ConcurrentHashMap[String, InMemorySyncWorkflowInstance[Ctx]]()

  override def createInstance(id: String): InMemorySyncWorkflowInstance[Ctx] = {
    instances.computeIfAbsent(
      id,
      { _ =>
        val instanceId                    = WorkflowInstanceId(templateId, id)
        val activeWf: ActiveWorkflow[Ctx] = ActiveWorkflow(instanceId, workflow, initialState)
        new InMemorySyncWorkflowInstance[Ctx](instanceId, activeWf, engine)
      },
    )
  }
}

object InMemorySyncRuntime {
  def create[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[IO],
      templateId: String = s"in-memory-sync-runtime-${java.util.UUID.randomUUID().toString.take(8)}",
  ): InMemorySyncRuntime[Ctx] =
    new InMemorySyncRuntime[Ctx](workflow, initialState, engine, templateId)(using IORuntime.global)
}
