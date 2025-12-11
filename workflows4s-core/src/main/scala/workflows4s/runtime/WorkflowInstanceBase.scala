package workflows4s.runtime

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

/** Base trait for workflow instance implementations.
  *
  * Note: This currently uses WorkflowInstanceEngine[IO] because the engine methods return IO-based results internally
  * (WakeupResult and SignalResult contain IO). The outer F[_] effect is used for the instance API.
  *
  * TODO: Consider moving to workflows4s-cats-effect module since it requires IO internally.
  */
trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext](using val E: Effect[F])
    extends WorkflowInstance[F, WCState[Ctx]]
    with StrictLogging {

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def engine: WorkflowInstanceEngine[IO]

  import E.{flatMap, map, pure, unit, liftIO, traverse_}

  override def queryState(): F[WCState[Ctx]] = flatMap(getWorkflow)(x => liftIO(engine.queryState(x)))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = flatMap(getWorkflow)(x => liftIO(engine.getProgress(x)))

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] = flatMap(getWorkflow)(x => liftIO(engine.getExpectedSignals(x)))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      flatMap(liftIO(engine.handleSignal(state, signalDef, req))) {
        case SignalResult.Processed(resultIO) =>
          flatMap(liftIO(resultIO)) { eventAndResp =>
            flatMap(persistEvent(eventAndResp.event)) { _ =>
              val newStateOpt = engine.handleEvent(state, eventAndResp.event)
              flatMap(traverse_(newStateOpt.toList)(updateState)) { _ =>
                map(handleStateChange(state, newStateOpt))(_ => Right(eventAndResp.response))
              }
            }
          }
        case SignalResult.UnexpectedSignal    => pure(Left(WorkflowInstance.UnexpectedSignal(signalDef)))
      }
    }
    lockState(processSignal)
  }

  override def wakeup(): F[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[Ctx], newStateOpt: Option[ActiveWorkflow[Ctx]]): F[Unit] = {
    newStateOpt match {
      case Some(newState) =>
        flatMap(liftIO(engine.onStateChange(oldState, newState))) { cmds =>
          traverse_(cmds.toList) { case PostExecCommand.WakeUp =>
            processWakeup(newState)
          }
        }
      case None           => unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[Ctx]): F[Unit] = {
    flatMap(liftIO(engine.triggerWakeup(state))) {
      case WakeupResult.Processed(resultIO) =>
        flatMap(liftIO(resultIO)) {
          case WakeupResult.ProcessingResult.Failed(_)        => unit
          case WakeupResult.ProcessingResult.Proceeded(event) =>
            flatMap(persistEvent(event)) { _ =>
              val newStateOpt = engine.handleEvent(state, event)
              flatMap(traverse_(newStateOpt.toList)(updateState)) { _ =>
                handleStateChange(state, newStateOpt)
              }
            }
        }
      case WakeupResult.Noop                => unit
    }
  }

  protected def recover(initialState: ActiveWorkflow[Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[Ctx]] = {
    events.toList.foldLeft(pure(initialState)) { (stateF, event) =>
      map(stateF)(engine.processEvent(_, event))
    }
  }
}
