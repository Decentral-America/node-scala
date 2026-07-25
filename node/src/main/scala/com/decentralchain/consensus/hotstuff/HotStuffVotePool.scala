package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.GeneratorSet
import io.decentralchain.protobuf.block.HotStuffPhase

/** Accumulated HotStuff votes, bucketed by the target they vote on. Only cryptographically-valid,
  * per-voter-deduplicated votes are retained (see `HotStuffVotePool.onVote`).
  *
  * `committeeSnapshot` records, per target, the committee that was active when the FIRST vote for
  * that target was accepted. SAFETY (audit finding, 2026-07-25 — see
  * `HotStuffVotePoolCommitteeChangeSpecification`): the committee handed to `onVote` on each call is
  * whatever `HotStuffCoordinator.refreshCommittee()` currently holds, re-read fresh on every event.
  * Without pinning, a committee-membership change (a generator removed via a committed-generators/
  * conflict-generators period rollover) taking effect WHILE votes for one target are still
  * accumulating can let a stake set that never reached 2/3 of the committee active when voting
  * started retroactively satisfy a since-shrunk committee's (lower) 2/3 threshold — the same class
  * of transient-unsafe-majority bug CockroachDB found and fixed via joint consensus in etcd/raft.
  * Pinning the snapshot at first-touch and reusing it for every subsequent vote/quorum-check on that
  * SAME target closes this: once a target starts accumulating, its quorum question is answered
  * entirely against one fixed committee view, never a mix of two.
  */
case class VotePool(
    pending: Map[(Int, HotStuffPhase, BlockId), Vector[HotStuffVote]] = Map.empty,
    committeeSnapshot: Map[(Int, HotStuffPhase, BlockId), GeneratorSet] = Map.empty
)

/** Pure leader-side vote accumulator: collect votes for a `(view, phase, block)` target and emit a
  * `QuorumCertificate` the moment the accumulated distinct voters reach the ≥2/3 stake quorum.
  *
  * Design (internal review finding #3 — liveness): invalid votes are dropped on ingress rather than
  * poisoning the bucket, so a single Byzantine vote cannot stall QC formation. Side-effect free and
  * unit-testable; the shell (step 4c) feeds inbound `HotStuffVote`s here and broadcasts the emitted QC.
  */
object HotStuffVotePool {

  /** Ingest one vote. `liveCommittee` is whatever the caller's committee-provider currently returns
    * (it may differ from call to call). If this is the first vote for `vote`'s target, `liveCommittee`
    * becomes that target's PINNED snapshot; every vote for this same target — including this one — is
    * then verified and quorum-checked against the pinned snapshot, not whatever `liveCommittee` is on
    * later calls. This is what prevents a mid-accumulation committee change from retroactively
    * completing a quorum that never held under the committee active when accumulation started.
    * Returns the updated pool and, if this vote completes the quorum for its (pinned) target, the
    * freshly-formed QC (and clears that bucket + its snapshot). Invalid votes are ignored.
    */
  def onVote(pool: VotePool, vote: HotStuffVote, liveCommittee: GeneratorSet): (VotePool, Option[QuorumCertificate]) = {
    val key       = (vote.view, vote.phase, vote.blockId)
    val committee = pool.committeeSnapshot.getOrElse(key, liveCommittee) // pin on first touch, reuse thereafter

    if (!HotStuffQuorum.verifyVote(vote, committee)) (pool, None) // drop invalid — do not pool it
    else {
      val bucket    = pool.pending.getOrElse(key, Vector.empty)
      val updated   = if (bucket.exists(_.voterIndex == vote.voterIndex)) bucket else bucket :+ vote
      val withPin   = pool.copy(committeeSnapshot = pool.committeeSnapshot.updated(key, committee))

      if (HotStuffQuorum.hasQuorum(updated.map(_.voterIndex), committee)) {
        HotStuffQuorum.formQC(updated, committee) match {
          case Right(qc) =>
            // quorum → emit + clear this target's bucket AND its pinned snapshot
            (withPin.copy(pending = withPin.pending - key, committeeSnapshot = withPin.committeeSnapshot - key), Some(qc))
          // Reachable: `hasQuorum` only counts voter indexes, but `formQC` additionally requires every
          // vote in the bucket to share the SAME blockHeight (its `sameTarget` check). Bucketing by
          // (view, phase, blockId) ignores blockHeight, so votes that agree on the block but disagree on
          // height reach quorum yet fail to form a QC. The shell logs this discrepancy (see
          // HotStuffCoordinator.onVote). Keep the bucket (and its pin) so a later matching-height vote
          // can still form against the SAME snapshot.
          case Left(_) => (withPin.copy(pending = withPin.pending.updated(key, updated)), None)
        }
      } else (withPin.copy(pending = withPin.pending.updated(key, updated)), None)
    }
  }
}
