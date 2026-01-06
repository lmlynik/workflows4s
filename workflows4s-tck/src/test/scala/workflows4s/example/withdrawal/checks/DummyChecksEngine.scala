package workflows4s.example.withdrawal.checks

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

/** A dummy ChecksEngine implementation for testing that immediately approves without running any checks.
  */
class DummyChecksEngine[F[_], Ctx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState }](
    ctx: Ctx,
)(using Effect[F])
    extends ChecksEngine[F, Ctx](ctx) {

  override def runChecks: ctx.WIO[ChecksInput[F], Nothing, ChecksState.Decided] =
    ctx.WIO.pure(ChecksState.Decided(Map(), Decision.ApprovedBySystem())).autoNamed
}

object DummyChecksEngine {

  def apply[F[_], Ctx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState }](
      ctx: Ctx,
  )(using Effect[F]): DummyChecksEngine[F, Ctx] =
    new DummyChecksEngine[F, Ctx](ctx)
}
