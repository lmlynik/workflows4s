package workflows4s.example.pekko

import java.util.UUID
import scala.concurrent.Future
import workflows4s.example.withdrawal.checks.Check
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalService}

class FutureDummyWithdrawalService extends WithdrawalService[Future] {

  override def calculateFees(amount: BigDecimal): Future[WithdrawalService.Fee] =
    Future.successful(WithdrawalService.Fee(amount * 0.1))

  override def putMoneyOnHold(amount: BigDecimal): Future[Either[WithdrawalService.NotEnoughFunds, Unit]] =
    Future.successful(
      if amount > 1000 then Left(WithdrawalService.NotEnoughFunds())
      else Right(()),
    )

  override def initiateExecution(amount: BigDecimal, recepient: WithdrawalService.Iban): Future[WithdrawalService.ExecutionResponse] = {
    val hasUnwantedDecimals = amount.setScale(2) != amount
    Future.successful(
      if hasUnwantedDecimals then WithdrawalService.ExecutionResponse.Rejected("Invalid precision! Only 2 decimal places expected.")
      else WithdrawalService.ExecutionResponse.Accepted(UUID.randomUUID().toString),
    )
  }

  override def releaseFunds(amount: BigDecimal): Future[Unit] = Future.successful(())

  override def cancelFundsLock(): Future[Unit] = Future.successful(())

  override def getChecks(): List[Check[Future, WithdrawalData.Validated]] = List()
}

object FutureDummyWithdrawalService {
  def apply(): FutureDummyWithdrawalService = new FutureDummyWithdrawalService
}
