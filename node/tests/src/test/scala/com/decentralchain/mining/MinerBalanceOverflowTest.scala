package com.decentralchain.mining

import com.decentralchain.db.WithDomain
import com.decentralchain.test.*
import com.decentralchain.transaction.TxHelpers
import org.scalatest.matchers.should.Matchers

class MinerBalanceOverflowTest extends FlatSpec with Matchers with WithDomain {
  "Miner balance" should "not overflow" in withDomain(DomainPresets.RideV4WithRewards) { d =>
    d.helpers.creditDccToDefaultSigner(Long.MaxValue - 6.dcc)
    d.appendBlockE() should produce("Dcc balance sum overflow")
    val minerBalance = d.blockchain.balance(TxHelpers.defaultAddress)
    minerBalance shouldBe >(0L)
  }
}
