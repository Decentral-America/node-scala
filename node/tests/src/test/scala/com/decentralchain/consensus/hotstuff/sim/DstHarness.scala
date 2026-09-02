package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffAction, HotStuffCoordinator, HotStuffEffects, HotStuffEquivocationProof}
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}

import scala.collection.mutable

/** One committed-block observation, recorded for later invariant checking by [[SafetyInvariants]]. */
final case class CommitObservation(node: Int, blockId: BlockId, height: Int, at: SimTime)

/** One vote-cast observation: every `HotStuffVote` a node actually broadcast, captured off the wire.
  * Feeds [[SafetyInvariants.noEquivocation]] (audit F-2) -- the harness previously recorded only
  * commits, so no DST scenario could ever have observed a double-signed `(view, phase)`.
  */
final case class VoteObservation(node: Int, vote: HotStuffVote, at: SimTime)

/** Deterministic in-process HotStuff cluster simulation: `nodeCount` real `HotStuffCoordinator.Enabled`
  * instances (unmodified production consensus code) wired over a fault-injecting [[SimNetwork]] and
  * driven by a [[SimClock]]. No threads, no wall clock, no real networking — everything is reproducible
  * from `seed`. See the scope limitation note in this task's plan entry regarding restart/resync.
  */
final class DstHarness(
    seed: Long,
    nodeCount: Int,
    faultProfile: FaultProfile = FaultProfile(),
    // T10-liveness root-cause fix (2026-08-04): a PURE function of a vote's target height, shared
    // identically by every node in this harness -- exactly the shape of `Application.scala`'s
    // production `committeeEpochOf` (`blockchain.generationPeriodOf(h).index`). Defaults to a constant
    // `0`, matching every existing DST scenario/test that doesn't pass this and preserving prior
    // behaviour byte-for-byte. See `DstCommitteeEpochRotationScenarioSpecification` for a scenario that
    // exercises a real, height-driven committee-epoch rotation with this wired to a genuinely
    // height-varying function.
    committeeEpochOf: Int => Int = _ => 0,
    // Task 4 (wedged-committee watchdog) additive hook: fired as `(node, action)` for every action
    // reported by that node's `HotStuffCoordinator.Enabled.onAction` (see that class's doc for exactly
    // what counts). Defaults to a no-op so every existing scenario spec that doesn't pass this (all of
    // them as of this change -- see the call sites above) observes byte-for-byte the same behaviour as
    // before this parameter existed. `HotStuffWatchdogDstReproductionSpecification` is the one scenario
    // that supplies a real function here, wiring each node's progress signal to its own per-node
    // `HotStuffWatchdog` instance.
    onAction: (Int, HotStuffAction) => Unit = (_, _) => (),
    // Audit F-2 additive hook: the per-node `proposalValid` chain-membership guard, mirroring
    // production's `Application.scala` wiring (`blockchainUpdater.heightOf(blockId).isDefined` --
    // "is this a real block on MY OWN chain?"). Called as `(node, blockId)`. Defaults to the harness's
    // historical `_ => true`, so every pre-existing scenario keeps byte-for-byte prior behaviour; the
    // audit's point is precisely that a permissive default cannot exercise the realistic case, so
    // `HotStuffWatchdogInFlightResetScenarioSpecification` supplies a genuinely chain-backed one.
    proposalValid: (Int, BlockId) => Boolean = (_, _) => true,
    // F-6 fix additive hook (docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md, audit
    // F-6 "self-sealing epoch trap"): the max-target-lag bound wired to EVERY node's
    // `HotStuffCoordinator.Enabled.maxTargetLag`, mirroring production's single Application.scala-wide
    // setting. Defaults to `Int.MaxValue`, matching `HotStuffCoordinator.Enabled`'s own default -- i.e.
    // the lag check is unconditionally a no-op for every existing scenario spec that doesn't pass this,
    // preserving byte-for-byte prior behaviour. `DstStaleTargetSelfSealScenarioSpecification` is the one
    // scenario that supplies a finite bound here.
    maxTargetLag: () => Int = () => Int.MaxValue
) {
  val clock: SimClock                                = new SimClock(seed)
  val commits: mutable.ListBuffer[CommitObservation] = mutable.ListBuffer.empty
  // Audit F-2: every vote every node broadcasts, in delivery order. Recorded unconditionally for ALL
  // scenarios (the append is O(1) per vote and nothing reads it unless a spec asks), so any scenario
  // can run `SafetyInvariants.noEquivocation` without opting in to extra harness wiring.
  val votes: mutable.ListBuffer[VoteObservation] = mutable.ListBuffer.empty

  private val network                 = new SimNetwork[Message](clock, nodeCount, faultProfile)
  private val kps                     = (0 until nodeCount).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private var committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val live         = mutable.Set.from(0 until nodeCount)
  private val heightOfView = mutable.Map.empty[Int, Int]
  // F-6 fix: blockId -> height, populated by `leaderTurn` and `setCommittedTipAll`. `heightOfView`
  // alone is not enough for any scenario that exercises the SELF-DRIVEN re-propose path: when a node
  // re-anchors through `onRoundTimerTick` -> `onLeaderTurn`, it proposes at a view the harness never
  // registered a height for, so `deliver` fell back to `0` and every receiving replica saw height 0 --
  // maximally stale under any advanced tip, so the re-anchored round could never complete and no
  // scenario could observe a successful re-anchor at all. Keying by blockId fixes that: a block's
  // height is a property of the BLOCK, not of the view it happens to be proposed in (and production's
  // `onProposal` height likewise comes from `blockchainUpdater.heightOf(blockId)`, not from the view).
  private val heightOfBlock = mutable.Map.empty[BlockId, Int]
  // Every node's OWN "currently-believed-active committee epoch" -- the DST-harness equivalent of
  // production's `committeeEpoch` closure (`blockchainUpdater.currentGenerationPeriod`, read fresh from
  // each node's OWN live chain tip). Used ONLY for `HotStuffEngine.onQC`/`onProposal`'s transition-
  // gating decision (`HotStuffQuorum.acceptableCommitteeEpoch`), never for what a node signs (that is
  // `committeeEpochOf` above).
  //
  // F-6 fix (audit "self-sealing epoch trap"): this used to be ONE shared `var`, updated only via
  // `advanceEpochBelief` -- adequate for exercising the epoch-rotation TRANSITION-GATING rule
  // (`DstCommitteeEpochRotationScenarioSpecification`), but structurally incapable of expressing "one
  // node's own tip has diverged from the others'" -- exactly the F-6 trap's precondition (a node whose
  // OWN live tip has outrun a target it is still voting on). Replaced with a genuine per-node map so a
  // scenario can move ONE node's belief independently. `advanceEpochBelief` below is kept as a thin
  // shim over this map -- see its doc -- so every existing scenario spec keeps byte-for-byte identical
  // behaviour without being rewritten.
  private val epochBelief: mutable.Map[Int, Int] = mutable.Map.empty[Int, Int].withDefaultValue(0)
  // F-6 fix: each node's OWN simulated live chain tip height, fed to that node's own
  // `HotStuffCoordinator.Enabled.tipHeight` -- the DST-harness equivalent of production's
  // `blockchainUpdater.height`. Defaults to 0 for every node (matching `HotStuffCoordinator.Enabled`'s
  // own `tipHeight` default), so a scenario that never calls `advanceTip` observes the lag check as an
  // unconditional no-op, same as `maxTargetLag`'s `Int.MaxValue` default above. Deliberately SEPARATE
  // from `committedTip`/`blockSource` below: `tipHeight` models the replica's OWN progress for the F-6
  // liveness check, independent of what it has actually T2-committed (a node can legitimately be ahead
  // of its own last commit -- that gap is exactly what F-6 is about).
  private val simulatedTip: mutable.Map[Int, Int] = mutable.Map.empty[Int, Int].withDefaultValue(0)
  // Per-node "settled tip" (most recent T2-committed blockId/height), fed to that node's own
  // `HotStuffCoordinator.Enabled` as its `blockSource` -- see below. Mirrors production's `blockSource`
  // (Application.scala): "the current settled tip", not an in-flight/uncommitted branch.
  private val committedTip = mutable.Map.empty[Int, (BlockId, Int)]

  private class SimEffects(self: Int) extends HotStuffEffects {
    def broadcast(m: Message): Unit = {
      // Capture the vote BEFORE handing it to the network: a vote this replica signed is an
      // equivocation signature regardless of whether a partition/drop stops it being delivered
      // (audit F-2 -- the double-signature is the violation, not its delivery).
      m match {
        case v: HotStuffVote => votes += VoteObservation(self, v, clock.currentTime)
        case _               => ()
      }
      network.send(from = self, to = live.toSet)(m) { case (to, msg) => deliver(to, msg) }
    }
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit              = {
      commits += CommitObservation(self, blockId, height, clock.currentTime)
      committedTip(self) = (blockId, height)
    }
    def onEquivocation(proof: HotStuffEquivocationProof): Unit = ()
  }

  private val nodes: Map[Int, HotStuffCoordinator.Enabled] =
    (0 until nodeCount)
      .map(i =>
        i -> new HotStuffCoordinator.Enabled(
          () => committee,
          new SimEffects(i),
          (_, _) => true,
          // Wires the real `onRoundTimerTick` leader-timeout re-propose path (including its
          // `inFlightBranch`/bounded-retry logic) into the simulation: on a genuine stall, node `i`
          // falls back to ITS OWN last-committed tip, exactly as production's `blockSource` would.
          proposalValid = (blockId: BlockId) => proposalValid(i, blockId),
          blockSource = () => committedTip.get(i),
          committeeEpochProvider = () => epochBelief(i),
          committeeEpochOf = committeeEpochOf,
          onAction = action => {
            onAction(i, action)
            action match {
              case _: HotStuffAction.Rejected => rejectionCounter()
              case _                          => ()
            }
          },
          // F-6 fix: per-node lag check, mirroring production's Application.scala wiring.
          maxTargetLag = maxTargetLag,
          tipHeight = () => simulatedTip(i)
        )
      )
      .toMap

  private def deliver(to: Int, msg: Message): Unit =
    if (live.contains(to)) msg match {
      // Resolve by blockId first (a block's height is intrinsic to the block -- mirrors production's
      // `blockchainUpdater.heightOf(blockId)`), falling back to the per-view map for any scenario that
      // only registered a height that way, then to 0 exactly as before.
      case p: HotStuffProposal   => nodes(to).onProposal(p, heightOfBlock.getOrElse(p.blockId, heightOfView.getOrElse(p.view, 0)))
      case v: HotStuffVote       => nodes(to).onVote(v)
      case qc: QuorumCertificate => nodes(to).onQC(qc)
      case _                     => ()
    }

  /** Drive `node`'s turn to lead `view`, proposing `blockId` at `blockHeight`. Records `blockHeight`
    * for `view` so that receiving nodes' `onProposal` calls (which need an externally supplied height —
    * `HotStuffProposal` itself does not carry one) get the correct value.
    */
  def leaderTurn(node: Int, view: Int, blockId: BlockId, blockHeight: Int): Unit = {
    heightOfView(view) = blockHeight
    heightOfBlock(blockId) = blockHeight
    nodes(node).onLeaderTurn(view, blockId, blockHeight)
  }

  // Real round-timer entry point (adversarial-review finding, `consensus/hotstuff-repropose-locked-
  // branch`): previously called bare `onTimeout()`, which only bumps the pacemaker's view counter and
  // never drives a leader-timeout re-propose at all -- so no DST scenario ever exercised
  // `onRoundTimerTick`/`inFlightBranch` (the in-flight-branch re-propose optimization and its bounded-
  // retry escape valve) through this harness. `onRoundTimerTick` is a strict superset of `onTimeout()`'s
  // behaviour when nothing has stalled (see its doc comment on `HotStuffCoordinator`), so this is a
  // safe like-for-like upgrade: no existing scenario spec calls `tickTimeout` today (grep confirms), so
  // this change has zero behavioural effect on `DstCrashRecoveryScenarioSpecification`,
  // `DstPartitionScenarioSpecification`, or `DstCommitteeChangeScenarioSpecification` -- it only takes
  // effect for scenarios (e.g. `DstCommitteeChangeReproposeScenarioSpecification`) that actually call it.
  def tickTimeout(node: Int): Unit = nodes(node).onRoundTimerTick()

  /** Convenience for scenarios that want to fire a shared round-timer on every currently-live node in
    * one step (deterministic ascending node-index order), mirroring a real network's replicas all
    * running the same wall-clock-driven timer.
    */
  def tickTimeoutAll(): Unit = live.toSeq.sorted.foreach(tickTimeout)

  /** Task 4 additive accessor: the recovery half of a per-node `HotStuffWatchdog` needs to call
    * `resetLocalSafetyState()` on THAT node's own coordinator. Exposed narrowly (one method, one node's
    * own reset) rather than leaking the `nodes` map itself, so a scenario wiring a watchdog per node
    * still cannot reach anything beyond what `HotStuffCoordinator`'s public interface already allows.
    */
  def resetLocalSafetyState(node: Int): Unit = nodes(node).resetLocalSafetyState()

  /** Task 4 additive accessor: the CURRENT committee, exactly as every node's own `committeeProvider`
    * reads it. Lets a per-node `HotStuffWatchdog` in a scenario spec observe the same committee data a
    * production watchdog would (`committee` var is private; scenarios must go through this, not reach in).
    */
  def currentCommittee(): GeneratorSet = committee

  /** F-6 fix, test observability: the two stale-target counters on ONE node's own coordinator (see
    * `HotStuffCoordinator.Enabled.staleTargetsAbandoned`/`staleTargetSkippedProposals`). Exposed so a
    * scenario can assert the re-anchor path actually FIRED on the node it is about, rather than
    * inferring it from an outcome a neutralized fix could also produce.
    */
  /** F-6 fix, test observability: a hook fired for every `HotStuffAction.Rejected` any node reports,
    * so a scenario can count cluster-wide rejections from a point in time WITHOUT having to wire the
    * `onAction` constructor param (which a scenario may already be using for something else, e.g. a
    * watchdog's progress signal). Settable mid-scenario, which is the point: F-6 compares rejection
    * volume AFTER the divergence is staged, not from t=0.
    */
  private var rejectionCounter: () => Unit     = () => ()
  def setRejectionCounter(f: () => Unit): Unit = rejectionCounter = f

  def staleTargetsAbandoned(node: Int): Int       = nodes(node).staleTargetsAbandoned
  def staleTargetSkippedProposals(node: Int): Int = nodes(node).staleTargetSkippedProposals

  def crash(node: Int): Unit     = live -= node
  def restart(node: Int): Unit   = live += node
  def isLive(node: Int): Boolean = live.contains(node)

  def setCommittee(next: GeneratorSet): Unit = committee = next

  /** Advance every LIVE node's shared `committeeEpoch` gating belief (see `epochBelief` above) to
    * `next` -- simulating every replica's own live chain tip having genuinely progressed into (and
    * agreed on) a new generation period, the real-world trigger for production's `committeeEpoch`
    * provider to advance. `HotStuffEngine.onQC`'s transition-gating rule
    * (`HotStuffQuorum.acceptableCommitteeEpoch`) still accepts `next` or `next - 1`, so this does not
    * need to be called with perfect synchrony relative to `committeeEpochOf`-derived vote/QC epochs --
    * it only needs to reflect that the epoch a round's votes/QCs are signed under is (or was, one step
    * back) one this replica currently accepts.
    *
    * F-6 fix: `epochBelief` is now a per-node map (was a single shared `var`); this method is kept as a
    * thin shim that sets EVERY node's entry to the same `next`, so every existing scenario spec that
    * calls this (all of them as of this change) observes byte-for-byte identical behaviour to the old
    * shared-`var` semantics. Deliberately writes ALL nodes, INCLUDING CRASHED ones (`0 until nodeCount`,
    * not `live`): the old shared `var` was a single value every node read through its own
    * `committeeEpochProvider` closure, so a crashed node that later `restart`ed read the CURRENT value,
    * not a stale pre-crash one. Filtering to `live` here would silently diverge from that on any
    * scenario that crashes a node, advances the epoch, then restarts it -- the restarted node would
    * wake with a stale belief the old harness never gave it. Faithfulness to the replaced semantics is
    * the whole point of this shim, so it writes the full node set.
    *
    * It does NOT touch `simulatedTip` -- that is a separate, F-6-only concept (see `simulatedTip`'s doc
    * and `advanceTip` below); no pre-existing scenario spec wires a finite `maxTargetLag`, so
    * `tipHeight`/`simulatedTip` remain a no-op for all of them regardless.
    */
  def advanceEpochBelief(next: Int): Unit = (0 until nodeCount).foreach(i => epochBelief(i) = next)

  /** F-6 fix: push ONE node's own simulated live chain tip to `height`, independent of every other
    * node's tip and independent of `epochBelief` (see `simulatedTip`'s doc for why the two are kept
    * separate). Lets a scenario put a single replica's tip a full generation period ahead of a target
    * it is still voting on -- the F-6 trap's precondition -- without perturbing any other node or the
    * epoch-gating belief exercised by `advanceEpochBelief`/`DstCommitteeEpochRotationScenarioSpecification`.
    */
  def advanceTip(node: Int, height: Int): Unit = simulatedTip(node) = height

  /** F-6 fix: push EVERY node's own simulated live tip to `height` at once -- the REALISTIC F-6 shape,
    * as opposed to `advanceTip`'s single-node divergence. The audit's finding is about the whole
    * cluster's T2 consensus lagging behind a chain that keeps advancing underneath it (feature-25
    * finality never halts the chain, so every replica's `blockchainUpdater.height` marches on while a
    * T2 round is stuck): in that case EVERY replica's own honest QCs for the stale target are
    * self-rejected, so no quorum of accepting replicas exists anywhere and ONLY re-anchoring can
    * restore commits. Writes all nodes including crashed ones, same rationale as `advanceEpochBelief`.
    */
  def advanceTipAll(height: Int): Unit = (0 until nodeCount).foreach(i => simulatedTip(i) = height)

  /** F-6 fix: seed EVERY node's `blockSource` answer (its "settled tip") to `(blockId, height)`.
    * Production's `blockSource` is `tip - settledDepth`, which advances WITH the live tip; the harness's
    * is `committedTip(i)`, which only moves on an actual simulated COMMIT. A scenario that advances the
    * cluster's tips (see `advanceTipAll`) must therefore also advance what those nodes would re-anchor
    * TO, or the re-anchor target is itself stale under the new tip and the scenario tests nothing.
    */
  def setCommittedTipAll(blockId: BlockId, height: Int): Unit =
    heightOfBlock(blockId) = height
    (0 until nodeCount).foreach(i => committedTip(i) = (blockId, height))

  /** F-6 fix: push EVERY node's own `committeeEpoch` gating belief AND its own simulated tip to match
    * `height`, exactly as production wires them (both are read from the SAME live tip in
    * `Application.scala`). The cluster-wide companion to `advanceTip` + `advanceEpochBeliefForNode`.
    */
  def advanceTipAndEpochAll(height: Int, epochOf: Int => Int): Unit = {
    advanceTipAll(height)
    (0 until nodeCount).foreach(i => epochBelief(i) = epochOf(height))
  }

  /** F-6 fix: push ONE node's own `committeeEpoch` gating belief independently of every other node's,
    * unlike `advanceEpochBelief` above (which deliberately moves every live node in lockstep, matching
    * every PRE-F-6 scenario's assumption that all replicas' live tips advance together). The F-6 trap's
    * precondition needs the OPPOSITE: exactly one replica whose own tip (and therefore its own
    * `committeeEpoch` belief, per `Application.scala`'s production wiring -- both are read from the
    * SAME live tip) has genuinely diverged from its peers, while a target it is still voting on was
    * signed under the epoch the REST of the committee is still on. Companion to `advanceTip`: a
    * scenario that wants a fully faithful "node N's own tip raced ahead" simulation calls both, with
    * `epoch` computed the same way production would (`committeeEpochOf(height)`), for the SAME `height`
    * passed to `advanceTip`.
    */
  def advanceEpochBeliefForNode(node: Int, epoch: Int): Unit = epochBelief(node) = epoch

  def partition(a: Set[Int], b: Set[Int]): Unit     = network.partition(a, b)
  def healPartition(a: Set[Int], b: Set[Int]): Unit = network.healPartition(a, b)

  /** Drain up to `maxEvents` scheduled events; returns the number actually fired. Call repeatedly with
    * a small `maxEvents` to interleave harness actions (crash, partition, committee change) mid-round.
    */
  def run(maxEvents: Int = 200000): Int = clock.runToQuiescence(maxEvents)
}
