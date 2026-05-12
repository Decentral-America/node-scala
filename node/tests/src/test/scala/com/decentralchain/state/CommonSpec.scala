package com.decentralchain.state

import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.TxHelpers

class CommonSpec extends FreeSpec with WithDomain {

  "Common Conditions" - {
    "Zero balance of absent asset" in {
      val sender         = TxHelpers.signer(1)
      val initialBalance = 1000
      val assetId        = Array.fill(32)(1.toByte)

      withDomain(balances = Seq(AddrWithBalance(sender.toAddress, initialBalance))) { d =>
        d.balance(sender.toAddress, IssuedAsset(ByteStr(assetId))) shouldEqual 0L
      }
    }
  }
}
