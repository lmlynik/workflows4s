package workflows4s.zio

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.instanceengine.{Effect, UnsafeRun}
import workflows4s.testing.WIORunIOTestSuite
import zio.Task

class ZIOWIORunIOTest extends AnyFreeSpec with WIORunIOTestSuite[Task] {

  override def effectInstance: Effect[Task]       = ZIOEffect.taskEffect
  override def unsafeRunInstance: UnsafeRun[Task] = ZIOEffect.taskUnsafeRun

  "WIO.RunIO (ZIO)" - {
    wioRunIOTests()
  }
}
