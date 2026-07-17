package com.decentralchain.it.sync.finalization

import com.typesafe.config.Config
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.it.api.*
import com.decentralchain.it.api.SyncHttpApi.*
import com.decentralchain.it.{BaseFreeSpec, NodeConfigs}
import com.decentralchain.test.NumericExt
import com.decentralchain.utils.ScorexLogging
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

/** Regression guard for the endorsement-rebroadcast fix (node-scala feat/endorsement-rebroadcast).
  *
  * feature-25 finality is miner-aggregated with fire-once endorsements. With a SINGLE aggregator finality is
  * continuous; with MULTIPLE generators forging the aggregator role rotates every block and a one-shot
  * endorsement can miss whichever node is currently mining, so finalizedHeight STALLS (this is what stalled the
  * live testnet when all 3 gens forged on plain be2dcfc0). The rebroadcast patch periodically re-emits a node's
  * own current-height endorsement so the rotated aggregator still receives it, keeping finality tight.
  *
  * This suite runs THREE nodes that ALL forge (quorum=1), drives txs round-robin so blocks rotate through every
  * generator, and asserts finalizedHeight keeps ADVANCING and the finality lag stays BOUNDED. Expected:
  *   - WITHOUT the rebroadcast patch: finalizedHeight stalls -> this suite FAILS (reproduces the bug).
  *   - WITH the patch: finalizedHeight advances, lag bounded -> this suite PASSES.
  */
class NNodesRotatingFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  import NodeConfigs.*

  // Three forging generators (all mine). Same feature/quorum setup as TwoNodesFinalizationTestSuite.
  override protected def nodeConfigs: Seq[Config] =
    Seq(Miners.head, Miners(1), Miners(2)).map(
      _.preactivatedFeatures(BlockchainFeatures.DeterministicFinality)
        .overrides("waves.dcc.blockchain.custom.functionality.min-block-time = 10s")
        .quorum(1)
    )

  private def node1    = nodes.head
  private def accounts = nodes.map(_.keyPair)
  private def addrs    = nodes.map(_.address)

  "finality stays tight while ALL generators forge (rotating aggregator)" in {
    val period1 = node1.currentGenerationPeriod.value.next

    step("Commit all generators to generation")
    val commitTxns = nodes.map(n => n.signCommitToGenerationRequest(n.address))
    commitTxns.foreach(node1.broadcastRequest(_))

    node1.waitForGenerationPeriod(period1)

    step("All 3 generators committed")
    isolated {
      val generators = node1.generators(period1.start)
      generators.size shouldBe nodes.size
      generators.map(_.address) should contain theSameElementsAs addrs
    }

    step("Finality must keep advancing while all 3 forge; lag stays bounded")
    // With 3 rotating aggregators, plain be2dcfc0 stalls here; the rebroadcast patch keeps it advancing.
    val deadline           = 5.minutes.fromNow
    val advancesRequired   = 5                 // finalizedHeight must rise this many times
    val maxLagAllowed      = 250               // matches the FinalizationStalled alert threshold
    var lastFinalized      = node1.finalizedHeight
    val startFinalized     = lastFinalized
    var i                  = 0

    while (node1.finalizedHeight < startFinalized + advancesRequired && deadline.hasTimeLeft()) {
      // Round-robin the sender so blocks rotate through every generator (need a tx for a microblock -> voting).
      val sender = accounts(i % accounts.size)
      val recip  = addrs((i + 1) % addrs.size)
      node1.transfer(sender, recip, 1.dcc, waitForTx = true)
      i += 1

      val h   = node1.height
      val fin = node1.finalizedHeight
      val lag = h - fin
      if (fin < lastFinalized)
        fail(s"finalizedHeight went backwards: $lastFinalized -> $fin")
      if (lag > maxLagAllowed)
        fail(s"finality lag $lag exceeded $maxLagAllowed (height=$h finalized=$fin) — finality is stalling under rotation")
      lastFinalized = fin
    }

    withClue("finalizedHeight did not advance enough within the deadline — finality stalled under rotation: ") {
      node1.finalizedHeight should be >= (startFinalized + advancesRequired)
    }
  }
}
