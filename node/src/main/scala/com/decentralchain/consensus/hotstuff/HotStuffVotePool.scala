package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.GeneratorSet
import io.decentralchain.protobuf.block.HotStuffPhase

/** Accumulated HotStuff votes, bucketed by the target they vote on. Only cryptographically-valid,
  * per-voter-deduplicated votes are retained (see `HotStuffVotePool.onVote`).
  *
  * `seenCommittees` records, per target, EVERY DISTINCT committee snapshot that was live at the
  * moment some vote for that target was processed. SAFETY (audit finding, 2026-07-25 — see
  * `HotStuffVotePoolCommitteeChangeSpecification`): the committee handed to `onVote` on each call is
  * whatever `HotStuffCoordinator.refreshCommittee()` currently holds, re-read fresh on every event.
  * A committee-membership change (a generator removed or added via a committed-generators/
  * conflict-generators period rollover) taking effect WHILE votes for one target are still
  * accumulating can otherwise let a stake set retroactively satisfy a threshold it never held under
  * every committee that was actually live during accumulation — the same class of transient-unsafe-
  * majority bug CockroachDB found and fixed via joint consensus in etcd/raft.
  *
  * An earlier version of this fix pinned to only the FIRST-seen snapshot. Adversarial review (see
  * git history) found that was unsafe in the GROW direction: if the committee grows mid-accumulation,
  * pinning to the smaller pre-growth snapshot lets a QC form and broadcast representing less than 2/3
  * of the CURRENT (larger) committee — a regression versus the original unpinned code, which at least
  * evaluated every vote against the live committee at call time. The fix here instead requires the
  * accumulated signer set to satisfy quorum against EVERY distinct committee snapshot observed while
  * this target was accumulating — monotonically safe in both directions: a shrinking committee cannot
  * make an insufficient signer set sufficient (it must still clear the ORIGINAL, larger threshold),
  * and a growing committee cannot let a QC form representing less than 2/3 of the CURRENT stake (it
  * must also clear the NEW, larger threshold). This does not by itself guarantee cross-replica
  * agreement on which committee a QC "belongs to" during an active transition (that would need the
  * committee's identity bound into the signed vote/QC content, or a full joint-consensus-style
  * two-phase membership-change protocol) — it closes the LOCAL, single-replica formation hazard this
  * pool is responsible for, not the wire-level question. See `HotStuffVotePoolCommitteeChangeSpecification`
  * for both the shrink- and grow-direction regression tests.
  */
case class VotePool(
    pending: Map[(Int, HotStuffPhase, BlockId), Vector[HotStuffVote]] = Map.empty,
    seenCommittees: Map[(Int, HotStuffPhase, BlockId), Set[GeneratorSet]] = Map.empty
)

/** Pure leader-side vote accumulator: collect votes for a `(view, phase, block)` target and emit a
  * `QuorumCertificate` the moment the accumulated distinct voters reach the ≥2/3 stake quorum under
  * EVERY committee snapshot observed so far for that target (see `VotePool`'s doc for why "every",
  * not just the latest or the first).
  *
  * Design (internal review finding #3 — liveness): invalid votes are dropped on ingress rather than
  * poisoning the bucket, so a single Byzantine vote cannot stall QC formation. Side-effect free and
  * unit-testable; the shell (step 4c) feeds inbound `HotStuffVote`s here and broadcasts the emitted QC.
  */
object HotStuffVotePool {

  /** Ingest one vote. `liveCommittee` is whatever the caller's committee-provider currently returns
    * (it may differ from call to call). The vote's OWN signature is checked against `liveCommittee`
    * (the committee genuinely active when THIS vote arrived — using anything else would be checking
    * a real vote against a snapshot that was never in effect when it was received). `liveCommittee` is
    * also added to this target's set of observed snapshots; the quorum-threshold question ("do the
    * accumulated signers reach 2/3?") is then answered against EVERY snapshot ever observed for this
    * target, not just this one, so a QC only forms if the signer set would have satisfied every
    * committee configuration that was live at any point during this target's accumulation.
    * Returns the updated pool and, if this vote completes the (all-snapshots) quorum for its target,
    * the freshly-formed QC (and clears that bucket + its observed-snapshot set). Invalid votes are
    * ignored.
    */
  def onVote(pool: VotePool, vote: HotStuffVote, liveCommittee: GeneratorSet): (VotePool, Option[QuorumCertificate]) = {
    val key = (vote.view, vote.phase, vote.blockId)

    if (!HotStuffQuorum.verifyVote(vote, liveCommittee)) (pool, None) // drop invalid — do not pool it
    else {
      val bucket        = pool.pending.getOrElse(key, Vector.empty)
      val updated       = if (bucket.exists(_.voterIndex == vote.voterIndex)) bucket else bucket :+ vote
      val priorSeen     = pool.seenCommittees.getOrElse(key, Set.empty)
      val seenNow       = priorSeen + liveCommittee
      val withObserved  = pool.copy(seenCommittees = pool.seenCommittees.updated(key, seenNow))
      val signerIndexes = updated.map(_.voterIndex)

      // Gate: EVERY committee ever observed for this target must independently agree quorum is
      // reached. One committee snapshot saying "yes" is not enough if an earlier (or later) one
      // that was also live during this target's accumulation would have said "no".
      if (seenNow.forall(c => HotStuffQuorum.hasQuorum(signerIndexes, c))) {
        // formQC re-verifies every vote's signature against ONE concrete committee (the latest
        // live one — the closest available proxy for what a receiver checking this QC "right now"
        // would also use) and aggregates. A signer whose key is no longer valid under the latest
        // committee is excluded from the aggregate by formQC itself; the all-snapshots gate above
        // already ensures the REMAINING signers are enough under every snapshot seen regardless.
        HotStuffQuorum.formQC(updated, liveCommittee) match {
          case Right(qc) =>
            // quorum → emit + clear this target's bucket AND its observed-snapshot set
            (withObserved.copy(pending = withObserved.pending - key, seenCommittees = withObserved.seenCommittees - key), Some(qc))
          // Reachable: `hasQuorum` only counts voter indexes, but `formQC` additionally requires every
          // vote in the bucket to share the SAME blockHeight (its `sameTarget` check). Bucketing by
          // (view, phase, blockId) ignores blockHeight, so votes that agree on the block but disagree on
          // height reach quorum yet fail to form a QC. The shell logs this discrepancy (see
          // HotStuffCoordinator.onVote). Keep the bucket (and its observed snapshots) so a later
          // matching-height vote can still form.
          case Left(_) => (withObserved.copy(pending = withObserved.pending.updated(key, updated)), None)
        }
      } else (withObserved.copy(pending = withObserved.pending.updated(key, updated)), None)
    }
  }
}
