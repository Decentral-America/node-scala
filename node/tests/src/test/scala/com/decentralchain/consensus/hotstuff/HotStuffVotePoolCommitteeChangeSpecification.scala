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
  * judged against the SAME committee snapshot. This is structurally the same class of bug
  * CockroachDB found and fixed via joint consensus in etcd/raft (a membership change taking effect
  * mid-round can transiently violate the quorum guarantee).
  *
  * Concrete demonstration below: committee membership SHRINKS (a generator is removed — the real,
  * live mechanism for this is `conflictGenerators`/committed-generators period rollover, per
  * `node/src/main/scala/com/decentralchain/state/appender/package.scala`'s `findBlockAndGetGenerators`)
  * while votes for one target are still accumulating. A 2-vote set that never reached 2/3 of the
  * ORIGINAL (4-member) committee's stake can end up satisfying 2/3 of the SHRUNK (3-member)
  * committee's stake — because `hasQuorum` at emission time only ever looks at whatever committee
  * is current AT THAT MOMENT, not the one that was active when the earlier votes in the same bucket
  * were cast. The result: a QC forms backed by less than 2/3 of the stake that was actually active
  * when those votes were solicited.
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

  "HotStuffVotePool.onVote, when the committee shrinks between two votes for the SAME target" should
    "NOT emit a QC using stake below the quorum required by the committee active when accumulation started" in {
      // vote(0) arrives while the committee is still the original 4-member one.
      val (afterFirst, qcAfterFirst) = HotStuffVotePool.onVote(VotePool(), voteOf(0), committeeOf4)
      qcAfterFirst should be(None)

      // Before vote(1) arrives, generator 3 is removed from the committee (simulating a real
      // committed-generators/period-rollover event landing between the two vote deliveries).
      // vote(1) is processed under the NEW, shrunk committee -- exactly what
      // HotStuffCoordinator.refreshCommittee() would hand to onVote in production, since it re-reads
      // committeeProvider() fresh on every single event.
      val (_, qcAfterSecond) = HotStuffVotePool.onVote(afterFirst, voteOf(1), committeeOf3AfterRemoval)

      // A correct implementation must not let a committee change that happens mid-accumulation
      // retroactively "complete" a quorum that never held under the committee actually active when
      // the earlier vote(s) in this same bucket were cast. If this fails, it demonstrates the exact
      // gap: 60 stake -- which never reached the original committee's 67-stake quorum -- got a QC
      // anyway, solely because the committee shrank while the bucket was still accumulating.
      qcAfterSecond should be(None)
    }
}
