package workflows4s.example.docs.pekko

import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.wio.FutureWorkflowContext

import scala.concurrent.{ExecutionContext, Future}

object PekkoExample {

  // FutureWorkflowContext provides Effect[Future] automatically
  object MyWorkflowCtx extends FutureWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  import MyWorkflowCtx.effect
  given ExecutionContext = ???
  given ActorSystem[?]   = ???

  // Create a WorkflowInstanceEngine for Future
  // The FutureEffect is provided by FutureWorkflowContext
  val engine: WorkflowInstanceEngine[Future] =
    WorkflowInstanceEngine
      .builder[Future]
      .withJavaTime()
      .withoutWakeUps
      .withoutRegistering
      .get

  val workflow: WIO.Initial = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), engine)

  runtime.initializeShard()

  // Pekko runtime returns Future-based instances
  val instance: WorkflowInstance[Future, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
