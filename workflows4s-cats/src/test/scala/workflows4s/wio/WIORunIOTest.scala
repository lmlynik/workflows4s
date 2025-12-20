package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.runtime.instanceengine.{Effect, UnsafeRun}
import workflows4s.testing.WIORunIOTestSuite

class WIORunIOTest extends AnyFreeSpec with WIORunIOTestSuite[IO] {

  override def effectInstance: Effect[IO]       = CatsEffect.ioEffect
  override def unsafeRunInstance: UnsafeRun[IO] = CatsEffect.ioUnsafeRun

  "WIO.RunIO" - {
    wioRunIOTests()
  }
}
