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
  * Brings up 4 nodes with `dcc.hotstuff.enabled = true` (feature 25 pre-activated) and exercises the
  * cluster under three conditions, all with HotStuff running:
  *   1. happy path — finality advances on every node without halting or forking;
  *   2. crashed generator — safety holds while a generator is down, and it re-syncs on restart;
  *   3. network partition — no divergent finality, and the cluster reconverges after healing.
  *
  * HotStuff commit is currently observational (feature-25 `finalizedHeight` stays authoritative), so
  * these suites validate that enabling HotStuff is non-destructive AND preserves feature-25's BFT
  * safety under real faults. Assertions are deliberately restricted to deterministic SAFETY invariants
  * (finalized height is monotonic; the cluster reconverges on one chain; a recovered node re-syncs) —
  * NOT liveness-during-fault thresholds, which depend on the committed-stake distribution and generation
  * period boundaries and would be flaky. Liveness/view-change is covered by the pure-core unit tests and
  * by scenario (1). Byzantine equivocation is covered adversarially at the unit level
  * (`HotStuffSafety.equivocators`); a node-it double-vote test needs a fault-injection node build and is
  * tracked as future work in docs/hotstuff-integration-design.md.
  *
  * These are resource-sensitive: run on a host where node-it has real memory headroom (CI ubuntu-latest),
  * not a memory-pressured laptop where the peer mesh fragments regardless of HotStuff.
  */
class FourNodeHotStuffTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.DeterministicFinality.id, Height(0))))
      .overrideBase(_.raw("dcc.miner.minimal-block-generation-offset = 10s"))
      .overrideBase(_.raw("dcc.miner.quorum = 1")) // 2+ nodes still produce blocks -> survives one down
      .overrideBase(_.raw("dcc.hotstuff.enabled = true"))
      .overrideBase(_.raw("dcc.hotstuff.round-timeout = 1200ms"))
      .withDefault(4)
      .buildNonConflicting()

  private def hsNodes = dockerNodes()
  private def leader  = hsNodes.head

  /** Commit every node as a generator for the next period and wait until that period is active.
    * Returns the committed generators (address + generating balance) for that period.
    */
  private def commitAllForNextPeriod(): Seq[GeneratorsResponse.Entry] = {
    val period  = leader.currentGenerationPeriod.value.next
    val commits = hsNodes.map(n => n.sign(CommitToGenerationRequest(sender = Some(n.address))))
    hsNodes.foreach(n => commits.foreach(n.broadcastRequest))
    hsNodes.foreach(_.waitForGenerationPeriod(period))
    leader.generators(period.start)
  }

  /** The committed generator with the smallest generating balance — crashing/partitioning it removes the
    * least committed stake, so the surviving set retains the most quorum weight.
    */
  private def smallestStakeNode(generators: Seq[GeneratorsResponse.Entry]) =
    hsNodes.minBy(n => generators.find(_.address == n.address).map(_.balance).getOrElse(Long.MaxValue))

  "T2 HotStuff on a real 4-node cluster" - {

    "finalizes on every node without halting or forking" in {
      commitAllForNextPeriod()
      val start    = leader.finalizedHeight
      val target   = start + 2
      val deadline = 4.minutes.fromNow
      var done     = false
      while (!done && deadline.hasTimeLeft()) {
        // one tx per round -> microblock -> endorsement voting -> finalization progresses
        leader.transfer(leader.keyPair, hsNodes(1).address, 1.dcc, waitForTx = true)
        val fhs = hsNodes.map(_.finalizedHeight)
        fhs.foreach(fh => if (fh < start) fail(s"finalized height regressed below $start: got $fh"))
        done = fhs.forall(_ >= target)
      }
      if (!done)
        fail(s"HotStuff-enabled cluster did not finalize to $target within the deadline; per-node finalized=${hsNodes.map(_.finalizedHeight)}")
    }

    "keeps safety when a generator crashes, and the node re-syncs on restart" in {
      val generators = commitAllForNextPeriod()
      val victim     = smallestStakeNode(generators)
      val survivors  = hsNodes.filterNot(_ == victim)

      val finalizedFloor = survivors.map(_.finalizedHeight).min
      val heightBefore   = survivors.map(_.height).max
      log.info(s"Crashing generator ${victim.name} (smallest committed stake); survivors=${survivors.map(_.name)}")

      val victimId = docker.stopContainer(victim)

      // SAFETY + block production: survivors keep producing (quorum=1) and NEVER regress finalized height.
      val deadline  = 3.minutes.fromNow
      var producing = false
      while (!producing && deadline.hasTimeLeft()) {
        survivors.foreach(n => n.transfer(n.keyPair, survivors.head.address, 1.dcc, waitForTx = true))
        survivors.map(_.finalizedHeight).foreach(fh => if (fh < finalizedFloor) fail(s"finalized regressed during crash: $fh < $finalizedFloor"))
        producing = survivors.map(_.height).max >= heightBefore + 2
      }
      if (!producing) fail(s"survivors stopped producing blocks after crash: heights=${survivors.map(_.height)}")

      // RECOVERY: restart the crashed node; the whole cluster must reconverge on identical headers.
      docker.startContainer(victimId)
      val tip = survivors.map(_.height).max
      assert(nodes.waitForSameBlockHeadersAt(tip), s"cluster did not reconverge at height $tip after ${victim.name} restarted")
    }

    "keeps safety across a network partition (no divergent finality)" in {
      val generators      = commitAllForNextPeriod()
      val victim          = smallestStakeNode(generators)
      val majority        = hsNodes.filterNot(_ == victim)
      val finalizedBefore = hsNodes.map(n => n.name -> n.finalizedHeight).toMap

      log.info(s"Partitioning ${victim.name} away from the majority=${majority.map(_.name)}")
      docker.disconnectFromNetwork(victim)

      // Majority keeps producing while the minority is isolated; its finalized height must not regress.
      val majFloor  = majority.map(_.finalizedHeight).min
      val majBefore = majority.map(_.height).max
      val deadline  = 3.minutes.fromNow
      var advanced  = false
      while (!advanced && deadline.hasTimeLeft()) {
        majority.foreach(n => n.transfer(n.keyPair, majority.head.address, 1.dcc, waitForTx = true))
        majority.map(_.finalizedHeight).foreach(fh => if (fh < majFloor) fail(s"majority finalized regressed during partition: $fh < $majFloor"))
        advanced = majority.map(_.height).max >= majBefore + 2
      }
      if (!advanced) fail(s"majority stopped producing during partition: heights=${majority.map(_.height)}")

      // HEAL: reconnect the minority; the whole cluster must reconverge on ONE chain (no permanent fork).
      docker.connectToNetwork(Seq(victim))
      val tip = majority.map(_.height).max
      assert(nodes.waitForSameBlockHeadersAt(tip), s"cluster did not reconverge at height $tip after healing the partition")

      // SAFETY invariant: no node's finalized height went backwards across the whole episode.
      hsNodes.foreach { n =>
        val after = n.finalizedHeight
        if (after < finalizedBefore(n.name)) fail(s"${n.name} finalized height regressed across partition: $after < ${finalizedBefore(n.name)}")
      }
    }
  }
}
