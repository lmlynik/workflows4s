package workflows4s.effect

import cats.effect.IO
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Effect instance for cats.effect.IO */
object CatsEffect {
  given Effect[IO] = new Effect[IO] {
    def pure[A](a: A): IO[A]                                       = IO.pure(a)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]             = fa.flatMap(f)
    def map[A, B](fa: IO[A])(f: A => B): IO[B]                     = fa.map(f)
    def raiseError[A](e: Throwable): IO[A]                         = IO.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.handleErrorWith(f)
    def sleep(duration: FiniteDuration): IO[Unit]                  = IO.sleep(duration)
    def realTimeInstant: IO[Instant]                               = IO.realTime.map(d => Instant.ofEpochMilli(d.toMillis))
    def delay[A](a: => A): IO[A]                                   = IO.delay(a)
    def liftIO[A](io: IO[A]): IO[A]                                = io
    def toIO[A](fa: IO[A]): IO[A]                                  = fa
  }
}
