package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.GeneratorSet
import io.decentralchain.protobuf.block.HotStuffPhase

/** Accumulated HotStuff votes, bucketed by the target they vote on. Only cryptographically-valid,
  * per-voter-deduplicated votes are retained (see `HotStuffVotePool.onVote`). */
case class VotePool(pending: Map[(Int, HotStuffPhase, BlockId), Vector[HotStuffVote]] = Map.empty)

/** Pure leader-side vote accumulator: collect votes for a `(view, phase, block)` target and emit a
  * `QuorumCertificate` the moment the accumulated distinct voters reach the ≥2/3 stake quorum.
  *
  * Design (internal review finding #3 — liveness): invalid votes are dropped on ingress rather than
  * poisoning the bucket, so a single Byzantine vote cannot stall QC formation. Side-effect free and
  * unit-testable; the shell (step 4c) feeds inbound `HotStuffVote`s here and broadcasts the emitted QC.
  */
object HotStuffVotePool {

  /** Ingest one vote. Returns the updated pool and, if this vote completes a quorum for its target,
    * the freshly-formed QC (and clears that bucket). Invalid votes are ignored. */
  def onVote(pool: VotePool, vote: HotStuffVote, committee: GeneratorSet): (VotePool, Option[QuorumCertificate]) =
    if (!HotStuffQuorum.verifyVote(vote, committee)) (pool, None) // drop invalid — do not pool it
    else {
      val key    = (vote.view, vote.phase, vote.blockId)
      val bucket  = pool.pending.getOrElse(key, Vector.empty)
      val updated = if (bucket.exists(_.voterIndex == vote.voterIndex)) bucket else bucket :+ vote

      if (HotStuffQuorum.hasQuorum(updated.map(_.voterIndex), committee)) {
        HotStuffQuorum.formQC(updated, committee) match {
          case Right(qc) => (pool.copy(pending = pool.pending - key), Some(qc)) // quorum → emit + clear bucket
          case Left(_)   => (pool.copy(pending = pool.pending.updated(key, updated)), None) // unreachable: all pooled votes are valid
        }
      } else (pool.copy(pending = pool.pending.updated(key, updated)), None)
    }
}
