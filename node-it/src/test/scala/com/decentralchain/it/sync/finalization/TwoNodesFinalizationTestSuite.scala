package com.decentralchain.it.sync.finalization

import com.typesafe.config.Config
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.api.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{BaseFreeSpec, Node, NodeConfigs}
import com.decentralchain.state.Height
import com.decentralchain.test.NumericExt
import com.decentralchain.utils.ScorexLogging
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

class TwoNodesFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging, HotStuffCommitOps {
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

  override protected def commitTargets: Seq[(Node, String)] = Seq((node1, miner1Addr), (node2, miner2Addr))
  override protected def commitLeader: Node                 = node1

  "finalization activated and works" in {
    step("Commit to generation (rollover-hardened)")
    val generators = commitAllForNextPeriod()

    step("Generators")
    isolated {
      generators.size shouldBe 2
      // Assert the committed addresses (the consensus-relevant identity), not exact (address, txId)
      // pairs: the hardened commit helper can legitimately re-sign/retry across a period rollover,
      // so the locally-signed transaction id from a single attempt is no longer guaranteed to match.
      generators.map(_.address) should contain theSameElementsAs Seq(miner1Addr, miner2Addr)
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
