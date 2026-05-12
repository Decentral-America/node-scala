package com.decentralchain.state.diffs

import com.decentralchain.lang.ValidationError
import com.decentralchain.state.*
import com.decentralchain.transaction.lease.*

object LeaseTransactionsDiff {
  def lease(blockchain: Blockchain)(tx: LeaseTransaction): Either[ValidationError, StateSnapshot] =
    DiffsCommon
      .processLease(blockchain, tx.amount, tx.sender, tx.recipient, tx.fee.value, tx.id(), TransactionId(tx.id()))

  def leaseCancel(blockchain: Blockchain, time: Long)(tx: LeaseCancelTransaction): Either[ValidationError, StateSnapshot] =
    DiffsCommon
      .processLeaseCancel(blockchain, tx.sender, tx.fee.value, time, tx.leaseId, tx.id())
}
