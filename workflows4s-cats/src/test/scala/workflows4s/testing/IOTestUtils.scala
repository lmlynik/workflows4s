package workflows4s.testing

import cats.effect.IO
import workflows4s.cats.CatsEffect.ioEffect
import workflows4s.wio.IOTestCtx2

/** IO-based test utilities for runtimes that use IO effect type (Pekko, Doobie).
  *
  * Delegates to the generic EffectTestUtils for most operations.
  */
object IOTestUtils extends EffectTestUtils[IO, IOTestCtx2.type](IOTestCtx2)
