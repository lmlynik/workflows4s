---
sidebar_position: 2
---

# Implementing Effect Modules

This guide walks through implementing a new effect system integration for workflows4s.

## Overview

Workflows4s uses an `Effect[F[_]]` typeclass to abstract over different effect systems. This allows workflows to be written generically and run on any supported effect type.

## The Effect Typeclass

Located at `workflows4s-core/src/main/scala/workflows4s/runtime/instanceengine/Effect.scala`:

```scala
trait Effect[F[_]] {
  // Mutex type for effect-polymorphic locking
  type Mutex
  def createMutex: Mutex
  def withLock[A](m: Mutex)(fa: F[A]): F[A]

  // Core monadic operations
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]

  // Error handling (fa is by-name for sync effects)
  def raiseError[A](e: Throwable): F[A]
  def handleErrorWith[A](fa: => F[A])(f: Throwable => F[A]): F[A]

  // Time operations
  def sleep(duration: FiniteDuration): F[Unit]
  def realTimeInstant: F[Instant]

  // Suspension
  def delay[A](a: => A): F[A]

  // Concurrency primitives
  def ref[A](initial: A): F[Ref[F, A]]
  def start[A](fa: F[A]): F[Fiber[F, A]]
  def guaranteeCase[A](fa: F[A])(finalizer: Outcome[A] => F[Unit]): F[A]
}
```

### Supporting Abstractions

**Outcome** - Result of a background computation:
```scala
enum Outcome[+A] {
  case Succeeded(value: A)
  case Errored(error: Throwable)
  case Canceled
}
```

**Ref** - Mutable reference:
```scala
trait Ref[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def update(f: A => A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]
  def getAndUpdate(f: A => A): F[A]
}
```

**Fiber** - Background computation:
```scala
trait Fiber[F[_], A] {
  def cancel: F[Unit]
  def join: F[Outcome[A]]
}
```

## Step-by-Step Implementation

### Step 1: Create Module Structure

Create a new sbt module following this structure:

```
workflows4s-{effect}/
  src/main/scala/workflows4s/{effect}/
    {Effect}Effect.scala           # Effect instance
    {Effect}WorkflowContext.scala  # Helper context trait
  src/test/scala/workflows4s/{effect}/
    {Effect}EffectTest.scala       # Effect instance tests
    {Effect}TestRuntimeAdapter.scala  # Optional: test runtime
```

### Step 2: Add to build.sbt

```scala
lazy val `workflows4s-{effect}` = (project in file("workflows4s-{effect}"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "{effect-library}" %% "{artifact}" % "{version}",
    ),
  )
  .dependsOn(
    `workflows4s-core` % "compile->compile;test->test",
    `workflows4s-testing` % "compile->compile;test->test",
  )
```

Don't forget to add the module to the `aggregate` list in the root project.

### Step 3: Implement Effect Instance

Key considerations:

1. **Mutex**: Use your effect's semaphore or mutex primitive
2. **Ref**: Wrap your effect's mutable reference type
3. **Fiber**: Wrap your effect's fiber/fork type
4. **guaranteeCase**: Use your effect's bracket/onExit mechanism

#### Example: ZIO Implementation

```scala
package workflows4s.zio

import zio.*
import workflows4s.runtime.instanceengine.{Effect, Fiber as WFiber, Outcome, Ref as WRef}

object ZIOEffect {

  given taskEffect: Effect[Task] = new Effect[Task] {
    type Mutex = Semaphore

    def createMutex: Mutex = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Semaphore.make(1)).getOrThrow()
    }

    def withLock[A](m: Mutex)(fa: Task[A]): Task[A] = m.withPermit(fa)

    def pure[A](a: A): Task[A] = ZIO.succeed(a)
    def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] = fa.flatMap(f)
    def map[A, B](fa: Task[A])(f: A => B): Task[B] = fa.map(f)

    def raiseError[A](e: Throwable): Task[A] = ZIO.fail(e)
    def handleErrorWith[A](fa: => Task[A])(f: Throwable => Task[A]): Task[A] =
      ZIO.suspend(fa).catchAll(f)

    def sleep(duration: FiniteDuration): Task[Unit] =
      ZIO.sleep(zio.Duration.fromScala(duration))

    def realTimeInstant: Task[Instant] = Clock.instant

    def delay[A](a: => A): Task[A] = ZIO.attempt(a)

    def ref[A](initial: A): Task[WRef[Task, A]] =
      Ref.make(initial).map { zioRef =>
        new WRef[Task, A] {
          def get: Task[A] = zioRef.get
          def set(a: A): Task[Unit] = zioRef.set(a)
          def update(f: A => A): Task[Unit] = zioRef.update(f)
          def modify[B](f: A => (A, B)): Task[B] = zioRef.modify(a => f(a).swap)
          def getAndUpdate(f: A => A): Task[A] = zioRef.getAndUpdate(f)
        }
      }

    def start[A](fa: Task[A]): Task[WFiber[Task, A]] =
      fa.fork.map { zioFiber =>
        new WFiber[Task, A] {
          def cancel: Task[Unit] = zioFiber.interrupt.unit
          def join: Task[Outcome[A]] = zioFiber.await.map {
            case Exit.Success(a) => Outcome.Succeeded(a)
            case Exit.Failure(cause) =>
              cause.failureOption match {
                case Some(e) => Outcome.Errored(e)
                case None => Outcome.Canceled
              }
          }
        }
      }

    def guaranteeCase[A](fa: Task[A])(finalizer: Outcome[A] => Task[Unit]): Task[A] =
      fa.onExit {
        case Exit.Success(a) => finalizer(Outcome.Succeeded(a)).orDie
        case Exit.Failure(cause) =>
          cause.failureOption match {
            case Some(e) => finalizer(Outcome.Errored(e)).orDie
            case None => finalizer(Outcome.Canceled).orDie
          }
      }
  }
}
```

### Step 4: Create WorkflowContext Helper

```scala
package workflows4s.zio

import zio.Task
import workflows4s.wio.WorkflowContext
import workflows4s.runtime.instanceengine.Effect

trait ZIOWorkflowContext extends WorkflowContext {
  type Eff[A] = Task[A]
  given effect: Effect[Eff] = ZIOEffect.taskEffect
}
```

### Step 5: Write Tests

Test all Effect methods:

```scala
class MyEffectTest extends AnyFreeSpec with Matchers {
  import MyEffect.given
  private val E = Effect[MyEffectType]

  "MyEffect" - {
    "pure should wrap a value" in { /* ... */ }
    "flatMap should sequence effects" in { /* ... */ }
    "raiseError should create a failed effect" in { /* ... */ }
    "handleErrorWith should recover from errors" in { /* ... */ }
    "delay should suspend side effects" in { /* ... */ }
    "ref should provide mutable state" in { /* ... */ }
    "start should run effects in background" in { /* ... */ }
    "guaranteeCase should run finalizer on success" in { /* ... */ }
    "guaranteeCase should run finalizer on error" in { /* ... */ }
    "withLock should provide mutual exclusion" in { /* ... */ }
  }
}
```

## Testing with Shared Infrastructure

The `workflows4s-testing` module provides effect-polymorphic test utilities:

### RecordingKnockerUpper

Records wakeup times for testing timers:

```scala
import workflows4s.testing.RecordingKnockerUpper

val knockerUpper = RecordingKnockerUpper[MyEffect]
// Use in tests...
knockerUpper.lastRegisteredWakeup(workflowId) // => Option[Instant]
```

### EffectTestRuntimeAdapter

Base trait for test runtime adapters:

```scala
import workflows4s.testing.EffectTestRuntimeAdapter

class MyEffectTestRuntimeAdapter[Ctx <: WorkflowContext]
    extends EffectTestRuntimeAdapter[MyEffect, Ctx] {

  override val knockerUpper = RecordingKnockerUpper[MyEffect]
  override val clock = TestClock()
  override val engine = WorkflowInstanceEngine
    .builder[MyEffect]
    .withJavaTime(clock)
    .withWakeUps(knockerUpper)
    .withoutRegistering
    .withGreedyEvaluation
    .get

  // Implement runWorkflow and recover...
}
```

## Common Pitfalls

1. **Mutex creation**: `createMutex` is called synchronously - use `unsafeRunSync()` or equivalent if needed

2. **By-name parameter**: `handleErrorWith` takes `fa` by-name to handle synchronous effects that throw immediately

3. **Outcome mapping**: Make sure to correctly map your effect's outcome/exit type to `Outcome[A]`

4. **Thread safety**: Ensure `Ref` operations are truly atomic for concurrent access

5. **Direct-style effects**: For effects without a wrapper type (like Ox), create a thin wrapper type to satisfy `F[_]`

## Reference Implementations

Study these existing implementations:

- **cats-effect IO**: `workflows4s-cats/src/main/scala/workflows4s/cats/CatsEffect.scala`
- **ZIO Task**: `workflows4s-zio/src/main/scala/workflows4s/zio/ZIOEffect.scala`
- **Ox Direct**: `workflows4s-ox/src/main/scala/workflows4s/ox/OxEffect.scala`
- **Id (synchronous)**: `workflows4s-core/src/main/scala/workflows4s/runtime/instanceengine/Effect.scala`
