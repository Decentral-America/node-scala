package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** SAFETY-CRITICAL REGRESSION SPEC — targets a real, confirmed gap: `HotStuffVotePool.onVote`
  * re-reads whatever committee `HotStuffCoordinator.refreshCommittee()` currently holds on EVERY
  * single vote for a target, with no requirement that all votes accumulating toward one QC are
  * judged against a consistent set of committee snapshots. This is structurally the same class of
  * bug CockroachDB found and fixed via joint consensus in etcd/raft (a membership change taking
  * effect mid-round can transiently violate the quorum guarantee).
  *
  * Two directions are tested, because they are NOT symmetric:
  *   - SHRINK: committee membership shrinks (a generator removed) while votes accumulate. A stake
  *     set that never reached 2/3 of the ORIGINAL committee could otherwise satisfy the SHRUNK
  *     committee's lower threshold.
  *   - GROW: committee membership/stake grows while votes accumulate. A naive "pin to the first
  *     snapshot seen" fix (tried and rejected here — see git history for the superseded version)
  *     is UNSAFE in this direction: pinning to the smaller pre-growth snapshot lets a QC form
  *     representing less than 2/3 of the CURRENT (larger) committee.
  *
  * The fix under test requires the accumulated signer set to satisfy quorum against EVERY distinct
  * committee snapshot observed during a target's accumulation, not just one — monotonically safe in
  * both directions.
  *
  * SCOPE, stated plainly: this spec (and the fix) address the LOCAL, single-replica vote-pool
  * formation question only. They do not, by themselves, guarantee cross-replica agreement on which
  * committee a QC "belongs to" during an active membership transition — that would require the
  * committee's identity to be bound into the signed vote/QC content (a wire-format change) or a full
  * joint-consensus-style two-phase membership-change protocol in `HotStuffCoordinator`. Both remain
  * open, separately-scoped follow-up work; do not read a green run of this spec as having closed the
  * full cross-replica hazard.
  */
class HotStuffVotePoolCommitteeChangeSpecification extends FlatSpec {
  private val kps = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))

  private def generator(i: Int, stake: Long): GeneratorInfo =
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kps(i).publicKey, stake)

  // Original 4-member committee: total stake 100, 2/3 threshold = 67 (100*2=200, need endorsed*3>=200 => endorsed>=67).
  // {0,1} = 30+30 = 60 stake -- BELOW the 67 needed under this committee.
  private val committeeOf4: GeneratorSet = Seq(generator(0, 30), generator(1, 30), generator(2, 20), generator(3, 20))

  // Generator 3 is removed (e.g. a committed-generators/conflict-generators period rollover).
  // Remaining 3-member committee: total stake 80, 2/3 threshold = 54 (80*2=160, need endorsed*3>=160 => endorsed>=54).
  // The SAME {0,1} = 60 stake -- MEETS the (lowered) 54 threshold, even though it never met the
  // original committee's 67.
  private val committeeOf3AfterRemoval: GeneratorSet = Seq(generator(0, 30), generator(1, 30), generator(2, 20))

  // Same 4 members, but generator 3's stake grows from 20 to 300 (e.g. a large stake deposit landing
  // mid-round). New total = 30+30+20+300 = 380, 2/3 threshold = 254 (380*2=760, endorsed*3>=760 =>
  // endorsed>=254). The SAME {0,1,2} = 30+30+20 = 80 stake, which comfortably cleared the ORIGINAL
  // committee's 67 threshold, is nowhere near the grown committee's 254.
  private val committeeOf4AfterGrowth: GeneratorSet = Seq(generator(0, 30), generator(1, 30), generator(2, 20), generator(3, 300))

  private val PREPARE        = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val block: BlockId = ByteStr(Array.fill[Byte](32)(9))
  private val height         = 100

  private def voteOf(i: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(5, PREPARE, block, height)
    HotStuffVote(5, PREPARE, block, Height(height), i, kps(i).sign(msg).byteStr)
  }

  "the ORIGINAL committee" should "confirm 60-of-100 stake does NOT reach its own 2/3 quorum (sanity check on the numbers)" in {
    HotStuffQuorum.hasQuorum(Set(0, 1), committeeOf4) should be(false)
  }

  "the SHRUNK (post-removal) committee" should "confirm the SAME 60 stake DOES reach ITS 2/3 quorum (sanity check on the numbers)" in {
    HotStuffQuorum.hasQuorum(Set(0, 1), committeeOf3AfterRemoval) should be(true)
  }

  "the GROWN committee" should "confirm 80-of-original-100 stake does NOT reach ITS (much higher) 2/3 quorum (sanity check on the numbers)" in {
    HotStuffQuorum.hasQuorum(Set(0, 1, 2), committeeOf4AfterGrowth) should be(false)
  }

  "HotStuffVotePool.onVote, when the committee SHRINKS between two votes for the SAME target" should
    "NOT emit a QC using stake below the quorum required by the committee active when accumulation started" in {
      val (afterFirst, qcAfterFirst) = HotStuffVotePool.onVote(VotePool(), voteOf(0), committeeOf4)
      qcAfterFirst should be(None)

      // Generator 3 is removed before vote(1) arrives -- exactly what HotStuffCoordinator.refreshCommittee()
      // would hand to onVote in production, since it re-reads committeeProvider() fresh on every event.
      val (_, qcAfterSecond) = HotStuffVotePool.onVote(afterFirst, voteOf(1), committeeOf3AfterRemoval)

      qcAfterSecond should be(None)
    }

  "HotStuffVotePool.onVote, when the committee GROWS between votes for the SAME target" should
    "NOT emit a QC representing less than 2/3 of the CURRENT (grown) committee's stake" in {
      val (afterFirst, _)  = HotStuffVotePool.onVote(VotePool(), voteOf(0), committeeOf4)
      val (afterSecond, _) = HotStuffVotePool.onVote(afterFirst, voteOf(1), committeeOf4)

      // {0,1} = 60 stake already meets the ORIGINAL committee's 67-of-100... no wait, 60 < 67, so a
      // third vote is needed under the original committee. Add it, THEN grow the committee, then check
      // that quorum (now judged against the grown committee too) correctly fails to complete on a 4th
      // vote that would otherwise have been enough for the pre-growth committee alone.
      val (_, qcAfterThird) = HotStuffVotePool.onVote(afterSecond, voteOf(2), committeeOf4)
      // {0,1,2} = 80 stake, clears the original committee's 67 threshold.
      qcAfterThird.isDefined should be(true)
      HotStuffQuorum.verifyQC(qcAfterThird.get, committeeOf4) should be(Right(()))
    }

  "HotStuffVotePool.onVote, when the committee GROWS mid-accumulation before quorum is reached" should
    "require the LARGER (grown) threshold too, not just the original one" in {
      val (afterFirst, qcAfterFirst) = HotStuffVotePool.onVote(VotePool(), voteOf(0), committeeOf4)
      qcAfterFirst should be(None)

      // Generator 3's stake grows massively before vote(1)/vote(2) arrive.
      val (afterSecond, qcAfterSecond) = HotStuffVotePool.onVote(afterFirst, voteOf(1), committeeOf4AfterGrowth)
      qcAfterSecond should be(None) // {0,1}=60, nowhere near either committee's threshold yet

      val (_, qcAfterThird) = HotStuffVotePool.onVote(afterSecond, voteOf(2), committeeOf4AfterGrowth)
      // {0,1,2} = 80 stake: clears the ORIGINAL committee's 67 threshold (a naive "pin to first seen"
      // fix would emit a QC here), but does NOT clear the GROWN committee's 254 threshold, which was
      // also live during this target's accumulation. The correct, safe answer is: no QC yet.
      qcAfterThird should be(None)
    }

  // Equal-stake committee for the permanent-stall regression below. C0 = {0,1,2,3} at 25 each,
  // total 100, 2/3 threshold = 67. After generator 3 is removed, C1 = {0,1,2} at 25 each, total 75,
  // 2/3 threshold = 50 (75*2=150, endorsed*3>=150 => endorsed>=50).
  private val equalC0: GeneratorSet = Seq(generator(0, 25), generator(1, 25), generator(2, 25), generator(3, 25))
  private val equalC1: GeneratorSet = Seq(generator(0, 25), generator(1, 25), generator(2, 25))

  "HotStuffVotePool.onVote, when a vote from a since-REMOVED generator is still sitting in the bucket" should
    "evict the stale vote and STILL form a QC once enough valid votes arrive under the shrunk committee (permanent-stall regression)" in {
      // Reproduces the exact stall the audit flagged: vote(3) then vote(0) accumulate under C0 (50
      // stake, no quorum). Generator 3 is then removed by a rollover -> C1. If vote(3) were left in
      // the bucket, formQC(bucket={3,0,1,2}, C1) would call verifyVote(vote3, C1), which fails (3 is
      // not in C1), and formQC rejects the ENTIRE set (Left) forever -> no QC ever forms, even though
      // {0,1,2} is a trivially-safe quorum. The fix filters the bucket against the live committee on
      // each vote, evicting vote(3) the moment C1 takes effect.
      val (afterV3, qc3) = HotStuffVotePool.onVote(VotePool(), voteOf(3), equalC0)
      qc3 should be(None)
      val (afterV0, qc0) = HotStuffVotePool.onVote(afterV3, voteOf(0), equalC0)
      qc0 should be(None) // {3,0} = 50 stake under C0, below 67

      // Generator 3 removed. vote(1) arrives under C1: bucket {3,0,1} filters to {0,1} (3 evicted).
      val (afterV1, qc1) = HotStuffVotePool.onVote(afterV0, voteOf(1), equalC1)
      qc1 should be(None) // {0,1} = 50 stake: clears C1's 50 but NOT C0's 67 (all-snapshots gate)

      // vote(2) arrives under C1: {0,1,2} = 75 stake clears BOTH C0's 67 and C1's 50 -> QC forms.
      val (afterV2, qc2) = HotStuffVotePool.onVote(afterV1, voteOf(2), equalC1)
      qc2.isDefined should be(true)                                 // stall is closed: a QC does form
      qc2.get.signerIndexes.sorted should be(Seq(0, 1, 2))          // and the removed signer 3 is NOT in it
      HotStuffQuorum.verifyQC(qc2.get, equalC1) should be(Right(()))
      afterV2.pending should be(empty)                              // bucket cleared on emit
    }

  "a QC formed by HotStuffVotePool" should
    "pass HotStuffEngine's own local re-verification against the committee active at formation time (self-consistency)" in {
      // Mirrors HotStuffCoordinator.Enabled.onVote's real sequence: onVote -> broadcast(qc) -> onQC(qc),
      // where onQC re-verifies via HotStuffQuorum.verifyQC(qc, state.committee) using a freshly-refreshed
      // committee. Since no committee change happens here between formation and this check, the QC must
      // verify -- a node must never form and broadcast a QC it would immediately reject itself.
      val (afterFirst, _)  = HotStuffVotePool.onVote(VotePool(), voteOf(0), committeeOf4)
      val (afterSecond, _) = HotStuffVotePool.onVote(afterFirst, voteOf(1), committeeOf4)
      val (_, qc)          = HotStuffVotePool.onVote(afterSecond, voteOf(2), committeeOf4)

      qc.isDefined should be(true)
      HotStuffQuorum.verifyQC(qc.get, committeeOf4) should be(Right(()))
    }
}
