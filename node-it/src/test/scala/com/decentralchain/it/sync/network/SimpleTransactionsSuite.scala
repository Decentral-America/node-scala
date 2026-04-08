package com.decentralchain.it.sync.network

import com.typesafe.config.Config
import com.decentralchain.account.Address
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.api.AsyncNetworkApi.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.network.{RawBytes, TransactionSpec}
import com.decentralchain.transaction.Asset.Waves
import com.decentralchain.transaction.TxHelpers

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class SimpleTransactionsSuite extends BaseTransactionSuite {
  import com.decentralchain.it.NodeConfigs.*
  override val nodeConfigs: Seq[Config] = Seq(BiggestMiner.quorum(0))

  private def node = nodes.head

  test("valid tx send by network to node should be in blockchain") {
    val tx = TxHelpers.transfer(node.keyPair, Address.fromString(node.address).explicitGet(), 1L, Waves, minFee, Waves)

    node.sendByNetwork(RawBytes.fromTransaction(tx))
    node.waitForTransaction(tx.id().toString)

  }

  test("invalid tx send by network to node should be not in UTX or blockchain") {
    val tx = TxHelpers.transfer(
      node.keyPair,
      Address.fromString(node.address).explicitGet(),
      1L,
      Waves,
      minFee,
      Waves,
      timestamp = System.currentTimeMillis() + (1 days).toMillis
    )

    node.sendByNetwork(RawBytes.fromTransaction(tx))
    val maxHeight = nodes.map(_.height).max
    nodes.waitForHeight(maxHeight + 1)
    node.ensureTxDoesntExist(tx.id().toString)
  }

  test("should blacklist senders of non-parsable transactions") {
    val blacklistBefore = node.blacklistedPeers
    node.sendByNetwork(RawBytes(TransactionSpec.messageCode, "foobar".getBytes(StandardCharsets.UTF_8)))
    node.waitForBlackList(blacklistBefore.size)
  }
}
