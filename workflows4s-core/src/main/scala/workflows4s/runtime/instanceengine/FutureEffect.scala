package workflows4s.runtime.instanceengine

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success}

object FutureEffect {

  given futureEffect(using ec: ExecutionContext): Effect[Future] =
    new Effect[Future] {

      type Mutex = Semaphore

      def createMutex: Future[Mutex] = Future.successful(new Semaphore(1))

      def withLock[A](m: Mutex)(fa: => Future[A]): Future[A] = {
        // 1. Acquire lock asynchronously on the blocking context
        Future(blocking(m.acquire()))
          .flatMap { _ =>
            // 2. Execute the effect
            fa.transformWith { result =>
              // 3. Ensure release happens regardless of Success/Failure
              m.release()
              Future.fromTry(result)
            }
          }
      }

      def pure[A](a: A): Future[A]                                                   = Future.successful(a)
      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B]                 = fa.flatMap(f)
      def map[A, B](fa: Future[A])(f: A => B): Future[B]                             = fa.map(f)
      def raiseError[A](e: Throwable): Future[A]                                     = Future.failed(e)
      def handleErrorWith[A](fa: => Future[A])(f: Throwable => Future[A]): Future[A] =
        fa.recoverWith { case e => f(e) }

      def sleep(duration: scala.concurrent.duration.FiniteDuration): Future[Unit] =
        Future(blocking(Thread.sleep(duration.toMillis)))

      def delay[A](a: => A): Future[A] = Future(a)

      // Simplified Ref using Java 8+ AtomicReference functional API
      def ref[A](initial: A): Future[Ref[Future, A]] = Future.successful(new Ref[Future, A] {
        private val underlying = new AtomicReference[A](initial)

        def get: Future[A]          = Future.successful(underlying.get())
        def set(a: A): Future[Unit] = Future.successful(underlying.set(a))

        def update(f: A => A): Future[Unit] = Future.successful {
          underlying.updateAndGet(x => f(x))
          ()
        }

        def modify[B](f: A => (A, B)): Future[B] = Future.successful {
          // AtomicReference doesn't have a direct "modify returning B"
          // so we use a CAS loop here or accumulateAndGet with a tuple hack.
          // The manual loop is clearest for this specific signature.
          var result: B = null.asInstanceOf[B]
          underlying.updateAndGet { current =>
            val (next, b) = f(current)
            result = b // Side-effecting inside update is safe here as it runs on the calling thread
            next
          }
          result
        }

        def getAndUpdate(f: A => A): Future[A] = Future.successful(underlying.getAndUpdate(x => f(x)))
      })

      def start[A](fa: Future[A]): Future[Fiber[Future, A]] = {
        // A Promise to represent the Outcome, separate from the running Future
        val promise = Promise[Outcome[A]]()

        fa.onComplete {
          case Success(a) => promise.trySuccess(Outcome.Succeeded(a))
          case Failure(e) => promise.trySuccess(Outcome.Errored(e))
        }

        Future.successful(new Fiber[Future, A] {
          // Future cannot be canceled, but we can simulate the signal
          def cancel: Future[Unit]     = Future.successful {
            val _ = promise.trySuccess(Outcome.Canceled)
            ()
          }
          def join: Future[Outcome[A]] = promise.future
        })
      }

      def guaranteeCase[A](fa: Future[A])(finalizer: Outcome[A] => Future[Unit]): Future[A] = {
        fa.transformWith { result =>
          val outcome = result match {
            case Success(a) => Outcome.Succeeded(a)
            case Failure(e) => Outcome.Errored(e)
          }
          finalizer(outcome).flatMap { _ =>
            Future.fromTry(result)
          }
        }
      }
    }
}
