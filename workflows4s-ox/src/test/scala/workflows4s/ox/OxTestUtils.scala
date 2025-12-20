package workflows4s.ox

import workflows4s.testing.EffectTestUtils

/** Direct-style test utilities for OX runtime tests.
  *
  * Delegates to the generic EffectTestUtils for most operations.
  */
object OxTestUtils extends EffectTestUtils[Direct, OxTestCtx2.type](OxTestCtx2)(using OxEffect.directEffect)
