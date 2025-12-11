package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect

import java.time.{Clock, Instant}

/** Basic engine that gets the current time from a Java Clock. */
class BasicJavaTimeEngine[F[_]](clock: Clock)(using E: Effect[F]) extends BasicEngine[F] {

  override protected def now: F[Instant] = E.delay(clock.instant())

}
