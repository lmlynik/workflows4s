package workflows4s.zio

import workflows4s.testing.TestWorkflowContext
import zio.Task

/** ZIO-based version of TestCtx2 for ZIO runtime tests */
object ZIOTestCtx2 extends TestWorkflowContext[Task](using ZIOEffect.taskEffect)
