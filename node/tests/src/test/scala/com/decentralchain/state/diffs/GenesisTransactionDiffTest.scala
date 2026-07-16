package com.decentralchain.state.diffs

import com.decentralchain.db.WithDomain
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.RideV6
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.TxHelpers.{defaultAddress, genesis}
import org.scalatest.exceptions.TestFailedException

class GenesisTransactionDiffTest extends PropSpec with WithDomain {
  property("fails if height != 1") {
    withDomain(RideV6) { d =>
      d.appendAndAssertSucceed(genesis(defaultAddress))
      d.appendBlockE(genesis(defaultAddress)) should produce("GenesisTransaction cannot appear in non-initial block")
    }
  }

  property("StateSnapshot establishes Dcc invariant") {
    val genesis = (1 to 10).map(idx => TxHelpers.genesis(TxHelpers.address(idx), 10000))
    assertDiffAndState(Seq.empty, TestBlock.create(genesis)) { (blockDiff, _) =>
      blockDiff.balances.collect {
        case ((_, Dcc), amount) => amount
        case ((_, asset), _)    => throw new TestFailedException(s"unexpected $asset", 0)
      }.sum shouldBe genesis.map(_.amount.value).sum
      blockDiff.leaseBalances shouldBe empty
      genesis.foreach { gtx =>
        blockDiff.balances((gtx.recipient, Dcc)) shouldBe gtx.amount.value
      }
    }
  }
}
