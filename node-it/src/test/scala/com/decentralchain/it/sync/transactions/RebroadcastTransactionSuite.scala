package com.decentralchain.it.sync.transactions

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory.parseString
import com.decentralchain.account.Address
import com.decentralchain.api.http.ApiError.CustomValidationError
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.it.Node
import com.decentralchain.it.NodeConfigs.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.sync.*
import com.decentralchain.it.transactions.{BaseTransactionSuite, NodesFromDocker}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.TxHelpers

class RebroadcastTransactionSuite extends BaseTransactionSuite with NodesFromDocker {

  import RebroadcastTransactionSuite.*

  override protected def nodeConfigs: Seq[Config] =
    Seq(configWithRebroadcastAllowed.withFallback(Miners.head), configWithRebroadcastAllowed.withFallback(NotMiner))

  private def nodeAIsMiner: Node    = nodes.head
  private def nodeBIsNotMiner: Node = nodes.last

  test("should rebroadcast a transaction if that's allowed in config") {
    val tx = TxHelpers
      .transfer(
        nodeAIsMiner.keyPair,
        Address.fromString(nodeBIsNotMiner.address).explicitGet(),
        transferAmount,
        Dcc,
        minFee,
        Dcc
      )
      .json()

    val dockerNodeAId = docker.stopContainer(dockerNodes().head)
    val txId          = nodeBIsNotMiner.signedBroadcast(tx).id
    docker.startContainer(dockerNodeAId)
    nodeBIsNotMiner.waitForPeers(1)

    nodeAIsMiner.ensureTxDoesntExist(txId)
    nodeBIsNotMiner.signedBroadcast(tx)
    nodeAIsMiner.waitForUtxIncreased(0)
    nodeAIsMiner.utxSize shouldBe 1
  }

  test("should not rebroadcast a transaction if that's not allowed in config") {
    dockerNodes().foreach(docker.restartNode(_, configWithRebroadcastNotAllowed))

    val tx = TxHelpers
      .transfer(
        nodeAIsMiner.keyPair,
        Address.fromString(nodeBIsNotMiner.address).explicitGet(),
        transferAmount,
        Dcc,
        minFee,
        Dcc
      )
      .json()

    val dockerNodeAId = docker.stopContainer(dockerNodes().head)
    val txId          = nodeBIsNotMiner.signedBroadcast(tx).id
    docker.startContainer(dockerNodeAId)
    nodeBIsNotMiner.waitForPeers(1)

    nodeAIsMiner.ensureTxDoesntExist(txId)
    nodeBIsNotMiner.signedBroadcast(tx)
    nodes.waitForHeightArise()
    nodeAIsMiner.utxSize shouldBe 0
    nodeAIsMiner.ensureTxDoesntExist(txId)
  }

  test("should not broadcast a transaction if there are not enough peers") {
    val tx = TxHelpers
      .transfer(
        nodeAIsMiner.keyPair,
        Address.fromString(nodeBIsNotMiner.address).explicitGet(),
        transferAmount,
        Dcc,
        minFee,
        Dcc
      )
      .json()

    val testNode = dockerNodes().last
    try {
      docker.restartNode(testNode, configWithMinimumPeers(999))
      assertApiError(
        testNode.signedBroadcast(tx),
        CustomValidationError("There are not enough connections with peers \\(\\d+\\) to accept transaction").assertiveRegex
      )
    } finally {
      docker.restartNode(testNode, configWithMinimumPeers(0))
    }
  }
}
object RebroadcastTransactionSuite {
  private val configWithRebroadcastAllowed =
    parseString("dcc.synchronization.utx-synchronizer.allow-tx-rebroadcasting = true")

  private val configWithRebroadcastNotAllowed =
    parseString("dcc.synchronization.utx-synchronizer.allow-tx-rebroadcasting = false")

  private def configWithMinimumPeers(n: Int) =
    parseString(s"dcc.rest-api.minimum-peers = $n")
}
