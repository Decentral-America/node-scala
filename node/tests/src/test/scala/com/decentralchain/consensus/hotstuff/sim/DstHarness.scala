package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffCoordinator, HotStuffEffects}
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
final class DstHarness(seed: Long, nodeCount: Int, faultProfile: FaultProfile = FaultProfile()) {
  val clock: SimClock                                = new SimClock(seed)
  val commits: mutable.ListBuffer[CommitObservation] = mutable.ListBuffer.empty

  private val network                 = new SimNetwork[Message](clock, nodeCount, faultProfile)
  private val kps                     = (0 until nodeCount).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private var committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val live         = mutable.Set.from(0 until nodeCount)
  private val heightOfView = mutable.Map.empty[Int, Int]
  // Per-node "settled tip" (most recent T2-committed blockId/height), fed to that node's own
  // `HotStuffCoordinator.Enabled` as its `blockSource` -- see below. Mirrors production's `blockSource`
  // (Application.scala): "the current settled tip", not an in-flight/uncommitted branch.
  private val committedTip = mutable.Map.empty[Int, (BlockId, Int)]

  private class SimEffects(self: Int) extends HotStuffEffects {
    def broadcast(m: Message): Unit =
      network.send(from = self, to = live.toSet)(m) { case (to, msg) => deliver(to, msg) }
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit = {
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
          blockSource = () => committedTip.get(i)
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

  def crash(node: Int): Unit     = live -= node
  def restart(node: Int): Unit   = live += node
  def isLive(node: Int): Boolean = live.contains(node)

  def setCommittee(next: GeneratorSet): Unit = committee = next

  def partition(a: Set[Int], b: Set[Int]): Unit     = network.partition(a, b)
  def healPartition(a: Set[Int], b: Set[Int]): Unit = network.healPartition(a, b)

  /** Drain up to `maxEvents` scheduled events; returns the number actually fired. Call repeatedly with
    * a small `maxEvents` to interleave harness actions (crash, partition, committee change) mid-round.
    */
  def run(maxEvents: Int = 200000): Int = clock.runToQuiescence(maxEvents)
}
