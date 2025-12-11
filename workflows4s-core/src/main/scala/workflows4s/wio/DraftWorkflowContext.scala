package workflows4s.wio

import workflows4s.effect.Effect

/** A dummy WorkflowContext used for draft workflows that are never executed.
  * The effect type is Nothing since draft operations are never actually run.
  */
object DraftWorkflowContext extends WorkflowContext {
  type F[A] = Nothing

  given effect: Effect[F] = new Effect[F] {
    def pure[A](a: A): F[A]                                       = ???
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]               = ???
    def map[A, B](fa: F[A])(f: A => B): F[B]                      = ???
    def raiseError[A](e: Throwable): F[A]                         = ???
    def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A]  = ???
    def sleep(duration: scala.concurrent.duration.FiniteDuration): F[Unit] = ???
    def realTimeInstant: F[java.time.Instant]                     = ???
    def delay[A](a: => A): F[A]                                   = ???
    def liftIO[A](io: cats.effect.IO[A]): F[A]                    = ???
    def toIO[A](fa: F[A]): cats.effect.IO[A]                      = ???
  }
}
