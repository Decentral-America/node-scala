package com.decentralchain.it.sync.transactions

import com.decentralchain.common.state.ByteStr
import com.decentralchain.it.IntegrationSuiteWithThreeAddresses
import com.decentralchain.it.sync.calcDataFee
import com.decentralchain.state.BinaryDataEntry

import scala.concurrent.duration.*

trait OverflowBlock { self: IntegrationSuiteWithThreeAddresses =>
  // Hack to bypass instant micro mining
  def overflowBlock(): Unit = {
    import com.decentralchain.it.api.SyncHttpApi.*
    val entries = List.tabulate(4)(n => BinaryDataEntry(n.toString, ByteStr(Array.fill(32724)(n.toByte))))
    val fee     = calcDataFee(entries, 1)
    for (i <- 1 to 8) {
      sender.putData(sender.keyPair, entries, fee + i)
    }
    sender.waitFor("empty utx")(n => n.utxSize, (utxSize: Int) => utxSize == 0, 100.millis)
  }
}
