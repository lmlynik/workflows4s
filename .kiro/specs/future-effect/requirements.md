# Requirements Document

## Introduction

This feature introduces a `FutureEffect` type class instance that provides an `Effect[Future]` implementation for the workflows4s library. The Pekko runtime currently uses `cats.effect.IO` internally for workflow processing but exposes a `Future`-based API. This creates an unnecessary dependency on cats-effect for users who only want to use Pekko with Scala's standard `Future`. By providing a proper `FutureEffect`, the Pekko module can operate entirely with `Future`, simplifying the dependency graph and making the library more accessible to users who prefer not to use cats-effect.

## Glossary

- **Effect**: A type class abstraction in workflows4s that provides monadic operations, error handling, time operations, concurrency primitives, and resource management for effect types like `IO`, `Future`, or `Id`.
- **FutureEffect**: An implementation of the `Effect` type class for `scala.concurrent.Future`.
- **Pekko_Runtime**: The workflows4s runtime implementation that uses Apache Pekko for actor-based workflow execution and persistence.
- **WorkflowBehavior**: The Pekko EventSourcedBehavior that handles workflow commands and events.
- **WorkflowInstanceEngine**: The core engine that processes workflow events, signals, and wakeups.
- **Mutex**: A synchronization primitive used to ensure exclusive access to shared resources.
- **Fiber**: A lightweight concurrent computation that can be started, joined, or canceled.

## Requirements

### Requirement 1: FutureEffect Type Class Instance

**User Story:** As a library user, I want a `FutureEffect` type class instance, so that I can use workflows4s with Scala's standard `Future` without requiring cats-effect dependencies.

#### Acceptance Criteria

1. THE FutureEffect SHALL provide an `Effect[Future]` instance that implements all required operations from the `Effect` trait
2. WHEN creating a FutureEffect, THE system SHALL require an implicit `ExecutionContext` for asynchronous operations
3. THE FutureEffect SHALL implement `pure[A](a: A)` by returning `Future.successful(a)`
4. THE FutureEffect SHALL implement `flatMap` by delegating to `Future.flatMap`
5. THE FutureEffect SHALL implement `map` by delegating to `Future.map`
6. THE FutureEffect SHALL implement `raiseError` by returning `Future.failed(e)`
7. THE FutureEffect SHALL implement `handleErrorWith` by using `Future.recoverWith`
8. THE FutureEffect SHALL implement `sleep` by using blocking `Thread.sleep` wrapped in a `Future`
9. THE FutureEffect SHALL implement `delay` by wrapping the computation in `Future.apply`

### Requirement 2: FutureEffect Concurrency Primitives

**User Story:** As a library user, I want FutureEffect to support concurrency primitives, so that I can use workflows4s features that require concurrent operations.

#### Acceptance Criteria

1. THE FutureEffect SHALL implement `ref[A]` to create a mutable reference using `AtomicReference`
2. THE FutureEffect SHALL implement `start[A]` to run a Future in the background and return a `Fiber[Future, A]`
3. THE FutureEffect SHALL implement `guaranteeCase` to run finalizers based on the outcome of a Future
4. THE FutureEffect SHALL implement `createMutex` to create a `Semaphore` for synchronization
5. THE FutureEffect SHALL implement `withLock` to acquire the mutex before running the effect and release it after completion

### Requirement 3: Pekko Runtime Future Integration

**User Story:** As a Pekko runtime user, I want the Pekko module to use `FutureEffect` internally, so that I don't need cats-effect as a dependency when using Pekko.

#### Acceptance Criteria

1. THE WorkflowBehavior SHALL use `Effect[Future]` instead of `IO` for workflow processing
2. THE PekkoRuntime SHALL accept a `WorkflowInstanceEngine[Future]` instead of `WorkflowInstanceEngine[IO]`
3. THE PekkoRuntime SHALL use the implicit `FutureEffect` for all effect operations
4. WHEN processing events, THE WorkflowBehavior SHALL use `FutureEffect` operations instead of `IO` operations
5. WHEN handling signals, THE WorkflowBehavior SHALL use `FutureEffect` operations instead of `IO` operations

### Requirement 4: Pekko Example Updates

**User Story:** As a library user, I want the Pekko examples to demonstrate using `FutureEffect`, so that I can learn how to use the Pekko runtime without cats-effect.

#### Acceptance Criteria

1. THE PekkoExample documentation SHALL demonstrate creating a `WorkflowInstanceEngine[Future]`
2. THE Main example application SHALL use `FutureEffect` for the Pekko runtime
3. THE WithdrawalWorkflowService SHALL use `Future` instead of `IO` for its operations
4. WHEN the examples use knocker-upper functionality, THE examples SHALL use a Future-compatible knocker-upper

### Requirement 5: Backward Compatibility

**User Story:** As an existing library user, I want the ability to continue using `IO` with Pekko if I prefer, so that I don't have to change my existing code.

#### Acceptance Criteria

1. THE existing `futureEffect` method in the Effect companion object SHALL remain available
2. THE FutureEffect SHALL be usable alongside the existing `ioEffect` in the same application
3. IF a user wants to use IO with Pekko, THEN THE user SHALL be able to convert between `Future` and `IO` using existing conversion utilities
