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

  // Three forging generators (all mine). Like TwoNodesFinalizationTestSuite but with a LONGER generation
  // period: committing 3 generators takes a few blocks to all mine, and the default period length of 3 is
  // too short — some commits land in the next period and get attributed one period late (flaky 0 generators).
  // A length-20 period with 3s blocks leaves ample room for all 3 commits to mine within one period.
  override protected def nodeConfigs: Seq[Config] =
    Seq(Miners.head, Miners(1), Miners(2)).map(
      _.preactivatedFeatures(BlockchainFeatures.DeterministicFinality)
        .overrides("dcc.blockchain.custom.functionality.min-block-time = 3s")
        .overrides("dcc.blockchain.custom.functionality.generation-period-length = 20")
        .quorum(1)
    )

  private def node1    = nodes.head
  private def accounts = nodes.map(_.keyPair)
  private def addrs    = nodes.map(_.address)

  "finality stays tight while ALL generators forge (rotating aggregator)" in {
    // A commit is attributed to the period AFTER the one it is mined in ("a generator can commit only
    // for a next period", Keys.committedGenerators). Committing 3 generators near a period boundary
    // races: some commits land in the next period and get attributed one period too late. So first
    // advance to a fresh period boundary, THEN commit — all commits mine within that full period and
    // are attributed to the same following period.
    step("Advance to a fresh generation-period boundary before committing")
    node1.waitForGenerationPeriod(node1.currentGenerationPeriod.value.next)
    val committedPeriod = node1.currentGenerationPeriod.value.next

    step("Commit all generators to generation")
    val commitTxns = nodes.map(n => n.signCommitToGenerationRequest(n.address))
    commitTxns.foreach(node1.broadcastRequest(_))
    commitTxns.foreach(t => node1.waitForTransaction(t.id)) // ensure all commits are mined this period

    node1.waitForGenerationPeriod(committedPeriod)

    step("All 3 generators committed")
    isolated {
      val generators = node1.generators(committedPeriod.start)
      generators.size shouldBe nodes.size
      generators.map(_.address) should contain theSameElementsAs addrs
    }

    step("Finality must keep advancing while all 3 forge; lag stays bounded")
    // With 3 rotating aggregators, plain be2dcfc0 stalls here; the rebroadcast patch keeps it advancing.
    // The committee is committed for one generation period (~60s), so a few advances within it is the signal;
    // require modestly and fail fast if it stalls.
    val deadline              = 3.minutes.fromNow
    val advancesRequired      = 3                 // finalizedHeight must rise this many times
    val maxLagAllowed         = 250               // matches the FinalizationStalled alert threshold
    val maxFinalityRegression = 16                // benign NG tip-replacement jitter is ~1 block; a bigger drop
                                                  // below the high-water mark = a real deep reversion (<< maxRollback 100)
    val startFinalized        = node1.finalizedHeight
    var maxFinalized          = startFinalized     // finality high-water mark
    var i                     = 0

    while (maxFinalized < startFinalized + advancesRequired && deadline.hasTimeLeft()) {
      // Round-robin the sender so blocks rotate through every generator (need a tx for a microblock -> voting).
      val sender = accounts(i % accounts.size)
      val recip  = addrs((i + 1) % addrs.size)
      node1.transfer(sender, recip, 1.dcc, waitForTx = true)
      i += 1

      val h   = node1.height
      val fin = node1.finalizedHeight
      val lag = h - fin
      // The reported finalized height is chain-tip-relative and can dip a block or two on an NG liquid-block
      // replacement, then re-advance (rebroadcast re-delivers the endorsements) — that is expected. Only a
      // DEEP reversion (un-finalizing a semi-buried block) or a STALL is a real failure.
      if (fin < maxFinalized - maxFinalityRegression)
        fail(s"DEEP finality reversion: finalized $fin dropped ${maxFinalized - fin} below high-water $maxFinalized (height=$h)")
      if (lag > maxLagAllowed)
        fail(s"finality lag $lag exceeded $maxLagAllowed (height=$h finalized=$fin) — finality is stalling under rotation")
      if (fin > maxFinalized) maxFinalized = fin
    }

    withClue("finalizedHeight did not advance enough within the deadline — finality stalled under rotation: ") {
      maxFinalized should be >= (startFinalized + advancesRequired)
    }
  }
}
