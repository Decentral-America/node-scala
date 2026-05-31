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
        newDcc: Long,
        newLease: LeaseBalance,
        additionalDeposit: Long
    ): Either[(Address, String), Unit] = {
      val oldDcc   = b.balance(acc)
      val oldDeposit = b.generationDeposit(acc)
      val oldLease   = b.leaseBalance(acc)

      val newDeposit          = oldDeposit + additionalDeposit
      val newDccWithDeposit = newDcc - newDeposit

      val leaseOutDiff = newLease.out - oldLease.out

      val stateChanges = s"old: w=$oldDcc, $oldLease, d=$oldDeposit, new: w=$newDcc, $newLease, d=$newDeposit"

      val errorMessage =
        if (newDcc < 0) s"negative dcc balance: $acc, old: $oldDcc, new: $newDcc".asLeft
        else if (newDccWithDeposit < 0) {
          if (newDeposit > oldDeposit) s"$acc not enough funds for deposit, $stateChanges".asLeft
          else s"$acc trying to spend a deposit, $stateChanges".asLeft
        } else if (newDccWithDeposit < newLease.out && b.height > b.settings.functionalitySettings.allowLeasedBalanceTransferUntilHeight) {
          if (newDccWithDeposit + newLease.in - newLease.out < 0) s"negative effective balance: $acc, $stateChanges".asLeft
          else if (leaseOutDiff == 0) s"$acc trying to spend leased money".asLeft
          else s"leased being more than own: $acc, $stateChanges".asLeft
        } else Either.unit

      errorMessage.leftMap(acc -> _)
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

    val positiveBalanceErrors =
      dccCheck ++ assetsCheck

    if (positiveBalanceErrors.isEmpty) {
      Right(snapshot)
    } else {
      Left(AccountBalanceError(positiveBalanceErrors))
    }
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
