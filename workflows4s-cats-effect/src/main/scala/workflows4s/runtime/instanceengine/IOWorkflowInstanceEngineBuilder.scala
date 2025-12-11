package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.effect.CatsEffect.given
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Clock

/** IO-specific builder for constructing WorkflowInstanceEngine with wakeups and logging support. */
object IOWorkflowInstanceEngineBuilder {

  def withJavaTimeIO(clock: Clock = Clock.systemUTC()): IOStep1 = IOStep1(new BasicJavaTimeEngine[IO](clock))

  class IOStep1(val get: WorkflowInstanceEngine[IO]) {

    def withWakeUps(knockerUpper: KnockerUpper.Agent[IO]) = IOStep2(new WakingWorkflowInstanceEngine(get, knockerUpper))
    def withoutWakeUps                                    = IOStep2(get)

    class IOStep2(val get: WorkflowInstanceEngine[IO]) {

      def withRegistering(registry: WorkflowRegistry.Agent[IO]) = IOStep3(new RegisteringWorkflowInstanceEngine[IO](get, registry))
      def withoutRegistering                                    = IOStep3(get)

      class IOStep3(val get: WorkflowInstanceEngine[IO]) {

        def withGreedyEvaluation     = IOStep4(new GreedyWorkflowInstanceEngine[IO](get))
        def withSingleStepEvaluation = IOStep4(get)

        class IOStep4(val get: WorkflowInstanceEngine[IO]) {

          def withLogging    = IOStep5(new LoggingWorkflowInstanceEngine(get))
          def withoutLogging = IOStep5(get)

          class IOStep5(val get: WorkflowInstanceEngine[IO]) {}

        }

      }

    }

  }

}

/** Backwards-compatible alias - use IOWorkflowInstanceEngineBuilder for new code. */
object WorkflowInstanceEngineBuilder {
  export IOWorkflowInstanceEngineBuilder.{withJavaTimeIO, IOStep1}
}
