package com.decentralchain.state.diffs

import cats.syntax.either.*
import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.{Blockchain, LeaseBalance, StateSnapshot}
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}
import com.decentralchain.transaction.CommitToGenerationTransaction.DepositInDcclets
import com.decentralchain.transaction.TxValidationError.AccountBalanceError

import scala.util.{Left, Right}

object BalanceDiffValidation {
  def cond(b: Blockchain, cond: Blockchain => Boolean)(s: StateSnapshot): Either[AccountBalanceError, StateSnapshot] = {
    if (cond(b)) apply(b)(s)
    else Right(s)
  }

  def apply(b: Blockchain)(snapshot: StateSnapshot): Either[AccountBalanceError, StateSnapshot] = {
    def checkDcc(
        acc: Address,
        wavesAfter: Long,
        leaseAfter: LeaseBalance,
        additionalDeposit: Long
    ): Either[(Address, String), Unit] = {
      val wavesBefore   = b.balance(acc)
      val depositBefore = b.generationDeposit(acc)
      val leaseBefore   = b.leaseBalance(acc)

      val depositAfter             = depositBefore + additionalDeposit
      val wavesWithoutDepositAfter = wavesAfter - depositAfter

      val leaseOutDiff = leaseAfter.out - leaseBefore.out

      @inline def ifNotZero(label: String, value: Long): String = if (value == 0) "" else s", $label=$value"
      @inline def balancesStr(waves: Long, lease: LeaseBalance, deposit: Long): String =
        s"spendable=${waves - lease.out - deposit}" + ifNotZero("waves", waves) + ifNotZero("lease", lease.out) + ifNotZero("deposit", deposit)

      lazy val stateChanges =
        s"before: ${balancesStr(wavesBefore, leaseBefore, depositBefore)}, after: ${balancesStr(wavesAfter, leaseAfter, depositAfter)}"

      val errorMessage =
        if (wavesAfter < 0) s"negative waves balance: before=$wavesBefore, after=$wavesAfter".asLeft
        else if (wavesWithoutDepositAfter < 0) {
          if (depositAfter > depositBefore) s"not enough funds for deposit, $stateChanges".asLeft
          else s"trying to spend a deposit, $stateChanges".asLeft
        } else if (wavesWithoutDepositAfter < leaseAfter.out && b.height > b.settings.functionalitySettings.allowLeasedBalanceTransferUntilHeight) {
          if (wavesWithoutDepositAfter + leaseAfter.in - leaseAfter.out < 0) s"negative effective balance, $stateChanges".asLeft
          else if (leaseOutDiff == 0) s"trying to spend leased money, $stateChanges".asLeft
          else s"leased being more than own, $stateChanges".asLeft
        } else if (wavesWithoutDepositAfter - leaseAfter.out < 0 && depositBefore > 0)
          s"trying to spend either a deposit or leased money, $stateChanges".asLeft
        else Either.unit

      errorMessage.leftMap(err => acc -> s"$err")
    }

    val dccCheck =
      snapshot.balances
        .flatMap {
          case ((address, Dcc), balance) =>
            val currentLeaseBalance = snapshot.leaseBalances.getOrElse(address, b.leaseBalance(address))
            val depositedOnNext = DepositInDcclets *
              snapshot.nextCommittedGenerators.find { case (pk, _) => pk.toAddress == address }.size
            checkDcc(address, balance, currentLeaseBalance, depositedOnNext).fold(error => List(error), _ => Nil)
          case _ =>
            Nil
        }

    val assetsCheck =
      snapshot.balances
        .collectFirst {
          case ((address, asset), balance) if asset != Dcc && balance < 0 =>
            Map(address -> s"negative asset balance: $address, new portfolio: ${negativeAssetsInfo(address, snapshot)}")
        }
        .getOrElse(Map())

    val positiveBalanceErrors = dccCheck ++ assetsCheck
    if (positiveBalanceErrors.isEmpty) Right(snapshot)
    else Left(AccountBalanceError(positiveBalanceErrors))
  }

  private def negativeAssetsInfo(
      address: Address,
      snapshot: StateSnapshot
  ): Map[ByteStr, Long] =
    snapshot.balances
      .collect {
        case ((`address`, assetId: IssuedAsset), balance) if balance < 0 => (assetId.id, balance)
      }
}
