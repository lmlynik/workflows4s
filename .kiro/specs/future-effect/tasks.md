# Implementation Plan: FutureEffect

## Overview

This implementation plan converts the Pekko runtime from using `cats.effect.IO` to `scala.concurrent.Future` by leveraging the existing `futureEffect` implementation in the core module and refactoring the Pekko components to be effect-polymorphic.

## Tasks

- [x] 1. Convert existing futureEffect to a given instance and create FutureWorkflowContext
  - [x] 1.1 Change `def futureEffect` to `given futureEffect` in Effect companion object
    - Changed `def futureEffect(using ec: ...)` to `given futureEffect(using ec: ...)`
    - This allows automatic resolution when `ExecutionContext` is in scope
    - _Requirements: 1.1, 1.2, 5.1_

  - [x] 1.2 Create FutureWorkflowContext trait in core module
    - Created `workflows4s-core/src/main/scala/workflows4s/wio/FutureWorkflowContext.scala`
    - Follows same pattern as `IOWorkflowContext`
    - Has overridable `executionContext` defaulting to `ExecutionContext.global`
    - _Requirements: 1.1, 1.2_

- [x] 2. Refactor WorkflowBehavior to use Future instead of IO
  - [x] 2.1 Update WorkflowBehavior to use Future
    - Change signature to accept `WIO.Initial[Future, Ctx]` and `WorkflowInstanceEngine[Future]`
    - Add implicit `ExecutionContext` parameter
    - Update `State` class to use `ActiveWorkflow[Future, Ctx]`
    - _Requirements: 3.1, 3.4, 3.5_

  - [x] 2.2 Update internal effect operations to use Future directly
    - Replace `IO` operations with `Future` operations
    - Remove `unsafeToFuture()` calls since we're already using Future
    - Use `Await.result` for synchronous event handling
    - _Requirements: 3.4, 3.5_

- [x] 3. Refactor PekkoRuntime to support Future
  - [x] 3.1 Update PekkoRuntime trait and implementation
    - Parameterize by effect type `F[_]`
    - Accept `WorkflowInstanceEngine[F]` instead of `WorkflowInstanceEngine[IO]`
    - Add `EffectRunner[F]` or `runEffect` parameter
    - _Requirements: 3.2, 3.3_

  - [x] 3.2 Verify PekkoRuntime factory method uses Future
    - The `PekkoRuntime.create` method now uses `Effect[Future]`
    - No separate `createFuture` method needed since Future is the default
    - _Requirements: 3.2, 3.3_

- [x] 4. Checkpoint - Verify Pekko module compiles
  - Main source code compiles successfully
  - Test code has expected compilation errors (addressed in Task 5)
  - Test adapters need to be updated to use Future instead of IO

- [x] 5. Update PekkoRuntimeAdapter for tests
  - [x] 5.1 Update PekkoRuntimeAdapter to use Future-based workflow
    - Change from `WIO.Initial[IO, Ctx]` to `WIO.Initial[Future, Ctx]`
    - Use `FutureEffect` given instance
    - Update effect conversions
    - _Requirements: 3.1, 3.2_

  - [x] 5.2 Remove PekkoIOTestRuntimeAdapter and migrate to Future
    - Delete PekkoIOTestRuntimeAdapter.scala as IO is no longer used in Pekko module
    - Update any tests that were using PekkoIOTestRuntimeAdapter to use PekkoRuntimeAdapter with Future
    - _Requirements: 3.1, 3.2_

- [-] 6. Update example applications
  - [x] 6.1 Update Main.scala to use FutureEffect
    - Replace `IO` with `Future` in workflow definition
    - Use `WorkflowInstanceEngine[Future]`
    - Update knocker-upper to Future-compatible version
    - _Requirements: 4.2, 4.4_

  - [x] 6.2 Update WithdrawalWorkflowService to use Future
    - Change return types from `IO` to `Future`
    - Remove cats-effect IO imports where not needed
    - _Requirements: 4.3_

  - [x] 6.3 Update PekkoExample documentation code
    - Show `WorkflowInstanceEngine[Future]` usage
    - Demonstrate FutureEffect import
    - _Requirements: 4.1_

- [x] 7. Checkpoint - Verify examples compile and run
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Final checkpoint - Run all tests
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- All tasks are required for comprehensive implementation
- The existing `futureEffect` method already implements all Effect operations correctly
- The main work is refactoring Pekko components to be effect-polymorphic
- Backward compatibility with IO is maintained through the `EffectRunner` abstraction
- Property tests use ScalaCheck with minimum 100 iterations
