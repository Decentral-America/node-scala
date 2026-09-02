package io.decentralchain.protobuf.block

import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.Height
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PBHotStuffEquivocationProofsSpecification extends AnyFreeSpec with Matchers {

  private def vote(voter: Int, view: Int, blockIdByte: Byte, epoch: Int): HotStuffVote =
    HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, ByteStr(Array.fill(32)(blockIdByte)), Height(10), voter, ByteStr(Array.fill(96)(1: Byte)), epoch)

  private val proof = HotStuffEquivocationProof(vote(3, 7, 1, 2), vote(3, 7, 2, 2))

  "round-trips a proof, top-level fields derived from voteA" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof)
    pb.voterIndex shouldBe 3; pb.view shouldBe 7
    PBHotStuffEquivocationProofs.vanilla(pb) shouldBe proof
  }

  "REJECTS a wire proof whose top-level voter_index disagrees with vote_a (framing attempt)" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof).copy(voterIndex = 9)
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb)
  }

  "REJECTS a wire proof missing vote_a or vote_b" in {
    val pb = PBHotStuffEquivocationProofs.protobuf(proof)
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb.copy(voteA = None))
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb.copy(voteB = None))
  }

  "REJECTS a wire proof whose votes carry a negative voter index" in {
    val negativeVoterProof = HotStuffEquivocationProof(vote(-1, 7, 1, 2), vote(-1, 7, 2, 2))
    val pb                 = PBHotStuffEquivocationProofs.protobuf(negativeVoterProof)
    pb.voterIndex shouldBe -1
    an[IllegalArgumentException] should be thrownBy PBHotStuffEquivocationProofs.vanilla(pb)
  }

  "FinalizationVoting round-trip carries hotstuffConflicts, and an empty field decodes to empty (1.6.5 compat)" in {
    val fv   = com.decentralchain.block.FinalizationVoting(Seq.empty, com.decentralchain.state.Height(1), None, Seq.empty, Seq(proof))
    val back = PBFinalizationVotings.vanilla(PBFinalizationVotings.protobuf(fv)).get
    back.hotstuffConflicts shouldBe Seq(proof)
    fv.nonEmpty shouldBe true
  }
}
