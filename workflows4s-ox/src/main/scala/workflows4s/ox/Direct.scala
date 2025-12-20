package workflows4s.ox

/** A wrapper type for direct-style computations that enables integration with workflows4s Effect typeclass.
  *
  * Ox uses direct style without a wrapper type, but the Effect typeclass requires F[_] (a type constructor). This opaque type provides a thin wrapper
  * that captures lazy computations while maintaining direct-style semantics.
  *
  * The computation is not executed until `run` is called.
  */
opaque type Direct[+A] = () => A

object Direct {

  /** Create a Direct computation from a by-name thunk */
  def apply[A](thunk: => A): Direct[A] = () => thunk

  /** Create a Direct computation that immediately returns a value */
  def pure[A](a: A): Direct[A] = () => a

  extension [A](d: Direct[A]) {

    /** Execute the computation and return the result */
    def run: A = d()

    /** Transform the result of this computation */
    def map[B](f: A => B): Direct[B] = () => f(d())

    /** Sequence this computation with another */
    def flatMap[B](f: A => Direct[B]): Direct[B] = () => f(d()).run
  }
}
