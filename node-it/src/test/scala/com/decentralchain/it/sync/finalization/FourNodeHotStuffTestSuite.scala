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

/** Step-5 runtime validation of the T2 HotStuff 4c-bind wiring on a real multi-node cluster.
  *
  * Brings up 4 nodes with `dcc.hotstuff.enabled = true` (feature 25 pre-activated) and asserts the
  * chain stays HEALTHY with the coordinator running: finality keeps advancing on EVERY node and none
  * halts or forks. This is the smoke-level gate that the 4c-bind Application wiring (inbound routing,
  * pacemaker timer, propose-if-forger hook) doesn't break block production / finality / consensus.
  *
  * NOTE: HotStuff commit is currently observational (feature-25 `finalizedHeight` stays authoritative),
  * so this validates that enabling HotStuff is non-destructive. Deeper HotStuff-specific checks
  * (fast-finality latency via an exposed `hotStuffFinalizedHeight`, crashed-leader view change,
  * equivocation) build on this once observability is exposed — see docs/hotstuff-integration-design.md.
  */
class FourNodeHotStuffTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.DeterministicFinality.id, Height(0))))
      .overrideBase(_.raw("dcc.miner.minimal-block-generation-offset = 10s"))
      .overrideBase(_.raw("dcc.hotstuff.enabled = true"))
      .overrideBase(_.raw("dcc.hotstuff.round-timeout = 1200ms"))
      .withDefault(4)
      .buildNonConflicting()

  private def hsNodes = dockerNodes()
  private def leader  = hsNodes.head

  "a HotStuff-enabled 4-node cluster finalizes on every node without halting or forking" in {
    val period = leader.currentGenerationPeriod.value.next

    step("Commit all 4 generators to the next period")
    val commits = hsNodes.map(n => n.sign(CommitToGenerationRequest(sender = Some(n.address))))
    hsNodes.foreach(n => commits.foreach(n.broadcastRequest))
    hsNodes.foreach(_.waitForGenerationPeriod(period))

    step("All 4 generators are committed")
    isolated {
      leader.generators(period.start).size shouldBe 4
    }

    step("Finality advances on every node with HotStuff enabled")
    val deadline = 4.minutes.fromNow
    val start    = leader.finalizedHeight
    val target   = start + 2
    var done     = false
    while (!done && deadline.hasTimeLeft()) {
      // one tx per round -> microblock -> endorsement voting -> finalization progresses
      leader.transfer(leader.keyPair, hsNodes(1).address, 1.dcc, waitForTx = true)
      val finalizedHeights = hsNodes.map(_.finalizedHeight)
      finalizedHeights.foreach(fh => if (fh < start) fail(s"finalized height regressed below $start: got $fh"))
      done = finalizedHeights.forall(_ >= target)
    }
    if (!done)
      fail(s"HotStuff-enabled cluster did not finalize to $target within the deadline; per-node finalized=${hsNodes.map(_.finalizedHeight)}")
  }
}
