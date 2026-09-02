package com.decentralchain.state.diffs

import cats.syntax.either.*
import com.decentralchain.consensus.GeneratingBalanceProvider
import com.decentralchain.crypto.bls.BlsUtils
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.*
import com.decentralchain.transaction.CommitToGenerationTransaction
import com.decentralchain.transaction.TxValidationError.{ActivationError, GenericError}

object CommitToGenerationTransactionDiff {
  def apply(blockchain: Blockchain)(tx: CommitToGenerationTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress

    for {
      current <- blockchain.currentGenerationPeriod.toRight(ActivationError("DeterministicFinality is not yet activated"))
      next = current.next
      _ <- Either.raiseUnless(tx.generationPeriodStart == next.start) {
        GenericError(s"Expected the next period start height (${next.start}), got ${tx.generationPeriodStart}")
      }
      _ <- Either.raiseUnless(
        BlsUtils
          .verifyBasic(
            tx.commitmentSignature.arr,
            tx.endorserPublicKey.arr ++ tx.generationPeriodStart.toByteArray,
            tx.endorserPublicKey.arr,
            BlsUtils.BlsDomainSeparationTag
          )
          .isRight
      )(GenericError("Invalid commitment signature"))
      // Full curve validation (in-group, not point-at-infinity) at the actual enforcement point: once,
      // when the key is trusted going forward as a committed generator -- not on every deserialization.
      _ <- tx.endorserPublicKey.validated.leftMap(GenericError(_))
      _ <- blockchain.committedGenerators(next).foldLeft(Either.unit[GenericError]) {
        case (r @ Left(_), _)          => r
        case (Right(_), (addr, blsPk)) =>
          if (addr == sender) GenericError(s"$sender is already committed").asLeft
          else if (blsPk == tx.endorserPublicKey) GenericError(s"BLS key ${tx.endorserPublicKey} is already committed, try another key").asLeft
          else ().asRight
      }
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(
          sender -> Portfolio(
            balance = -tx.fee.value
            // generationDeposit = ??? // We don't need this, because calculate from nextCommittedGenerators
          )
        ),
        nextCommittedGenerators = Seq(tx.sender -> tx.endorserPublicKey)
      )
      generatingBalanceAfterDeposit = SnapshotBlockchain(blockchain, snapshot).generatingBalance(sender)
      minMiningBalance = GeneratingBalanceProvider.minMiningBalance(blockchain, Height(blockchain.height))
      _ <- Either.raiseUnless(generatingBalanceAfterDeposit >= minMiningBalance)(
        GenericError(
          s"Generating balance $generatingBalanceAfterDeposit is less than $minMiningBalance required for block generation"
        )
      )
    } yield snapshot
  }
}
