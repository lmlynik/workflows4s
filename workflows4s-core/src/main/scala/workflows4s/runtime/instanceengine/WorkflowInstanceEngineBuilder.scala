package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect
import workflows4s.runtime.registry.WorkflowRegistry

import java.time.Clock

/** Builder for constructing WorkflowInstanceEngine with various configurations.
  *
  * For IO-specific features (wakeups, logging), use IOWorkflowInstanceEngineBuilder from workflows4s-cats-effect.
  */
object WorkflowInstanceEngineBuilder {

  def withJavaTime[F[_]](clock: Clock = Clock.systemUTC())(using E: Effect[F]) = Step1(new BasicJavaTimeEngine[F](clock))

  class Step1[F[_]: Effect](val get: WorkflowInstanceEngine[F]) {

    def withoutWakeUps = Step2(get)

    class Step2(val get: WorkflowInstanceEngine[F]) {

      def withRegistering(registry: WorkflowRegistry.Agent[F]) = Step3(new RegisteringWorkflowInstanceEngine[F](get, registry))
      def withoutRegistering                                   = Step3(get)

      class Step3(val get: WorkflowInstanceEngine[F]) {

        def withGreedyEvaluation     = Step4(new GreedyWorkflowInstanceEngine[F](get))
        def withSingleStepEvaluation = Step4(get)

        class Step4(val get: WorkflowInstanceEngine[F]) {

          def withoutLogging = Step5(get)

          class Step5(val get: WorkflowInstanceEngine[F]) {}

        }

      }

    }

  }

}
