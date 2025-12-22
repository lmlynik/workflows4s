package workflows4s.example.docs

import workflows4s.wio.internal.WorkflowEmbedding
import workflows4s.wio.SignalDef
import workflows4s.cats.IOWorkflowContext
import workflows4s.cats.CatsEffect.given

object EmbeddingExample {

  // ============================================
  // Inner workflow: Approval subprocess
  // ============================================

  case class ApprovalRequest(reviewerId: String)
  case class ApprovalDecision(approved: Boolean, comment: String)

  sealed trait ApprovalEvent
  object ApprovalEvent {
    case class ReviewStarted(reviewerId: String)                extends ApprovalEvent
    case class DecisionMade(approved: Boolean, comment: String) extends ApprovalEvent
    case class AdditionalInfoRequested(question: String)        extends ApprovalEvent
    case class AdditionalInfoProvided(answer: String)           extends ApprovalEvent
  }

  sealed trait ApprovalState
  object ApprovalState {
    case object Pending                                           extends ApprovalState
    case class UnderReview(reviewerId: String)                    extends ApprovalState
    case class AwaitingInfo(reviewerId: String, question: String) extends ApprovalState
    case object Approved                                          extends ApprovalState
  }

  object InnerContext extends IOWorkflowContext {
    override type Event = ApprovalEvent
    override type State = ApprovalState
  }

  object InnerSignals {
    val startReview    = SignalDef[ApprovalRequest, Unit]()
    val submitDecision = SignalDef[ApprovalDecision, Unit]()
  }

  // ============================================
  // Outer workflow: Order processing
  // ============================================

  sealed trait OrderEvent
  object OrderEvent {
    case class Created(orderId: String)           extends OrderEvent
    case class ApprovalStep(inner: ApprovalEvent) extends OrderEvent
    case object Shipped                           extends OrderEvent
  }

  sealed trait OrderState
  object OrderState {
    case object Empty                                                     extends OrderState
    case class Created(orderId: String)                                   extends OrderState
    case class AwaitingApproval(orderId: String, approval: ApprovalState) extends OrderState
    case class Approved(orderId: String)                                  extends OrderState
    case class Shipped(orderId: String)                                   extends OrderState
  }

  object OuterContext extends IOWorkflowContext {
    override type Event = OrderEvent
    override type State = OrderState
  }

  // start_embedding
  // Define the embedding that maps inner workflow state/events to outer
  val approvalEmbedding = new WorkflowEmbedding[InnerContext.Ctx, OuterContext.Ctx, OrderState.Created] {

    override def convertEvent(e: ApprovalEvent): OrderEvent =
      OrderEvent.ApprovalStep(e)

    override def unconvertEvent(e: OrderEvent): Option[ApprovalEvent] = e match {
      case OrderEvent.ApprovalStep(inner) => Some(inner)
      case _                              => None
    }

    override type OutputState[T <: ApprovalState] <: OrderState = T match {
      case ApprovalState.Pending.type  => OrderState.AwaitingApproval
      case ApprovalState.UnderReview   => OrderState.AwaitingApproval
      case ApprovalState.AwaitingInfo  => OrderState.AwaitingApproval
      case ApprovalState.Approved.type => OrderState.Approved
    }

    override def convertState[T <: ApprovalState](s: T, input: OrderState.Created): OutputState[T] =
      (s match {
        case ApprovalState.Pending         => OrderState.AwaitingApproval(input.orderId, s)
        case x: ApprovalState.UnderReview  => OrderState.AwaitingApproval(input.orderId, x)
        case x: ApprovalState.AwaitingInfo => OrderState.AwaitingApproval(input.orderId, x)
        case ApprovalState.Approved        => OrderState.Approved(input.orderId)
      }).asInstanceOf[OutputState[T]]

    override def unconvertState(outerState: OrderState): Option[ApprovalState] = outerState match {
      case OrderState.AwaitingApproval(_, approval) => Some(approval)
      case _: OrderState.Approved                   => Some(ApprovalState.Approved)
      case _                                        => None
    }
  }

  // Inner workflow: multi-step approval process
  private val awaitReviewStart: InnerContext.WIO[ApprovalState.Pending.type, Nothing, ApprovalState.UnderReview] =
    InnerContext.WIO
      .handleSignal(InnerSignals.startReview)
      .using[ApprovalState.Pending.type]
      .purely((_, req) => ApprovalEvent.ReviewStarted(req.reviewerId))
      .handleEvent((_, evt) => ApprovalState.UnderReview(evt.reviewerId))
      .voidResponse
      .named("Await Reviewer Assignment")

  private val awaitDecision: InnerContext.WIO[ApprovalState.UnderReview, Nothing, ApprovalState.Approved.type] =
    InnerContext.WIO
      .handleSignal(InnerSignals.submitDecision)
      .using[ApprovalState.UnderReview]
      .purely((_, decision) => ApprovalEvent.DecisionMade(decision.approved, decision.comment))
      .handleEvent((_, _) => ApprovalState.Approved)
      .voidResponse
      .named("Await Approval Decision")

  val innerWorkflow: InnerContext.WIO[ApprovalState.Pending.type, Nothing, ApprovalState.Approved.type] =
    awaitReviewStart >>> awaitDecision

  // Embed the inner workflow in the outer workflow
  val embeddedApproval: OuterContext.WIO[OrderState.Created, Nothing, OrderState.Approved] =
    OuterContext.WIO.embed[OrderState.Created, Nothing, ApprovalState.Approved.type, InnerContext.Ctx, approvalEmbedding.OutputState](
      innerWorkflow.transformInput((_: OrderState.Created) => ApprovalState.Pending),
    )(approvalEmbedding)
  // end_embedding

  // Full outer workflow using the embedded subprocess
  val createOrder: OuterContext.WIO[OrderState.Empty.type, Nothing, OrderState.Created] =
    OuterContext.WIO.pure(OrderState.Created("order-123")).named("Create Order")

  val shipOrder: OuterContext.WIO[OrderState.Approved, Nothing, OrderState.Shipped] =
    OuterContext.WIO.pure(OrderState.Shipped("order-123")).named("Ship Order")

  val workflow: OuterContext.WIO[OrderState.Empty.type, Nothing, OrderState.Shipped] =
    createOrder >>> embeddedApproval >>> shipOrder

}
