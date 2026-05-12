package com.decentralchain.state.patch

import com.decentralchain.account.Address
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.state.*

case object CancelInvalidLeaseIn extends PatchAtHeight('W' -> 1060000) {
  def apply(blockchain: Blockchain): StateSnapshot =
    StateSnapshot.ofLeaseBalances(readPatchData[Map[Address, LeaseBalance]](), blockchain).explicitGet()
}
