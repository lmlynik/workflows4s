package workflows4s.runtime.pekko

import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import _root_.workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId}
import _root_.workflows4s.wio.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Pekko runtime adapter for Future-based tests.
  */
class PekkoRuntimeAdapter[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
    extends FutureTestRuntimeAdapter[Ctx]
    with StrictLogging {

  implicit override val ec: ExecutionContext = actorSystem.executionContext

  /** Pekko actor messaging is slower than in-memory, so use a longer timeout. */
  override def testTimeout: FiniteDuration = 60.seconds

  val sharding = ClusterSharding(actorSystem)

  case class Stop(replyTo: ActorRef[Unit])

  type RawCmd = WorkflowBehavior.Command[Ctx]
  type Cmd    = WorkflowBehavior.Command[Ctx] | Stop

  override type Actor = FutureActor

  override def runWorkflow(
      workflow: WIO.Initial[Future, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val (entityRef, typeKey) = createEntityRef(workflow, state)
    FutureActor(entityRef, typeKey, clock)
  }

  override def recover(first: Actor): Actor = {
    given Timeout = Timeout(3.seconds)

    val isStopped = first.entityRef.ask(replyTo => Stop(replyTo))
    Await.result(isStopped, 3.seconds)
    Thread.sleep(100) // this is terrible, but sometimes akka gives us an already terminated actor if we ask for it too fast.
    val entityRef = sharding.entityRefFor(first.typeKey, first.entityRef.entityId)
    logger.debug(s"""Original Actor: ${first.entityRef}
                    |New Actor     : ${entityRef}""".stripMargin)
    FutureActor(entityRef, first.typeKey, first.actorClock)
  }

  protected def createEntityRef(
      workflow: WIO.Initial[Future, Ctx],
      state: WCState[Ctx],
  ): (EntityRef[Cmd], EntityTypeKey[Cmd]) = {
    // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
    // with single shard region its tricky to inject input into behavior creation
    val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

    // TODO we dont use PekkoRuntime because it's tricky to test recovery there.
    val _             = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(persistenceId.entityTypeHint, persistenceId.entityId)
        val base          = WorkflowBehavior(instanceId, persistenceId, workflow, state, engine)
        Behaviors.intercept[Cmd, RawCmd](() =>
          new BehaviorInterceptor[Cmd, RawCmd]() {
            override def aroundReceive(
                ctx: TypedActorContext[Cmd],
                msg: Cmd,
                target: BehaviorInterceptor.ReceiveTarget[RawCmd],
            ): Behavior[RawCmd] =
              msg match {
                case Stop(replyTo) => Behaviors.stopped(() => replyTo ! ())
                case other         =>
                  // classtag-based filtering doesnt work here due to union type
                  // we are mimicking the logic of Interceptor where unhandled messaged are passed through with casting
                  target
                    .asInstanceOf[BehaviorInterceptor.ReceiveTarget[Any]](ctx, other)
                    .asInstanceOf[Behavior[RawCmd]]
              }
          },
        )(base)
      }),
    )
    val persistenceId = UUID.randomUUID().toString
    val entityRef     = sharding.entityRefFor(typeKey, persistenceId)
    (entityRef, typeKey)
  }

  /** Future-based actor wrapper that delegates to PekkoWorkflowInstance.
    */
  case class FutureActor(entityRef: EntityRef[Cmd], typeKey: EntityTypeKey[Cmd], actorClock: Clock) extends WorkflowInstance[Future, WCState[Ctx]] {
    private val delegate: WorkflowInstance[Future, WCState[Ctx]] =
      PekkoWorkflowInstance(
        WorkflowInstanceId(entityRef.typeKey.name, entityRef.entityId),
        entityRef,
        queryTimeout = Timeout(3.seconds),
      )

    override def id: WorkflowInstanceId = delegate.id

    override def queryState(): Future[WCState[Ctx]] = delegate.queryState()

    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Future[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
      delegate.deliverSignal(signalDef, req)

    override def wakeup(): Future[Unit] = delegate.wakeup()

    override def getProgress: Future[model.WIOExecutionProgress[WCState[Ctx]]] = delegate.getProgress

    override def getExpectedSignals: Future[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
  }
}
