package workflows4s.example.withdrawal

import io.circe.{Codec, Decoder}
import workflows4s.example.withdrawal.WithdrawalService.Iban

object WithdrawalSignal {

  case class CreateWithdrawal(txId: String, amount: BigDecimal, recipient: Iban) derives Decoder

  sealed trait ExecutionCompleted derives Codec.AsObject
  object ExecutionCompleted {
    case object Succeeded extends ExecutionCompleted
    case object Failed    extends ExecutionCompleted
  }

  case class CancelWithdrawal(operatorId: String, comment: String, acceptStartedExecution: Boolean) derives Decoder
}
