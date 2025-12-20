package workflows4s.ox

import workflows4s.testing.TestWorkflowContext

/** Direct-style version of TestCtx2 for OX runtime tests */
object OxTestCtx2 extends TestWorkflowContext[Direct](using OxEffect.directEffect)
