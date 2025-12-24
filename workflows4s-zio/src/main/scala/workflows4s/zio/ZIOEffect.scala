package workflows4s.zio

import workflows4s.runtime.instanceengine.{Effect, Fiber as WFiber, Outcome, Ref as WRef, UnsafeRun}
import zio.*

import scala.concurrent.duration.FiniteDuration

/** ZIO integration for workflows4s Effect typeclass. Provides an Effect[Task] instance that uses ZIO primitives for all operations.
  */
object ZIOEffect {

  /** Effect instance for ZIO Task (ZIO[Any, Throwable, A]).
    *
    * This implementation uses:
    *   - ZIO Semaphore for mutex/locking
    *   - ZIO Ref for mutable references
    *   - ZIO Fiber for background computations
    *   - ZIO Clock for time operations
    */
  given taskEffect: Effect[Task] = new Effect[Task] {

    // Mutex implementation using ZIO Semaphore
    type Mutex = Semaphore

    def createMutex: Task[Mutex] = Semaphore.make(1)

    def withLock[A](m: Mutex)(fa: => Task[A]): Task[A] =
      m.withPermit(fa)

    // Core monadic operations
    def pure[A](a: A): Task[A]                               = ZIO.succeed(a)
    def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] = fa.flatMap(f)
    def map[A, B](fa: Task[A])(f: A => B): Task[B]           = fa.map(f)

    // Error handling
    def raiseError[A](e: Throwable): Task[A]                                 = ZIO.fail(e)
    def handleErrorWith[A](fa: => Task[A])(f: Throwable => Task[A]): Task[A] =
      ZIO.suspend(fa).catchAll(f)

    // Time operations
    def sleep(duration: FiniteDuration): Task[Unit] =
      ZIO.sleep(zio.Duration.fromScala(duration))

    // Suspension
    def delay[A](a: => A): Task[A] = ZIO.attempt(a)

    // Concurrency primitives
    def ref[A](initial: A): Task[WRef[Task, A]] =
      Ref.make(initial).map { zioRef =>
        new WRef[Task, A] {
          def get: Task[A]                       = zioRef.get
          def set(a: A): Task[Unit]              = zioRef.set(a)
          def update(f: A => A): Task[Unit]      = zioRef.update(f)
          def modify[B](f: A => (A, B)): Task[B] = zioRef.modify(a => f(a).swap)
          def getAndUpdate(f: A => A): Task[A]   = zioRef.getAndUpdate(f)
        }
      }

    def start[A](fa: Task[A]): Task[WFiber[Task, A]] =
      fa.fork.map { zioFiber =>
        new WFiber[Task, A] {
          def cancel: Task[Unit]     = zioFiber.interrupt.unit
          def join: Task[Outcome[A]] = zioFiber.await.map {
            case Exit.Success(a)     => Outcome.Succeeded(a)
            case Exit.Failure(cause) =>
              cause.failureOption match {
                case Some(e) => Outcome.Errored(e)
                case None    => Outcome.Canceled
              }
          }
        }
      }

    def guaranteeCase[A](fa: Task[A])(finalizer: Outcome[A] => Task[Unit]): Task[A] =
      fa.onExit {
        case Exit.Success(a)     => finalizer(Outcome.Succeeded(a)).orDie
        case Exit.Failure(cause) =>
          cause.failureOption match {
            case Some(e) => finalizer(Outcome.Errored(e)).orDie
            case None    => finalizer(Outcome.Canceled).orDie
          }
      }
  }

  /** UnsafeRun instance for ZIO Task. Uses the default ZIO runtime for synchronous execution.
    */
  given taskUnsafeRun: UnsafeRun[Task] = new UnsafeRun[Task] {
    def unsafeRunSync[A](fa: Task[A]): A = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(fa).getOrThrow()
    }
  }
}
