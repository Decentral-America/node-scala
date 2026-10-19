package com.decentralchain.mining

import com.decentralchain.block.FinalizationVoting
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, Height}
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class MinerHotStuffConflictsValidOverlapSpecification extends AnyFreeSpec with Matchers {
  private val epoch = 7
  private def vote(voter: Int, b: Byte): HotStuffVote =
    HotStuffVote(1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, ByteStr(Array.fill(32)(b)), Height(10), voter, ByteStr(Array.fill(96)(0)), epoch)
  private val proofFor2 = HotStuffEquivocationProof(vote(2, 1), vote(2, 2))

  "a pending proof whose voter is already a VALID endorser in the voting is not folded (validation would reject the block)" in {
    val fv = FinalizationVoting(Seq(GeneratorIndex(2)), Height(1), None, Seq.empty)
    Miner.foldHotStuffConflicts(true, Seq(proofFor2), Some(fv), epoch, _ => false, () => Height(1)) shouldBe Some(fv)
  }

  "a proof for a voter that is not a valid endorser is still folded" in {
    val fv = FinalizationVoting(Seq(GeneratorIndex(1)), Height(1), None, Seq.empty)
    Miner.foldHotStuffConflicts(true, Seq(proofFor2), Some(fv), epoch, _ => false, () => Height(1)).map(_.hotstuffConflicts) shouldBe Some(Seq(proofFor2))
  }
}
