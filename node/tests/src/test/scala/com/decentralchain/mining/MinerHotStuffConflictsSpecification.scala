package com.decentralchain.mining

import com.decentralchain.block.{BlockEndorsement, FinalizationVoting}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, GenesisBlockHeight, Height}
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure-function tests for `Miner.foldHotStuffConflicts` (T8 rev.2): the PRODUCTION-side
  * `slashingEnabled` gate that folds pending verified equivocation proofs into a key block's
  * `FinalizationVoting`. See docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md
  * §5 -- validation/union on receipt is unconditional elsewhere; this is the only place
  * `slashingEnabled` matters.
  */
class MinerHotStuffConflictsSpecification extends AnyFreeSpec with Matchers {

  private val epoch = 7

  private def vote(voter: Int, blockIdByte: Byte, view: Int = 1, committeeEpoch: Int = epoch): HotStuffVote =
    HotStuffVote(
      view,
      HotStuffPhase.HOTSTUFF_PHASE_PREPARE,
      ByteStr(Array.fill(32)(blockIdByte)),
      Height(10),
      voter,
      ByteStr(Array.fill(96)(1: Byte)),
      committeeEpoch
    )

  private def proof(voter: Int, committeeEpoch: Int = epoch): HotStuffEquivocationProof =
    HotStuffEquivocationProof(vote(voter, 1, committeeEpoch = committeeEpoch), vote(voter, 2, committeeEpoch = committeeEpoch))

  private val neverExcluded: Int => Boolean = _ => false
  private val fallbackHeight                = () => GenesisBlockHeight

  private def fv(hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty): FinalizationVoting =
    FinalizationVoting(Seq.empty, Height(5), None, Seq.empty, hotstuffConflicts)

  "foldHotStuffConflicts" - {
    "1. slashingEnabled=false, pending nonempty, voting=Some(fv) => fv UNCHANGED" in {
      val voting = Some(fv())
      Miner.foldHotStuffConflicts(
        slashingEnabled = false,
        pending = Seq(proof(0)),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe voting
    }

    "2. enabled, pending empty => voting unchanged (None stays None)" in {
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq.empty,
        voting = None,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe None
    }

    "3. enabled, one proof matching period, voting=Some(fv) => fv.hotstuffConflicts == Seq(proof)" in {
      val p      = proof(0)
      val voting = Some(fv())
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe Some(fv(Seq(p)))
    }

    "4. enabled, proof epoch != forge-height period index => filtered out" in {
      val p      = proof(0, committeeEpoch = epoch + 1)
      val voting = Some(fv())
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe voting
    }

    "5. enabled, proof voter alreadyExcluded => filtered out" in {
      val p      = proof(3)
      val voting = Some(fv())
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = idx => idx == 3,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe voting
    }

    "6. enabled, two proofs same voter => deduped to one (keep first)" in {
      val p1     = proof(0)
      val p2     = HotStuffEquivocationProof(vote(0, 3), vote(0, 4))
      val voting = Some(fv())
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p1, p2),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe Some(fv(Seq(p1)))
    }

    "7. enabled, one valid proof, voting=None => Some(FV(valid=[], conflict=[], hotstuffConflicts=[proof], finalizedHeight=fallback, aggregatedEndorsement=None))" in {
      val p = proof(0)
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p),
        voting = None,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe Some(FinalizationVoting(Seq.empty, fallbackHeight(), None, Seq.empty, Seq(p)))
    }

    "8. enabled, proof for voter N + voting=Some(fv with conflict endorsement by N) => proof filtered out, fv otherwise unchanged" in {
      val n = 2
      val p = proof(n)
      val conflictSig = BlsSignature(
        ByteStr.decodeBase58("RNMTkL736x3TmXfjQufKnxSgySaaoec3WYnxmujcum9BHEmCdjmwvjoUehghqYCWJcNj5CNfb9QdnujV9o2DRitbLgq2bnLdTU5s1DLBWBkVx8mBayvdfx7rPZ3mtUWeh5L").get
      ).explicitGet()
      val conflictingN = BlockEndorsement(
        endorserIndex = GeneratorIndex(n),
        finalizedId = ByteStr(Array.fill(32)(9: Byte)),
        finalizedHeight = Height(4),
        endorsedId = ByteStr(Array.fill(32)(8: Byte)),
        signature = conflictSig
      )
      val voting = Some(fv().copy(conflict = Seq(conflictingN)))
      Miner.foldHotStuffConflicts(
        slashingEnabled = true,
        pending = Seq(p),
        voting = voting,
        forgeHeightPeriodIndex = epoch,
        alreadyExcluded = neverExcluded,
        fallbackFinalizedHeight = fallbackHeight
      ) shouldBe voting
    }
  }

  "clampFinalizedHeight" - {
    "raw fallback below currentHeight-1 passes through unchanged" in {
      Miner.clampFinalizedHeight(Height(5), currentHeight = 10) shouldBe Height(5)
    }

    "raw fallback equal to currentHeight (tip) is clamped down to currentHeight-1" in {
      Miner.clampFinalizedHeight(Height(10), currentHeight = 10) shouldBe Height(9)
    }

    "raw fallback above currentHeight is clamped down to currentHeight-1" in {
      Miner.clampFinalizedHeight(Height(50), currentHeight = 10) shouldBe Height(9)
    }

    "never drops below GenesisBlockHeight even at the chain's genesis" in {
      Miner.clampFinalizedHeight(GenesisBlockHeight, currentHeight = 1) shouldBe GenesisBlockHeight
    }
  }
}
