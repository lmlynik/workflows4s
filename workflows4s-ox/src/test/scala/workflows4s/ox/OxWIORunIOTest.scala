package workflows4s.ox

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.instanceengine.{Effect, UnsafeRun}
import workflows4s.testing.WIORunIOTestSuite

class OxWIORunIOTest extends AnyFreeSpec with WIORunIOTestSuite[Direct] {

  override def effectInstance: Effect[Direct]       = OxEffect.directEffect
  override def unsafeRunInstance: UnsafeRun[Direct] = OxEffect.directUnsafeRun

  "WIO.RunIO (OX)" - {
    wioRunIOTests()
  }
}
