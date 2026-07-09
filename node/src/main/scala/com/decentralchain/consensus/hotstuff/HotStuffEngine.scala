package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffProposal, QuorumCertificate}
import com.decentralchain.state.GeneratorSet

/** Per-replica HotStuff engine state (pure). Composes safety + pacemaker with commit tracking. */
case class EngineState(
    committee: GeneratorSet,
    safety: SafetyState = SafetyState(),
    pacemaker: PacemakerState = PacemakerState(),
    committedBlockId: Option[BlockId] = None,
    committedHeight: Int = 0
)

sealed trait HotStuffAction
object HotStuffAction {
  case class Committed(blockId: BlockId, height: Int) extends HotStuffAction
  case class EnteredView(view: Int)                   extends HotStuffAction
  case class Rejected(reason: String)                 extends HotStuffAction
}

/** Pure HotStuff engine reducer — the composition of `HotStuffQuorum` (verification), `HotStuffSafety`
  * (vote/lock/commit rules) and `HotStuffPacemaker` (view/leader) into deterministic state transitions.
  *
  * Design decisions from the internal security review (see docs/hotstuff-security-review.md):
  *  - Finding #1: every inbound QC is `verifyQC`-checked BEFORE it can influence safety state or commit.
  *  - Finding #2: a block commits only on a cryptographically-verified COMMIT-phase QC; a valid COMMIT
  *    QC is itself proof that ≥2/3 stake followed the protocol chain, so no separate chain-replay is
  *    required (nor sound — that would break catch-up). Safety is enforced by the voting rule + lock,
  *    not by the receiver re-deriving the chain.
  *  - Liveness / never-halt: on timeout the view simply advances; feature-25 Deterministic Finality
  *    continues underneath, so a HotStuff stall never halts the chain.
  *
  * The side-effecting shell (network I/O, timers, Miner/BlockAppender integration) is step 4c; this
  * reducer is pure and unit-testable. Gated behind `dcc.hotstuff.enabled` at the shell.
  */
object HotStuffEngine {

  /** Ingest a QC. Verifies it first (finding #1); on a valid COMMIT QC for a higher block than already
    * committed, emits a `Committed` action (finding #2). Always advances the view on a valid QC. */
  def onQC(state: EngineState, qc: QuorumCertificate): (EngineState, Seq[HotStuffAction]) =
    HotStuffQuorum.verifyQC(qc, state.committee) match {
      case Left(err)    => (state, Seq(HotStuffAction.Rejected(s"QC rejected: $err")))
      case Right(false) => (state, Seq(HotStuffAction.Rejected("QC signature verification failed")))
      case Right(true) =>
        val advanced = state.copy(
          safety = HotStuffSafety.update(qc, state.safety),
          pacemaker = HotStuffPacemaker.onQC(qc.view, state.pacemaker)
        )
        HotStuffSafety.committedBlock(qc) match {
          case Some(bid) if qc.blockHeight.toInt > advanced.committedHeight =>
            val committed = advanced.copy(committedBlockId = Some(bid), committedHeight = qc.blockHeight.toInt)
            (committed, Seq(HotStuffAction.Committed(bid, qc.blockHeight.toInt), HotStuffAction.EnteredView(committed.pacemaker.view)))
          case _ =>
            (advanced, Seq(HotStuffAction.EnteredView(advanced.pacemaker.view)))
        }
    }

  /** Decide whether to vote for a leader's proposal. Verifies the justify QC (finding #1), folds it into
    * safety (catch-up), then applies the HotStuff voting rule. Returns the updated state and whether a
    * vote should be cast (the shell signs + broadcasts the HotStuffVote when true). */
  def onProposal(
      state: EngineState,
      proposal: HotStuffProposal,
      extendsBranch: (BlockId, BlockId) => Boolean
  ): (EngineState, Boolean) = {
    val justifyValid = proposal.justify.forall(qc => HotStuffQuorum.verifyQC(qc, state.committee).contains(true))
    if (!justifyValid) (state, false)
    else {
      val caughtUp = proposal.justify.fold(state)(qc => state.copy(safety = HotStuffSafety.update(qc, state.safety)))
      val shouldVote = HotStuffSafety.safeToVote(proposal, caughtUp.safety, extendsBranch)
      val next       = if (shouldVote) caughtUp.copy(safety = HotStuffSafety.recordVote(proposal.view, caughtUp.safety)) else caughtUp
      (next, shouldVote)
    }
  }

  /** Round timeout: advance the view so the next leader can propose. The chain never halts — feature-25
    * finality keeps advancing underneath. */
  def onTimeout(state: EngineState): (EngineState, Seq[HotStuffAction]) = {
    val pacemaker = HotStuffPacemaker.onTimeout(state.pacemaker)
    (state.copy(pacemaker = pacemaker), Seq(HotStuffAction.EnteredView(pacemaker.view)))
  }
}
