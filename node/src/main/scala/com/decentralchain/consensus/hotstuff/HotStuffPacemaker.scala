package com.decentralchain.consensus.hotstuff

import com.decentralchain.state.{GeneratorIndex, GeneratorSet}

/** Pacemaker view number (round). Advances on a QC or on timeout; drives leader rotation. */
case class PacemakerState(view: Int = 0)

/** Pure pacemaker logic for T2 HotStuff (see CONSENSUS.md): deterministic leader-per-view and view
  * advancement. Side-effect free and unit-testable. The actual timer, deadline tracking, and the
  * side-effecting driver (reset the timeout on each new QC, fire a NewView on expiry) are wired in
  * step 4's engine; this module only defines the *rules*.
  *
  * Gated behind `dcc.hotstuff.enabled` at the engine call sites; defining/using this changes no
  * behaviour on its own.
  */
object HotStuffPacemaker {

  /** Deterministic leader for `view`: round-robin over the committee ordered by generator index.
    * Used for leader rotation on view changes (liveness when a leader is faulty/slow). Returns None
    * for an empty committee. `floorMod` keeps it well-defined for any (including negative) view.
    *
    * NOTE: in the happy path the first view of a height is expected to align with the block's FairPoS
    * forger (CONSENSUS.md: "forger = leader"); reconciling that mapping with the FairPoS schedule is
    * engine work (step 4). This function is the fallback/rotation rule.
    */
  def leaderFor(view: Int, committee: GeneratorSet): Option[GeneratorIndex] =
    if (committee.isEmpty) None
    else {
      val ordered = committee.sortBy(_.index.toInt)
      Some(ordered(Math.floorMod(view, ordered.size)).index)
    }

  /** Whether `candidateIndex` is the leader for `view`. */
  def isLeader(candidateIndex: Int, view: Int, committee: GeneratorSet): Boolean =
    leaderFor(view, committee).exists(_.toInt == candidateIndex)

  /** Advance the view upon observing a QC for `qcView` (never regresses). Standard HotStuff pacemaker:
    * a QC for view v moves the pacemaker to v+1 so the next leader can propose.
    */
  def onQC(qcView: Int, state: PacemakerState): PacemakerState =
    if (qcView >= state.view) PacemakerState(qcView + 1) else state

  /** Advance the view upon a round timeout (leader failed to drive the round to a QC in time). */
  def onTimeout(state: PacemakerState): PacemakerState =
    PacemakerState(state.view + 1)
}
