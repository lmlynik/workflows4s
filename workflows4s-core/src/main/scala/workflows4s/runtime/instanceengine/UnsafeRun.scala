package workflows4s.runtime.instanceengine

/** Typeclass for effects that can be executed synchronously (blocking). Used for integrations like Quartz that require synchronous callbacks.
  */
trait UnsafeRun[F[_]] {

  /** Execute an effect synchronously, blocking until completion. Throws exceptions on failure.
    */
  def unsafeRunSync[A](fa: F[A]): A
}

object UnsafeRun {

  def apply[F[_]](using u: UnsafeRun[F]): UnsafeRun[F] = u

  /** UnsafeRun for cats.Id (trivial - already synchronous)
    */
  given idUnsafeRun: UnsafeRun[cats.Id] = new UnsafeRun[cats.Id] {
    def unsafeRunSync[A](fa: cats.Id[A]): A = fa
  }
}
