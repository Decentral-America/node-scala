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
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

/** Shared 4-node HotStuff cluster bring-up + helper vocabulary (`nodeConfigs`, `hsNodes`, `leader`,
  * `commitAllForNextPeriod`, `smallestStakeNode`), factored out of what used to be a single concrete
  * `FourNodeHotStuffTestSuite` so a second chaos scenario (`DegradedLinkHotStuffTestSuite`, the
  * degraded-link/ToxiProxy suite) can reuse the exact same cluster setup and vocabulary WITHOUT also
  * inheriting -- and therefore re-running -- `FourNodeHotStuffTestSuite`'s own three "it" cases as a side
  * effect of plain Scala class inheritance. `FourNodeHotStuffTestSuite` below and
  * `DegradedLinkHotStuffTestSuite` both extend this instead of one extending the other.
  */
abstract class HotStuffFourNodeSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.DeterministicFinality.id, Height(0))))
      .overrideBase(_.raw("dcc.miner.minimal-block-generation-offset = 10s"))
      .overrideBase(_.raw("dcc.miner.quorum = 1")) // 2+ nodes still produce blocks -> survives one down
      .overrideBase(_.raw("dcc.hotstuff.enabled = true"))
      .overrideBase(_.raw("dcc.hotstuff.round-timeout = 1200ms"))
      .withDefault(4)
      .buildNonConflicting()

  protected def hsNodes = dockerNodes()
  protected def leader  = hsNodes.head

  /** Commit every not-yet-committed node as a generator for the next period and wait until that period
    * is active. Returns the committed generators (address + generating balance) for that period.
    *
    * Idempotent and race-safe against two distinct sources of "already in the state" rejections that
    * both used to fail the whole test outright:
    *
    *   1. Across "it" blocks: this suite reuses the same 4 live containers for all three tests (no
    *      reset in between), and a CommitToGenerationTransaction is deterministic per (sender,
    *      generationPeriodStart) -- re-signing produces a byte-identical transaction. If a PRIOR "it"
    *      block already committed everyone for this same upcoming period (plausible: committing itself
    *      takes far less time than a full period, so the "next" period computed here can be the SAME
    *      one an earlier block already secured), re-broadcasting would resubmit an already-included
    *      transaction. Fixed by checking who's already committed (via /generators/at) before signing.
    *
    *   2. WITHIN one call, on a real P2P-connected 4-node cluster: broadcasting every commit to every
    *      node (as this originally did) is redundant -- one broadcast is enough for gossip to reach the
    *      whole cluster -- and that redundancy created a genuine race: by the time a LATER redundant
    *      broadcast of the SAME transaction reached some node, gossip from an EARLIER broadcast (to a
    *      different node) had often already gotten that transaction included in a block, so the node
    *      correctly rejected the redundant copy. Fixed by broadcasting each commit exactly once, from
    *      its own signing node, and treating a same-transaction "already in the state" rejection on
    *      that single broadcast as benign (the transaction ending up included via some other path is
    *      exactly the outcome we want) rather than a real failure.
    *
    *   3. Across the WHOLE call, on a loaded CI runner: `period` is computed once up front, but signing
    *      and broadcasting one commit per not-yet-committed node is real wall-clock time. If that adds up
    *      to a full period length before the last node's broadcast lands, the chain has already rolled
    *      over to the period after the one this call targeted, and `CommitToGenerationTransactionDiff`
    *      correctly rejects the now-stale `generationPeriodStart` with "Expected the next period start
    *      height (X), got Y" -- a real, observed 400 on CI, distinct from both races above (it's neither
    *      an already-committed sender nor an already-included transaction). Fixed by treating that
    *      specific rejection as a signal to retry the whole selection against the freshly-current period
    *      rather than a failure: any node that already got included under the old period will show up in
    *      the next attempt's `alreadyCommitted` and won't be resubmitted.
    */
  protected def commitAllForNextPeriod(retriesLeft: Int = 2): Seq[GeneratorsResponse.Entry] = {
    val period = leader.currentGenerationPeriod.value.next
    // /generators/at/{h} 404s until the chain has actually reached height h (the route's own guard is
    // period-based and would allow it, but the underlying height genuinely doesn't exist yet on the
    // FIRST call in the suite, before anyone has committed or waited for anything) -- a 404 here means
    // "nothing possibly committed there yet" exactly as much as an empty list would, so treat it the same.
    val alreadyCommitted =
      try leader.generators(period.start).map(_.address).toSet
      catch { case ApiCallException(e: UnexpectedStatusCodeException) if e.statusCode == StatusCodes.NotFound.intValue => Set.empty[String] }
    val toCommit         = hsNodes.filterNot(n => alreadyCommitted.contains(n.address))
    val periodRolledOver = toCommit.exists { n =>
      val commit = n.sign(CommitToGenerationRequest(sender = Some(n.address)))
      try {
        n.broadcastRequest(commit)
        false
      } catch {
        case ApiCallException(e: UnexpectedStatusCodeException)
            if e.statusCode == StatusCodes.BadRequest.intValue && e.responseBody.contains("already in the state") =>
          log.info(
            s"CommitToGeneration for ${n.address} raced with a concurrent inclusion (benign, transaction ${commit.id} is in the state): skipping"
          )
          false
        case ApiCallException(e: UnexpectedStatusCodeException)
            if e.statusCode == StatusCodes.BadRequest.intValue && e.responseBody.contains("Expected the next period start height") =>
          log.info(
            s"CommitToGeneration for ${n.address} targeted period ${period.start}, which already rolled over while committing the batch: retrying"
          )
          true
      }
    }
    if (periodRolledOver) {
      require(retriesLeft > 0, s"commitAllForNextPeriod: generation period kept rolling over past all retries")
      commitAllForNextPeriod(retriesLeft - 1)
    } else {
      hsNodes.foreach(_.waitForGenerationPeriod(period))
      leader.generators(period.start)
    }
  }

  /** The committed generator with the smallest generating balance — crashing/partitioning it removes the
    * least committed stake, so the surviving set retains the most quorum weight.
    */
  protected def smallestStakeNode(generators: Seq[GeneratorsResponse.Entry]) =
    hsNodes.minBy(n => generators.find(_.address == n.address).map(_.balance).getOrElse(Long.MaxValue))
}

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
class FourNodeHotStuffTestSuite extends HotStuffFourNodeSuite {
  "T2 HotStuff on a real 4-node cluster" - {

    "finalizes on every node without halting or forking" in {
      val start    = leader.finalizedHeight
      val target   = start + 2
      val deadline = 4.minutes.fromNow
      var done     = false
      while (!done && deadline.hasTimeLeft()) {
        // Re-commit every round, not just once up front. `generation-period-length` is only 3
        // blocks in node-it (template.conf), so a single commit's committee is live for ~3 blocks
        // and then goes empty at the period boundary -- after which every node logs "Generator set
        // is empty, don't collect endorsements" and finalization stalls permanently. Confirmed
        // directly from a failing run's node logs: the committee finalized up to HotStuff height 3
        // during its one committed period, then emptied and never recovered, leaving T0
        // finalizedHeight pinned. This is by design -- the live testnet runs an
        // auto-commit-generators cron that re-commits every period for exactly this reason -- so
        // the test must keep the committee populated across period rollovers the same way.
        // commitAllForNextPeriod is idempotent (skips already-committed generators) and tolerant of
        // period rollover mid-batch, so calling it each round is safe and cheap.
        commitAllForNextPeriod()
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
