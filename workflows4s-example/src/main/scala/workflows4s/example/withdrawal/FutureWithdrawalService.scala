package workflows4s.example.withdrawal

import scala.concurrent.Future
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban, NotEnoughFunds}
import workflows4s.example.withdrawal.checks.Check

/** Future-based version of WithdrawalService for use with FutureEffect.
  */
trait FutureWithdrawalService {
  def calculateFees(amount: BigDecimal): Future[Fee]

  def putMoneyOnHold(amount: BigDecimal): Future[Either[NotEnoughFunds, Unit]]

  def initiateExecution(amount: BigDecimal, recepient: Iban): Future[ExecutionResponse]

  def releaseFunds(amount: BigDecimal): Future[Unit]

  def cancelFundsLock(): Future[Unit]

  def getChecks(): List[Check[WithdrawalData.Validated]]
}
