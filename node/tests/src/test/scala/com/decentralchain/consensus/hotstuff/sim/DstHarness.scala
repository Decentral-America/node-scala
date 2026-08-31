package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffAction, HotStuffCoordinator, HotStuffEffects}
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}

import scala.collection.mutable

/** One committed-block observation, recorded for later invariant checking by [[SafetyInvariants]]. */
final case class CommitObservation(node: Int, blockId: BlockId, height: Int, at: SimTime)

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
    onAction: (Int, HotStuffAction) => Unit = (_, _) => ()
) {
  val clock: SimClock                                = new SimClock(seed)
  val commits: mutable.ListBuffer[CommitObservation] = mutable.ListBuffer.empty

  private val network                 = new SimNetwork[Message](clock, nodeCount, faultProfile)
  private val kps                     = (0 until nodeCount).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private var committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val live         = mutable.Set.from(0 until nodeCount)
  private val heightOfView = mutable.Map.empty[Int, Int]
  // Every node's shared "currently-believed-active committee epoch" -- the DST-harness equivalent of
  // production's `committeeEpoch` closure (`blockchainUpdater.currentGenerationPeriod`, read fresh from
  // each node's OWN live chain tip). Used ONLY for `HotStuffEngine.onQC`/`onProposal`'s transition-
  // gating decision (`HotStuffQuorum.acceptableCommitteeEpoch`), never for what a node signs (that is
  // `committeeEpochOf` above). Advances via `advanceEpochBelief` to simulate every node's live tip
  // having genuinely progressed to (agreed on) the new generation period -- see
  // `DstCommitteeEpochRotationScenarioSpecification`.
  private var epochBelief: Int = 0
  // Per-node "settled tip" (most recent T2-committed blockId/height), fed to that node's own
  // `HotStuffCoordinator.Enabled` as its `blockSource` -- see below. Mirrors production's `blockSource`
  // (Application.scala): "the current settled tip", not an in-flight/uncommitted branch.
  private val committedTip = mutable.Map.empty[Int, (BlockId, Int)]

  private class SimEffects(self: Int) extends HotStuffEffects {
    def broadcast(m: Message): Unit =
      network.send(from = self, to = live.toSet)(m) { case (to, msg) => deliver(to, msg) }
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit              = {
      commits += CommitObservation(self, blockId, height, clock.currentTime)
      committedTip(self) = (blockId, height)
    }
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
          blockSource = () => committedTip.get(i),
          committeeEpochProvider = () => epochBelief,
          committeeEpochOf = committeeEpochOf,
          onAction = action => onAction(i, action)
        )
      )
      .toMap

  private def deliver(to: Int, msg: Message): Unit =
    if (live.contains(to)) msg match {
      case p: HotStuffProposal   => nodes(to).onProposal(p, heightOfView.getOrElse(p.view, 0))
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

  def crash(node: Int): Unit     = live -= node
  def restart(node: Int): Unit   = live += node
  def isLive(node: Int): Boolean = live.contains(node)

  def setCommittee(next: GeneratorSet): Unit = committee = next

  /** Advance every node's shared `committeeEpoch` gating belief (see `epochBelief` above) to `next` --
    * simulating every replica's own live chain tip having genuinely progressed into (and agreed on) a
    * new generation period, the real-world trigger for production's `committeeEpoch` provider to
    * advance. `HotStuffEngine.onQC`'s transition-gating rule (`HotStuffQuorum.acceptableCommitteeEpoch`)
    * still accepts `next` or `next - 1`, so this does not need to be called with perfect synchrony
    * relative to `committeeEpochOf`-derived vote/QC epochs -- it only needs to reflect that the epoch
    * a round's votes/QCs are signed under is (or was, one step back) one this replica currently accepts.
    */
  def advanceEpochBelief(next: Int): Unit = epochBelief = next

  def partition(a: Set[Int], b: Set[Int]): Unit     = network.partition(a, b)
  def healPartition(a: Set[Int], b: Set[Int]): Unit = network.healPartition(a, b)

  /** Drain up to `maxEvents` scheduled events; returns the number actually fired. Call repeatedly with
    * a small `maxEvents` to interleave harness actions (crash, partition, committee change) mid-round.
    */
  def run(maxEvents: Int = 200000): Int = clock.runToQuiescence(maxEvents)
}
