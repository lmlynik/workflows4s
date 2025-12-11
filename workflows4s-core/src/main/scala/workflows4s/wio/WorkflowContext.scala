package workflows4s.wio

import workflows4s.effect.Effect
import workflows4s.wio.builders.{AllBuilders, InterruptionBuilder}

import scala.compiletime.deferred

trait WorkflowContext { self =>
  type Event
  type State
  type F[_]

  given effect: Effect[F] = deferred

  type Ctx = WorkflowContext.AUX[State, Event, F]

  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
  object WIO extends AllBuilders[Ctx, F](using effect) {
    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, F, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Ctx]

    def interruption: InterruptionBuilder.Step0[Ctx, F] = InterruptionBuilder.Step0[Ctx, F]()
  }
}

object WorkflowContext {
  private type AuxS[_S] = WorkflowContext { type State = _S }
  private type AuxE[_E] = WorkflowContext { type Event = _E }

  type State[T <: WorkflowContext] = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext] = T match {
    case AuxE[e] => e
  }

  type AUX[St, Evt, F0[_]] = WorkflowContext { type State = St; type Event = Evt; type F[A] = F0[A] }
}
