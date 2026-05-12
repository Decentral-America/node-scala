package com.decentralchain.it.sync.network

import java.nio.charset.StandardCharsets

import com.typesafe.config.Config
import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.AsyncNetworkApi.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.transactions.BaseTransactionSuite
import com.decentralchain.network.{RawBytes, TransactionSpec}
import com.decentralchain.transaction.Asset.Waves
import com.decentralchain.transaction.transfer.*

import scala.concurrent.duration.*

class SimpleTransactionsSuite extends BaseTransactionSuite {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(0))
      .withDefault(entitiesNumber = 1)
      .buildNonConflicting()

  private def node = nodes.head

  test("valid tx send by network to node should be in blockchain") {
    val tx = TransferTransaction
      .selfSigned(
        1.toByte,
        node.keyPair,
        Address.fromString(node.address).explicitGet(),
        Waves,
        1L,
        Waves,
        minFee,
        ByteStr.empty,
        System.currentTimeMillis()
      )
      .explicitGet()

    node.sendByNetwork(RawBytes.fromTransaction(tx))
    node.waitForTransaction(tx.id().toString)

  }

  test("invalid tx send by network to node should be not in UTX or blockchain") {
    val tx = TransferTransaction
      .selfSigned(
        1.toByte,
        node.keyPair,
        Address.fromString(node.address).explicitGet(),
        Waves,
        1L,
        Waves,
        minFee,
        ByteStr.empty,
        System.currentTimeMillis() + (1 days).toMillis
      )
      .explicitGet()

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
