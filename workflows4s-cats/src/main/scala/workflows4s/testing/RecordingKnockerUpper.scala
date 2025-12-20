package workflows4s.testing

import cats.Id
import cats.effect.IO
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.instanceengine.Effect

/** IO-based RecordingKnockerUpper for cats-effect.
  *
  * This is a convenience type alias and factory for creating RecordingKnockerUpper instances with IO effect type and the ability to create Id views
  * for synchronous test execution.
  */
object CatsRecordingKnockerUpper {

  /** Create a new IO-based RecordingKnockerUpper that can also provide an Id view */
  def apply(): workflows4s.testing.RecordingKnockerUpper[IO] = {
    workflows4s.testing.RecordingKnockerUpper[IO]
  }

  extension (ku: workflows4s.testing.RecordingKnockerUpper[IO]) {

    /** Create an Id-based view of this knocker upper, sharing the same state. Convenience method for cats-effect tests.
      */
    def asId: workflows4s.testing.RecordingKnockerUpper[Id] = {
      given Effect[Id] = Effect.idEffect
      ku.as[Id]
    }
  }
}
