package com.decentralchain.utx

import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.StateSnapshot
import com.decentralchain.transaction.Transaction

final class UtxPriorityPool {

  @volatile private var priorityTxIds = Seq.empty[ByteStr]

  def priorityTransactionIds: Seq[ByteStr] = priorityTxIds

  private[utx] def setPriorityDiffs(discDiffs: Seq[StateSnapshot]): Set[Transaction] = {
    priorityTxIds = discDiffs.flatMap(_.transactions.keys)
    discDiffs.flatMap(_.transactions.values.map(_.transaction)).toSet
  }
}
