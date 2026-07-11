package com.decentralchain.it.sync.finalization

import com.typesafe.config.Config
import com.decentralchain.api.http.requests.CommitToGenerationRequest
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.api.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{BaseFreeSpec, NodeConfigs}
import com.decentralchain.state.Height
import com.decentralchain.test.NumericExt
import com.decentralchain.utils.ScorexLogging
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

class TwoNodesFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.DeterministicFinality.id, Height(0))))
      .overrideBase(_.raw("dcc.miner.minimal-block-generation-offset = 10s"))
      .withDefault(2)
      .buildNonConflicting()

  private def node1 = dockerNodes().head
  private def node2 = dockerNodes().last

  private lazy val miner1Acc  = node1.keyPair
  private lazy val miner1Addr = node1.address

  private lazy val miner2Addr = node2.address

  "finalization activated and works" in {
    val period1 = node1.currentGenerationPeriod.value.next

    step("Commit to generation")
    val commitTxn1 = node1.sign(CommitToGenerationRequest(sender = Some(miner1Addr)))
    val commitTxn2 = node2.sign(CommitToGenerationRequest(sender = Some(miner2Addr)))
    Seq(node1, node2).foreach { node =>
      node.broadcastRequest(commitTxn1)
      node.broadcastRequest(commitTxn2)
    }
    node1.waitForGenerationPeriod(period1)
    node2.waitForGenerationPeriod(period1)

    step("Generators")
    isolated {
      val generators = node1.generators(period1.start)
      generators.size shouldBe 2
      // Assert the committed identities (address + commit tx), not exact balances: the generating
      // balance drifts with block rewards/fees between runs, so hardcoded magic numbers are brittle.
      generators.map(e => (e.address, e.transactionId)) should contain theSameElementsAs Seq(
        (miner1Addr, commitTxn1.id),
        (miner2Addr, commitTxn2.id)
      )
      all(generators.map(_.balance)) should be > 0L
    }

    step("Finalized height checks")
    val deadline               = 2.minutes.fromNow
    var finalizedHeight1       = node1.finalizedHeight
    val waitingFinalizedHeight = finalizedHeight1 + 2

    var done = false
    while (!done && deadline.hasTimeLeft()) {
      val currHeight = node1.height
      if (currHeight > waitingFinalizedHeight + 2)
        fail(
          s"Finalization height doesn't rise: height=$currHeight, waiting for finalized height=$waitingFinalizedHeight, last finalized height=$finalizedHeight1"
        )

      // We need at least one transaction, otherwise there won't be a microblock, thus no voting, no finalization
      node1.transfer(miner1Acc, miner2Addr, 1.dcc, waitForTx = true)

      val updatedFinalizedHeight = node1.finalizedHeight
      if (updatedFinalizedHeight < finalizedHeight1)
        fail(s"Finalized height $updatedFinalizedHeight became lower than the previous $finalizedHeight1")
      else if (updatedFinalizedHeight != finalizedHeight1)
        log.debug(s"New finalized height: $finalizedHeight1 -> $updatedFinalizedHeight")

      finalizedHeight1 = updatedFinalizedHeight
      done = finalizedHeight1 >= waitingFinalizedHeight
    }
  }
}
