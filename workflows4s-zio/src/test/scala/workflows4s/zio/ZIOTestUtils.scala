package workflows4s.zio

import workflows4s.testing.EffectTestUtils
import zio.Task

/** ZIO-based test utilities for ZIO runtime tests.
  *
  * Delegates to the generic EffectTestUtils for most operations.
  */
object ZIOTestUtils extends EffectTestUtils[Task, ZIOTestCtx2.type](ZIOTestCtx2)(using ZIOEffect.taskEffect)
