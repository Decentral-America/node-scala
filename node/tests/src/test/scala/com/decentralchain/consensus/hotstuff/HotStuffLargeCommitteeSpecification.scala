package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffProposalSpec, HotStuffVote, NetworkServer, QuorumCertificate, QuorumCertificateSpec}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** Task A6 — mainnet-scale committee probe (unit-level, no docker/network).
  *
  * The 4-node docker suites validate BFT PROTOCOL correctness (`n=3f+1`, `f=1`); the stake-weighted
  * 2/3 quorum math (`HotStuffQuorum.hasQuorum`) is size-independent, so that part is safe by
  * construction. But mainnet will run hundreds of generators, and two SCALE-only risks are flagged in
  * the code itself and were never exercised at that scale by any existing test:
  *
  *   1. `BasicMessagesRepo.QuorumCertificateSpec` — `signer_indexes` "may be up to the
  *      committed-committee size; bounded by the network frame limit" (`NetworkServer.MaxFrameLength`).
  *   2. `HotStuffVotePool.seenCommittees` — documented UNBOUNDED growth with committee changes.
  *
  * This spec builds synthetic 100/500/1000-member committees and (a) re-confirms the quorum math
  * holds at each size using the REAL `HotStuffQuorum.hasQuorum` (not a re-derived formula) to pick the
  * boundary, (b) round-trips a full 1000-signer QC/Proposal through the real wire codec and measures
  * its size against the real frame limit, and (c) drives `HotStuffVotePool` through 50 committee
  * changes at size 500 and measures the actual `seenCommittees` growth.
  *
  * `hasQuorum`/`verifyVote` do not depend on BLS public keys or addresses being distinct per member —
  * only `index` and `balance` matter for the stake math, and only the ACTUAL signer's key must verify.
  * So all "filler" committee members below share one placeholder BLS key/address; only the one or two
  * members that actually vote get an independent, correctly-derived key.
  */
class HotStuffLargeCommitteeSpecification extends FlatSpec {

  private val fillerKp      = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(42))
  private val fillerAddress = KeyPair(ByteStr(Array.fill[Byte](32)(43))).toAddress

  private def committeeOfSize(n: Int, stakeOf: Int => Long): GeneratorSet =
    (0 until n).map(i => GeneratorInfo(GeneratorIndex(i), fillerAddress, fillerKp.publicKey, stakeOf(i)))

  /** Uses the REAL `HotStuffQuorum.hasQuorum` (the function under test) to find the smallest,
    * highest-stake-first subset of `committee` that reaches 2/3 quorum, rather than re-deriving the
    * threshold formula independently (which would only test our own arithmetic, not the subject code).
    */
  private def minimalQuorumSubset(committee: GeneratorSet): Set[Int] = {
    val byStakeDesc = committee.sortBy(g => -g.balance).map(_.index.toInt)
    var acc         = Set.empty[Int]
    val it          = byStakeDesc.iterator
    while (!HotStuffQuorum.hasQuorum(acc, committee) && it.hasNext) acc = acc + it.next()
    acc
  }

  // --- (1) Quorum math at mainnet scale: pure arithmetic, must hold at every size. ---
  Seq(100, 500, 1000).foreach { n =>
    s"HotStuffQuorum.hasQuorum, at a $n-member committee with varied stakes" should
      "hold for a minimal 2/3-stake subset and fail for its complement" in {
        val committee = committeeOfSize(n, i => 1L + (i % 7)) // varied, non-uniform stakes
        val subset    = minimalQuorumSubset(committee)

        HotStuffQuorum.hasQuorum(subset, committee) should be(true)

        val complement = committee.map(_.index.toInt).toSet -- subset
        HotStuffQuorum.hasQuorum(complement, committee) should be(false)
      }
  }

  // --- (2) Frame-limit probe: a full 1000-signer QC/Proposal must fit within one network frame. ---
  "a QuorumCertificate carrying signer_indexes for a FULL 1000-member committee" should
    "serialize (round-trip) to well within NetworkServer.MaxFrameLength" in {
      val committee = committeeOfSize(1000, i => 1L + (i % 7))
      val qc        = QuorumCertificate(
        view = 7,
        phase = HotStuffPhase.HOTSTUFF_PHASE_COMMIT,
        blockId = ByteStr(Array.fill[Byte](32)(9)),
        blockHeight = Height(500000),
        signerIndexes = committee.map(_.index.toInt),          // every one of the 1000 members signed
        aggregatedSignature = ByteStr(Array.fill[Byte](96)(3)) // BLS agg sig: fixed 96 bytes regardless of signer count
      )

      val bytes = QuorumCertificateSpec.serializeData(qc)
      // MEASURED: a full 1000-signer QC serializes to 2015 bytes, vs a 104,857,600-byte
      // (100 MiB) MaxFrameLength -- roughly 52,000x headroom. No mainnet-scale bug here: BLS
      // aggregation keeps the signature at a fixed 96 bytes regardless of signer count, and
      // 1000 varint-encoded signer indexes cost only ~2-3 bytes each.
      bytes.length should be < NetworkServer.MaxFrameLength
      QuorumCertificateSpec.deserializeData(bytes).get should be(qc)
    }

  it should "still fit within the frame limit once wrapped in a HotStuffProposal's justify field" in {
    val committee = committeeOfSize(1000, i => 1L + (i % 7))
    val qc        = QuorumCertificate(
      view = 8,
      phase = HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT,
      blockId = ByteStr(Array.fill[Byte](32)(9)),
      blockHeight = Height(500000),
      signerIndexes = committee.map(_.index.toInt),
      aggregatedSignature = ByteStr(Array.fill[Byte](96)(3))
    )
    val proposal = HotStuffProposal(view = 9, blockId = ByteStr(Array.fill[Byte](32)(10)), justify = Some(qc))

    val bytes = HotStuffProposalSpec.serializeData(proposal)
    // MEASURED: 2054 bytes (the extra ~39 bytes over the bare QC above is the proposal's own
    // view/blockId/protobuf framing).
    bytes.length should be < NetworkServer.MaxFrameLength
    HotStuffProposalSpec.deserializeData(bytes).get should be(proposal)
  }

  // --- (3) Vote-pool growth: HotStuffVotePool.seenCommittees is documented UNBOUNDED; measure it. ---
  "HotStuffVotePool.seenCommittees, fed 50 distinct 500-member committee snapshots for ONE target" should
    "retain one full committee snapshot per distinct change (confirms the documented UNBOUNDED growth) " +
    "until the coordinator's own pruneOlderThan is called" in {
      val realKp = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(7))
      val view   = 42
      val phase  = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
      val target = ByteStr(Array.fill[Byte](32)(11))
      val height = 900000
      val key    = (view, phase, target)

      // Voter 0 is the only real signer (correct key); every other of the 500 members is a filler with
      // fixed stake 1. Only voter 0's stake changes per epoch (10+epoch), which is enough to make each
      // epoch's committee structurally distinct (so `Set[GeneratorSet]` counts it as a new snapshot)
      // while keeping voter 0's stake share far below the 2/3 quorum of a 500-member committee at every
      // epoch -- so no QC ever forms and the bucket/seenCommittees set is never cleared mid-run.
      def committeeEpoch(epoch: Int): GeneratorSet =
        GeneratorInfo(GeneratorIndex(0), fillerAddress, realKp.publicKey, balance = 10L + epoch) +:
          (1 until 500).map(i => GeneratorInfo(GeneratorIndex(i), fillerAddress, fillerKp.publicKey, balance = 1L))

      val msg  = HotStuffQuorum.voteMessage(view, phase, target, height)
      val vote = HotStuffVote(view, phase, target, Height(height), voterIndex = 0, signature = realKp.sign(msg, BlsUtils.BlsDomainSeparationTag).byteStr)

      val finalPool = (0 until 50).foldLeft(VotePool()) { (pool, epoch) =>
        val (updated, qc) = HotStuffVotePool.onVote(pool, vote, committeeEpoch(epoch), cryptoV2 = false)
        qc should be(None) // a single ~2%-stake voter never reaches 2/3 of any of these committees
        updated
      }

      val seen = finalPool.seenCommittees(key)
      // MEASURED: exactly one retained snapshot per distinct committee handed to onVote -- growth is
      // 1:1 with committee-change count, unbounded by the pool itself, exactly as VotePool's scaladoc
      // documents ("seenCommittees records ... EVERY DISTINCT committee snapshot").
      seen.size should be(50)
      // MEASURED: 50 * 500 = 25,000 retained GeneratorInfo copies pinned in memory for this ONE
      // in-flight (view, phase, blockId) target, for as long as it never resolves to a QC.
      seen.toSeq.map(_.size).sum should be(50 * 500)

      // UPDATE (Task 8 Step 3, 2026-08-02): the growth measured above is real, but as of this change it
      // is no longer unbounded from the pool's own perspective -- onVote now caps distinct-snapshot
      // growth per target at HotStuffVotePool.MaxSeenCommitteesPerTarget (128, well above this spec's
      // 50, so the assertions above are unaffected). See HotStuffVotePoolBoundedGrowthSpecification for
      // the growth-past-the-cap probe and HotStuffVotePool.onVote's fail-closed cap-enforcement comment.
      // The coordinator's external pruneOlderThan discipline (see HotStuffVotePoolSpecification) remains
      // the only way to reclaim memory from an ENTIRE target once it stops being relevant.
      val pruned = HotStuffVotePool.pruneOlderThan(finalPool, minView = view + 1)
      pruned.seenCommittees.get(key) should be(None)
    }
}
