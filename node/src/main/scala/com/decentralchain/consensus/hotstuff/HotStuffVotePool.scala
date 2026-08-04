package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.GeneratorSet
import io.decentralchain.protobuf.block.HotStuffPhase

/** Accumulated HotStuff votes, bucketed by the target they vote on. Only cryptographically-valid,
  * per-voter-deduplicated votes are retained (see `HotStuffVotePool.onVote`).
  *
  * `seenCommittees` records, per target, EVERY DISTINCT committee snapshot that was live at the
  * moment some vote for that target was processed, up to `HotStuffVotePool.MaxSeenCommitteesPerTarget`
  * (Task 8 Step 3, 2026-08-02 — see `onVote`'s cap-enforcement comment and
  * `HotStuffVotePoolBoundedGrowthSpecification`): earlier versions of this pool left this set genuinely
  * unbounded, reclaimed only by the coordinator's external `pruneOlderThan` call; it is now also capped
  * from inside the pool itself so a single pathological target cannot leak memory unboundedly even if
  * `pruneOlderThan` is never called or is called too infrequently. SAFETY (audit finding, 2026-07-25 —
  * see `HotStuffVotePoolCommitteeChangeSpecification`): the committee handed to `onVote` on each call is
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
  * must also clear the NEW, larger threshold). Note this "≥2/3 of every seen committee by stake"
  * guarantee holds BECAUSE of the COMBINATION of this stake gate AND `formQC`/`verifyQC`'s BLS
  * signature re-verification against each committee's real per-generator pubkeys — NOT from the stake
  * gate alone. The gate counts positional `GeneratorIndex`es, and an index can be reassigned to a
  * different generator across a period rollover, so index-based stake-counting on its own would not
  * bind the count to the generators who actually signed; the signature re-verification layered on top
  * is what makes the counted stake correspond to genuine signers under each committee. This does not
  * by itself guarantee cross-replica
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

  /** Hard cap on the number of distinct committee snapshots retained per (view, phase, blockId) target
    * in `seenCommittees`, enforced by `onVote` itself (Task 8 Step 3 — see the class-level scaladoc's
    * unbounded-growth warning, and `HotStuffLargeCommitteeSpecification`, which measured 50 snapshots ->
    * 25,000 retained `GeneratorInfo` copies for ONE target with no internal bound). Chosen well above
    * that spec's 50-snapshot stress figure (so it is unaffected) and far above any realistic number of
    * committee rollovers a single in-flight target should survive in correct operation — the coordinator
    * also prunes stale targets by view on every view-advance (`prunePool`/`pruneOlderThan`), so a target
    * surviving this many rollovers unresolved on one view already indicates a stall far beyond normal
    * operation, not a case this bound needs to stay live for.
    *
    * Enforcement is FAIL-CLOSED, not an LRU eviction: once a target has `MaxSeenCommitteesPerTarget`
    * distinct snapshots recorded, `onVote` refuses to admit any vote that would introduce a NEW, not
    * -yet-seen committee for that target (the vote is dropped exactly like an invalid one — pool
    * unchanged). It does NOT evict any already-observed snapshot to make room. This is deliberate:
    * the pool's core safety property (a QC only forms if the signer set clears quorum under EVERY
    * committee snapshot ever observed while the target accumulated — see the class scaladoc) depends on
    * never forgetting a snapshot that was genuinely checked. Evicting an old one to admit a new one would
    * let a later QC form without having been checked against it, silently reopening the shrink/grow
    * hazard `HotStuffVotePoolCommitteeChangeSpecification` closes. Refusing new growth once capped instead
    * means: at worst, an already-pathological target (100+ committee rollovers without resolving) simply
    * stops being able to form a QC at all until the coordinator's `pruneOlderThan` clears it — safe, if
    * not maximally live, and memory stays bounded either way. Votes under a committee ALREADY in the
    * observed set are unaffected by the cap (no new snapshot needs to be recorded), so this never
    * penalizes normal re-votes under a committee already seen.
    */
  val MaxSeenCommitteesPerTarget: Int = 128

  /** Ingest one vote. `liveCommittee` is whatever the caller's committee-provider currently returns
    * (it may differ from call to call). The vote's OWN signature is checked against `liveCommittee`
    * (the committee genuinely active when THIS vote arrived — using anything else would be checking
    * a real vote against a snapshot that was never in effect when it was received). The target's
    * bucket is then re-filtered against `liveCommittee`, silently evicting any previously-pooled vote
    * whose signer is no longer valid under it (removed or slot-reassigned by a committee rollover) —
    * this keeps the gate and `formQC` below over a single self-consistent signer set and closes a
    * permanent-stall bug (see the eviction comment inside the method). `liveCommittee` is also added
    * to this target's set of observed snapshots; the quorum-threshold question ("do the accumulated
    * signers reach 2/3?") is then answered against EVERY snapshot ever observed for this target, not
    * just this one, so a QC only forms if the signer set would have satisfied every committee
    * configuration that was live at any point during this target's accumulation.
    * Returns the updated pool and, if this vote completes the (all-snapshots) quorum for its target,
    * the freshly-formed QC (and clears that bucket + its observed-snapshot set). Invalid votes are
    * ignored.
    */
  def onVote(pool: VotePool, vote: HotStuffVote, liveCommittee: GeneratorSet): (VotePool, Option[QuorumCertificate]) = {
    val key = (vote.view, vote.phase, vote.blockId)

    val priorSeen      = pool.seenCommittees.getOrElse(key, Set.empty)
    val isNewCommittee = !priorSeen.contains(liveCommittee)
    val wouldExceedCap = isNewCommittee && priorSeen.size >= MaxSeenCommitteesPerTarget

    if (!HotStuffQuorum.verifyVote(vote, liveCommittee)) (pool, None) // drop invalid — do not pool it
    else if (wouldExceedCap) (pool, None)                             // capped (Task 8 Step 3): fail closed, do not grow seenCommittees further
    else {
      val bucket = pool.pending.getOrElse(key, Vector.empty)
      // T10-liveness defense-in-depth (2026-08-04): dedup is keyed on `(voterIndex, committeeEpoch)`,
      // NOT `voterIndex` alone. Before this, a voter's genuine same-target vote cast under a NEWER
      // epoch was silently dropped if that voter's FIRST vote for this target was already pooled under
      // an OLDER epoch (`bucket.exists(_.voterIndex == vote.voterIndex)` matched regardless of epoch) --
      // permanently stalling the bucket (the stale entry can never itself be replaced) until
      // `pruneOlderThan`-by-view evicted it, even after the root-cause fix (deriving `committeeEpoch`
      // from the vote's target height in `HotStuffCoordinator.castVotes`) made this scenario far less
      // likely among honest replicas. This does not by itself let a mixed-epoch bucket form a QC --
      // `HotStuffQuorum.formQC`'s `sameTarget` check still requires every vote in the bucket to agree on
      // `committeeEpoch`, by design (T10 fork-hazard fix) -- it only ensures a voter's genuinely later,
      // differently-epoched vote for the SAME target is never silently discarded, so it can still be
      // counted (and, once resolved, reach quorum) rather than being permanently forgotten.
      val withNew =
        if (bucket.exists(v => v.voterIndex == vote.voterIndex && v.committeeEpoch == vote.committeeEpoch)) bucket else bucket :+ vote
      // Evict any pooled vote that no longer verifies against the CURRENT live committee — e.g. its
      // signer was dropped by a committed-generators/conflict-generators period rollover, or its
      // positional slot was reassigned to a different generator. This is REQUIRED for liveness, not
      // just tidiness: `HotStuffQuorum.formQC` rejects the ENTIRE vote set (`Left`) if ANY single vote
      // fails `verifyVote` — it does not filter-and-retry on a valid subset. So one stale vote from a
      // since-removed generator, left sitting in the bucket, makes every future `formQC` call for this
      // target return `Left` forever, permanently stalling QC formation even when a safe quorum of
      // still-valid signers exists. Filtering here evicts such votes the instant a committee change
      // invalidates them, so the gate and `formQC` below always see a single self-consistent signer
      // set drawn from the live committee.
      val updated       = withNew.filter(v => HotStuffQuorum.verifyVote(v, liveCommittee))
      val seenNow       = priorSeen + liveCommittee
      val withObserved  = pool.copy(seenCommittees = pool.seenCommittees.updated(key, seenNow))
      val signerIndexes = updated.map(_.voterIndex)

      // Gate: EVERY committee ever observed for this target must independently agree quorum is
      // reached. One committee snapshot saying "yes" is not enough if an earlier (or later) one
      // that was also live during this target's accumulation would have said "no". `updated` has
      // already been filtered to votes valid under the live committee, so a shrink that evicted a
      // signer still has to clear the ORIGINAL (larger) snapshot's threshold with the REMAINING
      // signers — the shrink hazard stays closed.
      if (seenNow.forall(c => HotStuffQuorum.hasQuorum(signerIndexes, c))) {
        // formQC re-verifies every remaining vote's signature against the live committee and
        // aggregates. Because `updated` was already filtered to votes that verify under this same
        // committee, formQC cannot reject the set on a stale-signer basis here; the all-snapshots
        // gate above independently guarantees the remaining signers clear quorum under every
        // committee that was ever live while this target accumulated.
        HotStuffQuorum.formQC(updated, liveCommittee) match {
          case Right(qc) =>
            // quorum → emit + clear this target's bucket AND its observed-snapshot set
            (withObserved.copy(pending = withObserved.pending - key, seenCommittees = withObserved.seenCommittees - key), Some(qc))
          // Reachable: `hasQuorum` only counts voter indexes, but `formQC` additionally requires every
          // vote in the bucket to share the SAME blockHeight AND the SAME committeeEpoch (T10 fix --
          // its `sameTarget` check). Bucketing by (view, phase, blockId) ignores both, so votes that
          // agree on the block but disagree on height or claimed committee epoch reach quorum yet fail
          // to form a QC. The shell logs this discrepancy (see HotStuffCoordinator.onVote). Keep the
          // bucket (and its observed snapshots) so a later matching vote can still form.
          case Left(_) => (withObserved.copy(pending = withObserved.pending.updated(key, updated)), None)
        }
      } else (withObserved.copy(pending = withObserved.pending.updated(key, updated)), None)
    }
  }

  /** Bounded eviction (memory-leak guard): drop every target whose `view` is strictly older than
    * `minView`, from BOTH `pending` and `seenCommittees`. A target never resolves on its own — a
    * losing-fork block, or junk votes deliberately broadcast for bogus targets, would otherwise leak
    * one bucket plus one committee-snapshot set into the pool forever, and (before Task 8 Step 3)
    * `seenCommittees` was unbounded by distinct-snapshot count per target on top of that. `onVote` now
    * also caps distinct-snapshot growth per target internally (`MaxSeenCommitteesPerTarget`), but this
    * method remains the only way to reclaim memory from a target's ENTIRE bucket/snapshot-set once it
    * stops being relevant — the two are complementary, not redundant. The coordinator calls this from
    * its view/height-advance path with a `minView` chosen to retain the currently-active view's
    * still-in-flight phases (see `HotStuffCoordinator`). Pure and side-effect free — no timers or
    * background threads.
    */
  def pruneOlderThan(pool: VotePool, minView: Int): VotePool =
    VotePool(
      pending = pool.pending.filter { case ((view, _, _), _) => view >= minView },
      seenCommittees = pool.seenCommittees.filter { case ((view, _, _), _) => view >= minView }
    )
}
