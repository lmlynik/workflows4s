package workflows4s.ox

import workflows4s.runtime.instanceengine.{Effect, Fiber as WFiber, Outcome, Ref as WRef, UnsafeRun}

import java.util.concurrent.Semaphore as JSemaphore
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/** Ox integration for workflows4s Effect typeclass. Provides an Effect[Direct] instance that uses Ox primitives for structured concurrency
  * operations.
  *
  * Note: Ox uses direct style and structured concurrency. The `start` operation requires being called within a `supervised` block in the calling code
  * for proper fiber management.
  */
object OxEffect {

  /** Effect instance for Direct (Ox direct-style wrapper).
    *
    * This implementation uses:
    *   - Java Semaphore for mutex/locking
    *   - AtomicReference for mutable references
    *   - Ox fork for background computations (when in supervised scope)
    *   - Ox sleep for time operations
    */
  given directEffect: Effect[Direct] = new Effect[Direct] {

    // Mutex implementation using Java Semaphore
    type Mutex = JSemaphore

    def createMutex: Direct[Mutex] = Direct(new JSemaphore(1))

    def withLock[A](m: Mutex)(fa: => Direct[A]): Direct[A] = Direct {
      m.acquire()
      try fa.run
      finally m.release()
    }

    // Core monadic operations
    def pure[A](a: A): Direct[A]                                   = Direct.pure(a)
    def flatMap[A, B](fa: Direct[A])(f: A => Direct[B]): Direct[B] = fa.flatMap(f)
    def map[A, B](fa: Direct[A])(f: A => B): Direct[B]             = fa.map(f)

    // Error handling
    def raiseError[A](e: Throwable): Direct[A]                                     = Direct { throw e }
    def handleErrorWith[A](fa: => Direct[A])(f: Throwable => Direct[A]): Direct[A] = Direct {
      try fa.run
      catch { case e: Throwable => f(e).run }
    }

    // Time operations
    def sleep(duration: FiniteDuration): Direct[Unit] = Direct {
      ox.sleep(duration)
    }

    // Suspension
    def delay[A](a: => A): Direct[A] = Direct(a)

    // Concurrency primitives
    def ref[A](initial: A): Direct[WRef[Direct, A]] = Direct {
      val underlying = new AtomicReference[A](initial)
      new WRef[Direct, A] {
        def get: Direct[A]                       = Direct(underlying.get())
        def set(a: A): Direct[Unit]              = Direct(underlying.set(a))
        def update(f: A => A): Direct[Unit]      = Direct {
          var updated = false
          while !updated do {
            val current = underlying.get()
            updated = underlying.compareAndSet(current, f(current))
          }
        }
        def modify[B](f: A => (A, B)): Direct[B] = Direct {
          var result: B = null.asInstanceOf[B]
          var updated   = false
          while !updated do {
            val current   = underlying.get()
            val (newA, b) = f(current)
            if underlying.compareAndSet(current, newA) then {
              result = b
              updated = true
            }
          }
          result
        }
        def getAndUpdate(f: A => A): Direct[A]   = Direct {
          var old: A  = null.asInstanceOf[A]
          var updated = false
          while !updated do {
            old = underlying.get()
            updated = underlying.compareAndSet(old, f(old))
          }
          old
        }
      }
    }

    /** Start a background computation. Note: This executes immediately in Direct style since Ox structured concurrency requires being in a supervised
      * block. The returned fiber is already completed.
      */
    def start[A](fa: Direct[A]): Direct[WFiber[Direct, A]] = Direct {
      // In direct style, we execute immediately and return a completed fiber
      // For true background execution, the workflow engine should use ox.supervised
      val result =
        try Outcome.Succeeded(fa.run)
        catch { case e: Throwable => Outcome.Errored(e) }
      new WFiber[Direct, A] {
        def cancel: Direct[Unit]     = Direct(())
        def join: Direct[Outcome[A]] = Direct(result)
      }
    }

    def guaranteeCase[A](fa: Direct[A])(finalizer: Outcome[A] => Direct[Unit]): Direct[A] = Direct {
      val outcome =
        try Outcome.Succeeded(fa.run)
        catch { case e: Throwable => Outcome.Errored(e) }

      finalizer(outcome).run

      outcome match {
        case Outcome.Succeeded(a) => a
        case Outcome.Errored(e)   => throw e
        case Outcome.Canceled     => throw new InterruptedException("Canceled")
      }
    }
  }

  /** UnsafeRun instance for Direct. Simply runs the computation since Direct is already synchronous.
    */
  given directUnsafeRun: UnsafeRun[Direct] = new UnsafeRun[Direct] {
    def unsafeRunSync[A](fa: Direct[A]): A = fa.run
  }
}
