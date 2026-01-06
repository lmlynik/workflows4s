package workflows4s.example.docs.pekko

import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.{LazyFuture, WorkflowInstanceEngine}
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.wio.FutureWorkflowContext

import scala.concurrent.ExecutionContext

object PekkoExample {

  // FutureWorkflowContext provides Effect[LazyFuture] automatically
  object MyWorkflowCtx extends FutureWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
    override val executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  // doc_start
  import MyWorkflowCtx.*
  import MyWorkflowCtx.effect
  given ExecutionContext = ???
  given ActorSystem[?]   = ???

  // Create a WorkflowInstanceEngine for LazyFuture
  // The FutureEffect is provided by FutureWorkflowContext
  val engine: WorkflowInstanceEngine[LazyFuture] =
    WorkflowInstanceEngine
      .builder[LazyFuture]
      .withJavaTime()
      .withoutWakeUps
      .withoutRegistering
      .get

  val workflow: WIO.Initial = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), engine)

  runtime.initializeShard()

  // Pekko runtime returns LazyFuture-based instances (call .run to convert to Future)
  val instance: WorkflowInstance[LazyFuture, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
