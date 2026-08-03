package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, QuorumCertificate}
import io.decentralchain.protobuf.block.HotStuffPhase

/** Per-replica HotStuff safety state.
  *
  * @param prepareQC     highest-view QC this replica has seen (used by the leader for liveness)
  * @param lockedQC      the QC this replica is locked on (safety anchor; only a PRE_COMMIT QC locks)
  * @param lastVotedView the last view in which this replica cast a vote (monotonic; prevents double-voting)
  */
case class SafetyState(
    prepareQC: Option[QuorumCertificate] = None,
    lockedQC: Option[QuorumCertificate] = None,
    lastVotedView: Int = -1
)

/** Pure HotStuff safety/liveness rules (basic 3-phase HotStuff — see CONSENSUS.md and the HotStuff
  * paper, Abraham et al.). No side effects; ancestry is injected via `extendsBranch` so this module
  * is fully unit-testable in isolation.
  *
  * SAFETY-CRITICAL / AUDIT GATE: these rules are what prevent two conflicting blocks from both being
  * finalized. They are covered by adversarial unit tests and internal review, but MUST be externally
  * audited before `dcc.hotstuff.enabled` is turned on for mainnet. The engine that calls these is
  * gated off by default; this module changes no behaviour on its own.
  */
object HotStuffSafety {

  /** The HotStuff voting rule. Returns true iff it is safe AND permitted to vote for `proposal`.
    *
    * @param extendsBranch (descendant, ancestor) => true iff `descendant`'s block extends `ancestor`'s
    *                      branch (is a descendant of, or equal to, `ancestor`).
    */
  def safeToVote(proposal: HotStuffProposal, state: SafetyState, extendsBranch: (BlockId, BlockId) => Boolean): Boolean =
    proposal.view > state.lastVotedView && {
      state.lockedQC match {
        // Nothing locked yet — safe to vote, subject only to `view > lastVotedView` above.
        //
        // KNOWN, ACCEPTED, BOUNDED NARROWING (post-restart window): a freshly-constructed `SafetyState`
        // (e.g. right after a coordinator restart) starts with `lockedQC = None`, and this branch will
        // unconditionally admit ANY proposal until this replica accumulates its own lock again — including
        // a Byzantine leader replaying an old-but-real on-chain block under an inflated view number. Such
        // a proposal also clears `HotStuffCoordinator`'s `proposalValid` guard (chain-membership only, not
        // view-aware — see Application.scala/HotStuffCoordinator.scala's Task 8 Step 2 notes), so this
        // window can get an honest just-restarted replica to vote/form a QC for that replayed block under
        // the inflated view. This is a real narrowing versus the old view-coupled guard (`blockId(view)`),
        // which incidentally also gated this case by construction.
        //
        // It is deliberately left as-is (not restored) because it is bounded, not fatal: `HotStuffEngine`'s
        // commit path (`onQC`'s `qc.blockHeight > committedHeight` check) is separately monotonic in block
        // height, so no vote/QC formed via this window can ever regress or falsely-advance
        // `hotStuffFinalizedHeight` — T2 remains observational-only and feature-25 Deterministic Finality
        // is untouched regardless. The only consequence is a wasted round (a vote/QC for a
        // proposal that doesn't correspond to real new progress), not a safety violation. See the
        // regression test "a freshly-restarted replica (lockedQC=None) votes for a replayed old-but-real
        // block under an inflated view, but the commit height is monotonically bounded" in
        // HotStuffViewChangeSpecification for a concrete demonstration that this cannot regress
        // `hotStuffFinalizedHeight`.
        case None         => true // nothing locked yet — safe to vote
        case Some(locked) =>
          val safety   = extendsBranch(proposal.blockId, locked.blockId) // extends the locked branch
          val liveness = proposal.justify.exists(_.view > locked.view)   // justified by a newer QC
          safety || liveness
      }
    }

  /** Record that this replica voted in `view` (call only after `safeToVote` returned true and the
    * vote was cast). Keeps `lastVotedView` monotonic.
    */
  def recordVote(view: Int, state: SafetyState): SafetyState =
    if (view > state.lastVotedView) state.copy(lastVotedView = view) else state

  /** Update the safety state upon observing a valid `qc`:
    *  - `prepareQC` tracks the highest-view QC seen;
    *  - `lockedQC` advances only on a PRE_COMMIT QC of strictly higher view.
    * Never regresses either QC (monotonic in view).
    */
  def update(qc: QuorumCertificate, state: SafetyState): SafetyState = {
    val newPrepareQC =
      if (state.prepareQC.forall(qc.view > _.view)) Some(qc) else state.prepareQC
    val newLockedQC =
      if (qc.phase == HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT && state.lockedQC.forall(qc.view > _.view)) Some(qc)
      else state.lockedQC
    state.copy(prepareQC = newPrepareQC, lockedQC = newLockedQC)
  }

  /** The block a COMMIT-phase QC finalizes, if any. Only a COMMIT QC commits a node. */
  def committedBlock(qc: QuorumCertificate): Option[BlockId] =
    Option.when(qc.phase == HotStuffPhase.HOTSTUFF_PHASE_COMMIT)(qc.blockId)

  /** Detect equivocation: any voter that signed two or more DIFFERENT blocks at the same (view, phase).
    * Such a voter has violated protocol and is subject to exclusion (handled by the caller / feature-25
    * conflict machinery).
    */
  def equivocators(votes: Iterable[HotStuffVote]): Set[Int] =
    votes
      .groupBy(v => (v.voterIndex, v.view, v.phase))
      .collect { case ((voter, _, _), vs) if vs.iterator.map(_.blockId).toSet.size > 1 => voter }
      .toSet
}
