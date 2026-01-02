package workflows4s.testing

trait Runner[F[_]] {
  def run[A](fa: F[A]): A
}
