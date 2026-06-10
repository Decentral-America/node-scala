package com.decentralchain.state.patch

import cats.implicits.catsSyntaxSemigroup
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.state.patch.CancelAllLeases.CancelledLeases
import com.decentralchain.state.{Blockchain, StateSnapshot}

case object CancelLeaseOverflow extends PatchAtHeight() {
  def apply(blockchain: Blockchain): StateSnapshot = {
    val patch = readPatchData[CancelledLeases]()
    StateSnapshot.ofLeaseBalances(patch.balances, blockchain).explicitGet() |+| StateSnapshot(cancelledLeases = patch.leaseStates)
  }
}
