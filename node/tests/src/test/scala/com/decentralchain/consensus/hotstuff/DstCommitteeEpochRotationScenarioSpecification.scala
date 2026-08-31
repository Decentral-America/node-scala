package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.test.FlatSpec

/** LIVE-ROTATION DST scenario for the T10-liveness root-cause fix (2026-08-04, see
  * `HotStuffCrossEpochLivenessSpecification`'s docstring for the full finding). That spec proves the
  * fix at the coordinator-unit level with hand-picked heights/epochs and no fault injection; THIS
  * scenario drives a REAL, multi-round `DstHarness` cluster (4 real, unmodified
  * `HotStuffCoordinator.Enabled` instances, under real network delay jitter) through a sequence of
  * rounds whose target heights cross TWO committee-epoch rotation boundaries, and confirms every round
  * still reaches quorum and commits on every live node -- i.e. a genuine, height-driven
  * `GenerationPeriod.index` rotation (the real trigger for a `committeeEpoch` change in production)
  * does not stall consensus under this fix, across a 50-seed sweep with random delivery-delay jitter.
  *
  * Why this is a meaningful liveness proof and not a tautology: `committeeEpochOf` here is a genuinely
  * height-varying pure function (`h => h / RotationPeriod`), shared identically by all 4 nodes -- exactly
  * the shape of `Application.scala`'s production wiring (`blockchain.generationPeriodOf(h).index`). Each
  * round's target height is chosen to land in a DIFFERENT epoch than the previous round. Pre-fix (i.e.
  * if `committeeEpoch` were still derived from each node's own live tip instead of the target height),
  * ordinary delivery-delay jitter across 4 nodes voting near a rotation boundary would risk exactly the
  * natural-skew mismatch `HotStuffCrossEpochLivenessSpecification` reproduces at the unit level -- this
  * scenario's job is to confirm that risk does not materialize in a real multi-round, fault-injected
  * run of production coordinator code.
  *
  * SCOPE, stated plainly -- what this does NOT prove: it deliberately does **not** also change the
  * committee's membership/stake between rounds (unlike `DstCommitteeChangeScenarioSpecification`).
  * Combining an epoch rotation with an actual committee/stake change hits a SEPARATE, already-documented,
  * out-of-scope hazard: a round's `justify` QC (`HotStuffEngine.onProposal`'s `verifyQC(qc, state.committee)`
  * check) carries forward whichever signer subset happened to reach quorum under the OLD committee, and
  * `HotStuffCoordinator`/`HotStuffEngine` have no atomic/joint-consensus-style committee-transition
  * protocol (see `DstCommitteeChangeScenarioSpecification`'s docstring) -- that signer subset can fail to
  * clear the NEW committee's threshold purely from a stake redistribution, entirely independent of
  * `committeeEpoch`. Conflating the two would misattribute a pre-existing, separately-scoped committee-
  * transition gap to this fix. This scenario isolates the ONE question this task's fix is actually
  * about: does a height-driven epoch rotation, on its own, stall QC formation. A live, dockerized
  * `node-it` scenario driving a REAL committed-generators rotation (membership change included) remains
  * explicitly flagged as still-open follow-up work in docs/hotstuff-audit-readiness.md's T10 entry --
  * this spec narrows, but does not close, that item.
  *
  * Also scoped: this is an in-process DST scenario (deterministic, seeded, no real Docker/network), not
  * a dockerized `node-it` cluster test -- chosen deliberately per the audit-readiness doc's own note
  * that local `node-it` Docker on this laptop has documented memory/flakiness constraints that make it
  * a poor fit for anything beyond the single non-regression run already recorded there.
  */
class DstCommitteeEpochRotationScenarioSpecification extends FlatSpec {
  private val SeedCount      = 50
  private val RotationPeriod = 200
  // Pure, height-derived epoch function shared by every node -- the DST-harness equivalent of
  // `Application.scala`'s production `committeeEpochOf`.
  private val committeeEpochOf: Int => Int = h => h / RotationPeriod

  private def blockAt(tag: Int): BlockId = ByteStr(Array.fill[Byte](32)(tag.toByte))

  private final case class Round(height: Int, view: Int, tag: Int)

  "a 4-node cluster driven through 3 rounds whose target heights cross 2 committee-epoch rotation boundaries" should
    "reach quorum and commit on every live node in EVERY round, for every seed in the sweep (no cross-epoch liveness stall)" in {
      // Round heights deliberately land in 3 DIFFERENT epochs under `committeeEpochOf` (0, 1, 2).
      val rounds = Seq(
        Round(height = 100, view = 0, tag = 1),
        Round(height = 250, view = 1, tag = 2),
        Round(height = 450, view = 2, tag = 3)
      )
      rounds.map(r => committeeEpochOf(r.height)).distinct.size should be(3) // sanity: genuinely 3 distinct epochs

      var firstFailure: Option[(Long, String)] = None

      (0 until SeedCount).foreach { seed =>
        val harness =
          new DstHarness(seed, nodeCount = 4, FaultProfile(minDelayMillis = 1, maxDelayMillis = 3), committeeEpochOf = committeeEpochOf)

        rounds.zipWithIndex.foreach { case (r, i) =>
          // Every node's live chain tip has genuinely progressed into this round's generation period
          // by the time this round starts -- the real-world trigger for `committeeEpoch`'s gating
          // belief to advance (see `DstHarness.advanceEpochBelief`'s doc).
          harness.advanceEpochBelief(committeeEpochOf(r.height))
          harness.leaderTurn(node = i % 4, view = r.view, blockId = blockAt(r.tag), blockHeight = r.height)
          harness.run()

          val commitsThisRound = harness.commits.filter(_.height == r.height)
          val nodesCommitted   = commitsThisRound.map(_.node).toSet
          if (nodesCommitted != Set(0, 1, 2, 3) && firstFailure.isEmpty)
            firstFailure = Some(
              (
                seed,
                s"round $i (height=${r.height}, epoch=${committeeEpochOf(r.height)}): expected all 4 nodes to commit, " +
                  s"only $nodesCommitted did -- a cross-epoch liveness stall"
              )
            )
        }

        SafetyInvariants.checkAll(harness.commits.toSeq, harness.votes.toSeq) match {
          case Left(reason) if firstFailure.isEmpty => firstFailure = Some((seed, reason))
          case _                                    => ()
        }
      }

      firstFailure match {
        case None                 => succeed
        case Some((seed, reason)) =>
          fail(s"DST found a committee-epoch-rotation liveness/safety violation at seed=$seed: $reason")
      }
    }
}
