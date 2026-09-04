package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** SAFETY-CRITICAL FINDING (2026-07-26) — concretizes the "cross-replica committee identity" gap
  * flagged (but never demonstrated end-to-end) in `VotePool`'s doc comment and in
  * `docs/hotstuff-integration-design.md`'s "follow-up (a)". This is a DIFFERENT hazard from the one
  * `HotStuffVotePoolCommitteeChangeSpecification` closes:
  *
  *   - That spec: ONE replica's OWN vote-pool bucket observes a committee CHANGE while votes for a
  *     SINGLE target accumulate. Fixed by requiring quorum against every snapshot seen during that
  *     one accumulation.
  *   - THIS spec: TWO SEPARATE, fully self-consistent QC formations — each internally valid, each
  *     accumulated entirely under its OWN unchanging committee, each independently passing
  *     `HotStuffQuorum.verifyQC` — nonetheless certify TWO DIFFERENT blocks at the SAME (view,
  *     height). No single vote-pool instance ever sees a committee change, so the other spec's fix
  *     does not apply here and could not catch this.
  *
  * Root cause: `HotStuffQuorum.voteMessage`/`formQC`/`verifyQC` never bind the committee's identity
  * into the signed content. A vote's signature is checked against WHATEVER `GeneratorSet` the caller
  * hands in; nothing in the wire format says "this vote was cast under committee-with-hash-X". Two
  * committees with ZERO common members (e.g. a full validator-set rotation between committed-
  * generators periods) can each reach their OWN independent 2/3-stake quorum for a DIFFERENT block
  * at an identical (view, height) pair, using entirely disjoint, entirely honest signers on each
  * side. Ordinary BFT quorum-intersection math (any two >=2/3 quorums of the SAME fixed set must
  * share >=1/3 of it, so they cannot certify conflicting values) is exactly what makes a partition
  * under a STATIC committee safe -- see `FourNodeHotStuffTestSuite`'s partition case, and this is
  * why that test correctly finds no fork. That guarantee provably does NOT extend across two
  * DIFFERENT committees: this spec exhibits two 2/3-quorums with an empty intersection.
  *
  * `HotStuffSafety.equivocators` -- the only Byzantine-signer detector in this codebase -- is blind
  * to this: it only flags a SINGLE voter signing two different blocks at the same (view, phase).
  * Every signer below is honest and signs exactly once; this spec proves `equivocators` returns
  * empty even while a hash-comparable fork condition exists at the QC layer.
  *
  * SCOPE: this spec proves the hazard is real and reachable through the public `HotStuffQuorum`/
  * `HotStuffVotePool` API alone (no coordinator, no network simulation needed) -- deliberately, so
  * the finding stands independent of any one shell/wiring detail. It does NOT ship a fix. Closing it
  * for real needs one of: (a) binding committee identity into the signed vote/QC bytes (a wire-
  * format change) plus a coordinator-level rule that rejects/holds proposals whose committee epoch
  * has not been reached via a properly-finalized transition from the currently-locked epoch, or (b)
  * a full joint-consensus-style two-phase membership-change protocol. Both are real protocol-design
  * decisions -- see `docs/hotstuff-integration-design.md` follow-up (a) -- and deliberately out of
  * scope for a single, unreviewed session; attempting either without dedicated multi-round
  * adversarial review (the process `HotStuffVotePoolCommitteeChangeSpecification`'s own fix went
  * through, twice) risks shipping a change that LOOKS safe and is not, exactly as happened on that
  * fix's first attempt. Do not read a green run of this spec as anything other than confirmation
  * that the hazard exists and is precisely characterized.
  */
class HotStuffCrossEpochForkSpecification extends FlatSpec {
  private val kps = (0 until 8).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))

  private def generator(i: Int, stake: Long): GeneratorInfo =
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kps(i).publicKey, stake)

  // Two committees sharing ZERO members -- e.g. a full validator-set rotation between committed-
  // generators periods. Each: 4 members @ 25 stake, total 100, 2/3 threshold = 67 (need 3-of-4).
  private val committeeEpochA: GeneratorSet = Seq(generator(0, 25), generator(1, 25), generator(2, 25), generator(3, 25))
  private val committeeEpochB: GeneratorSet = Seq(generator(4, 25), generator(5, 25), generator(6, 25), generator(7, 25))

  private val PREPARE = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val view    = 10
  private val height  = 500

  private val blockA: BlockId = ByteStr(Array.fill[Byte](32)(0xaa.toByte))
  private val blockB: BlockId = ByteStr(Array.fill[Byte](32)(0xbb.toByte))

  private def voteFor(i: Int, block: BlockId): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, PREPARE, block, height)
    HotStuffVote(view, PREPARE, block, Height(height), i, kps(i).sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).byteStr)
  }

  "two committees with disjoint membership" should
    "each independently certify a DIFFERENT block at the identical (view, height) -- a real fork, using only honest single-signers" in {
      // Epoch A: generators {0,1,2} (75-of-100 stake, clears the 67 threshold) vote for blockA.
      val (afterA1, noneYet) = HotStuffVotePool.onVote(VotePool(), voteFor(0, blockA), committeeEpochA)
      noneYet should be(None)
      val (afterA2, stillNone) = HotStuffVotePool.onVote(afterA1, voteFor(1, blockA), committeeEpochA)
      stillNone should be(None)
      val (_, qcAOpt) = HotStuffVotePool.onVote(afterA2, voteFor(2, blockA), committeeEpochA)
      val qcA         = qcAOpt.getOrElse(fail("expected a QC for blockA under committeeEpochA"))

      // Epoch B: generators {4,5,6} (75-of-100 stake under THEIR OWN, entirely disjoint committee)
      // vote for a DIFFERENT block, at the SAME view and height. Nobody in this accumulation ever
      // observes committeeEpochA, and nobody in the epoch-A accumulation above ever observed
      // committeeEpochB -- these are two fully independent VotePool histories.
      val (afterB1, _) = HotStuffVotePool.onVote(VotePool(), voteFor(4, blockB), committeeEpochB)
      val (afterB2, _) = HotStuffVotePool.onVote(afterB1, voteFor(5, blockB), committeeEpochB)
      val (_, qcBOpt)  = HotStuffVotePool.onVote(afterB2, voteFor(6, blockB), committeeEpochB)
      val qcB          = qcBOpt.getOrElse(fail("expected a QC for blockB under committeeEpochB"))

      // Both QCs independently pass verification against the committee that (honestly) produced
      // them -- exactly what a receiving replica would do if it accepted "the committee for this
      // QC's view" from whichever committee snapshot it happened to have on hand.
      HotStuffQuorum.verifyQC(qcA, committeeEpochA) should be(Right(()))
      HotStuffQuorum.verifyQC(qcB, committeeEpochB) should be(Right(()))

      // The fork condition itself: same view, same height, different blockId.
      qcA.view should be(qcB.view)
      qcA.blockHeight should be(qcB.blockHeight)
      qcA.blockId should not be qcB.blockId

      // Zero shared signers -- this is not a double-vote by any one generator.
      (qcA.signerIndexes.toSet intersect qcB.signerIndexes.toSet) should be(Set.empty)

      // Nothing in the QC's own content records which committee produced it: verifying qcA against
      // committeeEpochB (or vice versa) fails only because the SIGNER INDEXES happen not to overlap
      // -- i.e. purely by construction luck of this example, not because the wire format forbids the
      // mismatch. Nothing stops a future, adversarially-chosen pair of committees with overlapping
      // index numbering from making this check pass in both directions.
      HotStuffQuorum.verifyQC(qcA, committeeEpochB) shouldBe a[Left[?, ?]]
      HotStuffQuorum.verifyQC(qcB, committeeEpochA) shouldBe a[Left[?, ?]]
    }

  "HotStuffSafety.equivocators" should
    "report NO equivocating signer for the cross-epoch fork above (proving the existing Byzantine detector is blind to this hazard)" in {
      val votes = Seq(voteFor(0, blockA), voteFor(1, blockA), voteFor(2, blockA), voteFor(4, blockB), voteFor(5, blockB), voteFor(6, blockB))
      HotStuffSafety.equivocators(votes) should be(Set.empty)
    }

  // --- T10 FIX (2026-08-03): committee-epoch binding + transition-gating rule ------------------------
  // The two tests above prove the hazard using UNLABELED votes (committeeEpoch defaults to 0 for both
  // sides -- exactly what every pre-fix vote/QC looked like on the wire). They must keep passing
  // unchanged: labeling is opt-in, not retroactive amnesia for already-analyzed unlabeled traffic.
  //
  // The tests below prove the FIX: once each side signs its votes under its OWN distinct
  // `committeeEpoch` (the natural, already-existing `GenerationPeriod.index` -- see that class's doc
  // -- reused rather than inventing a new committee-hash scheme), the two committees' votes can no
  // longer be confused with each other, and a replica's transition-gating rule
  // (`HotStuffQuorum.acceptableCommitteeEpoch`) rejects a QC from any epoch it does not currently
  // consider live.
  private def voteForEpoch(i: Int, block: BlockId, epoch: Int): HotStuffVote = {
    val msg = HotStuffQuorum.voteMessage(view, PREPARE, block, height, epoch)
    HotStuffVote(view, PREPARE, block, Height(height), i, kps(i).sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).byteStr, epoch)
  }

  "binding committee epoch into the signed vote message" should
    "prevent a QC formed under one committee's epoch from ever verifying as valid under the other epoch's bytes, even at the identical (view, height)" in {
      val epochA = 41
      val epochB = 42

      val (afterA1, _) = HotStuffVotePool.onVote(VotePool(), voteForEpoch(0, blockA, epochA), committeeEpochA)
      val (afterA2, _) = HotStuffVotePool.onVote(afterA1, voteForEpoch(1, blockA, epochA), committeeEpochA)
      val (_, qcAOpt)  = HotStuffVotePool.onVote(afterA2, voteForEpoch(2, blockA, epochA), committeeEpochA)
      val qcA          = qcAOpt.getOrElse(fail("expected a QC for blockA under committeeEpochA/epochA"))
      qcA.committeeEpoch should be(epochA)

      val (afterB1, _) = HotStuffVotePool.onVote(VotePool(), voteForEpoch(4, blockB, epochB), committeeEpochB)
      val (afterB2, _) = HotStuffVotePool.onVote(afterB1, voteForEpoch(5, blockB, epochB), committeeEpochB)
      val (_, qcBOpt)  = HotStuffVotePool.onVote(afterB2, voteForEpoch(6, blockB, epochB), committeeEpochB)
      val qcB          = qcBOpt.getOrElse(fail("expected a QC for blockB under committeeEpochB/epochB"))
      qcB.committeeEpoch should be(epochB)

      // Each QC still verifies fine against its OWN committee (no regression to the happy path).
      HotStuffQuorum.verifyQC(qcA, committeeEpochA) should be(Right(()))
      HotStuffQuorum.verifyQC(qcB, committeeEpochB) should be(Right(()))

      // The tamper-evidence property: relabeling qcA's committeeEpoch to claim it was epochB does NOT
      // make it pass as an epochB QC -- the aggregated BLS signature was computed over bytes that
      // included epochA, so re-verifying against the relabeled epoch's canonical bytes fails.
      val relabeled = qcA.copy(committeeEpoch = epochB)
      HotStuffQuorum.verifyQC(relabeled, committeeEpochA) shouldBe a[Left[?, ?]]
    }

  "formQC" should
    "refuse to merge votes that agree on (view, phase, block, height) but disagree on committeeEpoch (unlike the pre-fix code, which had no such field to disagree on)" in {
      val mixed = Seq(
        voteForEpoch(0, blockA, 41),
        voteForEpoch(1, blockA, 41),
        voteForEpoch(2, blockA, 42) // same view/phase/block/height, but a DIFFERENT claimed epoch
      )
      HotStuffQuorum.formQC(mixed, committeeEpochA) shouldBe a[Left[?, ?]]
    }

  "HotStuffQuorum.acceptableCommitteeEpoch (the transition-gating rule)" should
    "accept the current epoch and the immediately-previous one (the legitimate single-committee-rotation-over-time case), and reject everything else" in {
      HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch = 42, currentEpoch = 42) should be(true)  // current
      HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch = 41, currentEpoch = 42) should be(true)  // one-step-back transition window
      HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch = 40, currentEpoch = 42) should be(false) // too far in the past
      HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch = 43, currentEpoch = 42) should be(
        false
      ) // a future epoch nobody has finalized a transition to yet
    }

  "HotStuffEngine.onQC" should
    "REJECT the disjoint committeeEpochB's QC when this replica currently believes committeeEpochA (numeric epoch 41) is active -- closing the T10 hazard end-to-end" in {
      val epochA = 41
      val epochB = 99 // far outside the one-step transition window from 41

      val (afterA1, _) = HotStuffVotePool.onVote(VotePool(), voteForEpoch(0, blockA, epochA), committeeEpochA)
      val (afterA2, _) = HotStuffVotePool.onVote(afterA1, voteForEpoch(1, blockA, epochA), committeeEpochA)
      val (_, qcAOpt)  = HotStuffVotePool.onVote(afterA2, voteForEpoch(2, blockA, epochA), committeeEpochA)
      val qcA          = qcAOpt.getOrElse(fail("expected a QC for blockA under committeeEpochA"))

      val (afterB1, _) = HotStuffVotePool.onVote(VotePool(), voteForEpoch(4, blockB, epochB), committeeEpochB)
      val (afterB2, _) = HotStuffVotePool.onVote(afterB1, voteForEpoch(5, blockB, epochB), committeeEpochB)
      val (_, qcBOpt)  = HotStuffVotePool.onVote(afterB2, voteForEpoch(6, blockB, epochB), committeeEpochB)
      val qcB          = qcBOpt.getOrElse(fail("expected a QC for blockB under committeeEpochB"))

      // A replica whose engine currently believes epochA (41) is active and holds committeeEpochA as
      // its committee: qcA (its own genuine epoch) is accepted; qcB (epoch 99, a disjoint committee
      // entirely outside the transition window) is REJECTED before it can ever influence safety/commit
      // state -- this is the fix actually closing the fork the earlier tests in this file proved open.
      val engine = EngineState(committee = committeeEpochA, committeeEpoch = epochA)

      val (_, actionsA) = HotStuffEngine.onQC(engine, qcA)
      actionsA.exists { case HotStuffAction.Rejected(_) => true; case _ => false } should be(false)

      val (_, actionsB) = HotStuffEngine.onQC(engine, qcB)
      actionsB should matchPattern { case Seq(HotStuffAction.Rejected(msg)) if msg.contains("committee epoch") => }
    }
}
