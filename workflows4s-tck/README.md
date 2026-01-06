# workflows4s-tck

Technology Compatibility Kit (TCK) module for workflows4s.

## Purpose

This module provides **effect-polymorphic workflow definitions** and **reusable test suites** that can be instantiated with any effect type `F[_]` (IO, Future, Id, etc.). It eliminates duplication between different runtime implementations by extracting generic workflow logic.

## Contents

### Main Sources (`src/main/scala`)

Generic workflow definitions that work with any effect type:

- **WithdrawalWorkflow** - A complete withdrawal workflow demonstrating signals, timers, error handling, and embedded workflows
- **ChecksEngine** - An embedded workflow for running compliance checks with retry logic and timeouts
- **Domain models** - WithdrawalData, WithdrawalEvent, WithdrawalSignal, ChecksState, etc.

### Test Sources (`src/test/scala`)

Reusable test suites and utilities:

- **WithdrawalWorkflowTestSuite[F]** - Generic test suite for withdrawal workflow that can be mixed into concrete test classes
- **ChecksEngineTestSuite[F]** - Generic test suite for the checks engine
- **TestWithdrawalService[F]** - Mock service for testing
- **Test contexts** - WithdrawalWorkflowTestContext, ChecksEngineTestContext

## Usage

To test a new runtime implementation:

1. Create a test class that extends `WithdrawalWorkflowTestSuite[F]` for your effect type
2. Provide the required `Effect[F]` instance
3. Create a `WorkflowTestAdapter` for your runtime
4. Call `withdrawalTests(adapter)` to run the full test suite

Example:
```scala
class MyRuntimeWithdrawalTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[IO] {
  override given effect: Effect[IO] = CatsEffect.ioEffect
  override val testContext = new WithdrawalWorkflowTestContext[IO]

  "my-runtime" - {
    val adapter = new MyRuntimeAdapter[testContext.Context.Ctx](...)
    withdrawalTests(adapter)
  }
}
```

## Design Principles

1. **Effect polymorphism** - All workflow definitions use `F[_]` type parameter, allowing the same workflow to run with different effect systems
2. **No runtime-specific dependencies** - TCK module only depends on `workflows4s-core`, not on Pekko, Doobie, or cats-effect
3. **Reusable test logic** - Test suites validate behavior consistently across all runtime implementations
