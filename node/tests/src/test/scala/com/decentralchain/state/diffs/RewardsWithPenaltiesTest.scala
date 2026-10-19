package com.decentralchain.state.diffs

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.state.{Portfolio, StateSnapshot}
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers

class RewardsWithPenaltiesTest extends FreeSpec with WithDomain {
  private val acc = TxHelpers.signer(1)

  "a reward that exactly cancels the penalty leaves NO balance entry (same as the appender's netting)" in
    withDomain(DomainPresets.TransactionStateSnapshot, AddrWithBalance.enoughBalances(acc)) { d =>
      val a = acc.toAddress
      val r = BlockDiffer.rewardsWithPenalties(d.blockchain, StateSnapshot.empty, Map(a -> Portfolio.dcc(100)), Map(a -> Portfolio.dcc(-100))).explicitGet()
      r.balances shouldBe empty
    }

  "non-cancelling amounts net into one entry" in
    withDomain(DomainPresets.TransactionStateSnapshot, AddrWithBalance.enoughBalances(acc)) { d =>
      val a = acc.toAddress
      val r = BlockDiffer.rewardsWithPenalties(d.blockchain, StateSnapshot.empty, Map(a -> Portfolio.dcc(140)), Map(a -> Portfolio.dcc(-100))).explicitGet()
      r.balances.toMap shouldBe Map((a, Dcc) -> (d.blockchain.balance(a) + 40))
    }
}
