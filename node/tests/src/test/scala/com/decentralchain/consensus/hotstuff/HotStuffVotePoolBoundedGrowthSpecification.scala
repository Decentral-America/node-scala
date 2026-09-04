package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** Task 8 Step 3 — bound `HotStuffVotePool.seenCommittees` from INSIDE the pool itself.
  *
  * `HotStuffLargeCommitteeSpecification` ("A6") already measured and documented the hazard this spec
  * closes: 50 distinct 500-member committee snapshots fed to `onVote` for a SINGLE, never-resolving
  * (view, phase, blockId) target retain all 50 snapshots (25,000 `GeneratorInfo` copies) in
  * `seenCommittees`, with the only reclaim path being the COORDINATOR's external `pruneOlderThan` call
  * (`HotStuffCoordinator.prunePool()`, driven by view-advance). A node that stalls on one target for a
  * long time relative to its committee-rollover cadence — or is fed adversarial junk votes for a bogus
  * target across many rollovers — leaks unboundedly inside the pool itself with no internal guard.
  *
  * This spec drives the SAME single-target scenario well PAST any realistic committee-rollover count
  * (200 distinct committee snapshots, vs A6's 50) and asserts the retained snapshot count for that one
  * target never exceeds `HotStuffVotePool.MaxSeenCommitteesPerTarget` — a bound enforced by `onVote`
  * itself, with no `pruneOlderThan` call anywhere in this test. Before the fix, `onVote` unconditionally
  * adds every new distinct committee to the per-target set, so this assertion fails (retained size grows
  * to 200, past the cap). After the fix, `onVote` fails CLOSED once the cap is reached for a target: it
  * refuses to admit further NEW committee snapshots (and the vote that would have introduced one), so
  * that target can never emit a QC on incomplete history — capping memory without ever forming a QC
  * that skipped checking quorum against a committee snapshot that was actually live. This preserves the
  * `HotStuffVotePoolCommitteeChangeSpecification` shrink/grow safety property exactly: the fix only
  * refuses to grow the observed-snapshot set past the cap, it never evicts an already-observed one, so
  * every committee the gate DOES check remains one that was genuinely live during accumulation.
  *
  * The cap (see `HotStuffVotePool.MaxSeenCommitteesPerTarget`) is chosen well above A6's 50-snapshot
  * stress figure so that spec's assertions are unaffected, and far above any realistic number of
  * committee rollovers a single in-flight target should ever survive in correct operation (targets are
  * also pruned by view on every view-advance via the coordinator's `prunePool()`; surviving dozens of
  * rollovers unresolved on one view implies something is already very wrong upstream).
  */
class HotStuffVotePoolBoundedGrowthSpecification extends FlatSpec {

  private val fillerKp      = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(42))
  private val fillerAddress = KeyPair(ByteStr(Array.fill[Byte](32)(43))).toAddress
  private val realKp        = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(7))

  private val view      = 42
  private val phase     = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val target    = ByteStr(Array.fill[Byte](32)(11))
  private val height    = 900000
  private val targetKey = (view, phase, target)

  // Voter 0 is the only real signer; its stake changes per epoch so every epoch's committee is a
  // structurally distinct GeneratorSet (never enough to reach quorum of a 500-member committee, so
  // the bucket/seenCommittees set is never cleared by QC formation mid-run) -- identical construction
  // to HotStuffLargeCommitteeSpecification's growth probe, just run for far more epochs and with no
  // pruneOlderThan call anywhere.
  private def committeeEpoch(epoch: Int): GeneratorSet =
    GeneratorInfo(GeneratorIndex(0), fillerAddress, realKp.publicKey, balance = 10L + epoch) +:
      (1 until 500).map(i => GeneratorInfo(GeneratorIndex(i), fillerAddress, fillerKp.publicKey, balance = 1L))

  private val msg  = HotStuffQuorum.voteMessage(view, phase, target, height)
  private val vote =
    HotStuffVote(view, phase, target, Height(height), voterIndex = 0, signature = realKp.sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).byteStr)

  "HotStuffVotePool.seenCommittees, fed 200 distinct committee snapshots for ONE never-resolving target " +
    "and NO external pruneOlderThan call" should "never retain more than MaxSeenCommitteesPerTarget snapshots" in {
      val finalPool = (0 until 200).foldLeft(VotePool()) { (pool, epoch) =>
        val (updated, qc) = HotStuffVotePool.onVote(pool, vote, committeeEpoch(epoch))
        qc should be(None)
        updated
      }

      val seen = finalPool.seenCommittees.getOrElse(targetKey, Set.empty)
      (seen.size <= HotStuffVotePool.MaxSeenCommitteesPerTarget) should be(true)
    }

  it should "still accept votes and grow up to the cap, not stall on the very first committee change" in {
    val finalPool = (0 until 10).foldLeft(VotePool()) { (pool, epoch) =>
      val (updated, _) = HotStuffVotePool.onVote(pool, vote, committeeEpoch(epoch))
      updated
    }
    finalPool.seenCommittees(targetKey).size should be(10) // well under the cap: normal growth is unaffected
  }

  it should "never form a QC from an incomplete history once capped (fail closed, not fail open)" in {
    // Drive well past the cap with a single low-stake voter across many committees -- no QC should ever
    // form, and once capped, further distinct committees are simply not admitted (existing pooled votes
    // and previously-observed snapshots are left untouched, so nothing already-checked is forgotten).
    val finalPool = (0 until 200).foldLeft(VotePool()) { (pool, epoch) =>
      val (updated, qc) = HotStuffVotePool.onVote(pool, vote, committeeEpoch(epoch))
      qc should be(None)
      updated
    }
    finalPool.seenCommittees(targetKey).size should be(HotStuffVotePool.MaxSeenCommitteesPerTarget)
  }
}
