package com.decentralchain.network

import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffMessagesSpecification extends FlatSpec {
  private val blockId = ByteStr(Array.fill[Byte](32)(7))
  private val sig     = ByteStr(Array.fill[Byte](96)(3)) // BLS signature length

  "HotStuffVoteSpec" should "round-trip a vote through protobuf" in {
    val vote = HotStuffVote(
      view = 5,
      phase = HotStuffPhase.HOTSTUFF_PHASE_PREPARE,
      blockId = blockId,
      blockHeight = Height(1234),
      voterIndex = 2,
      signature = sig
    )
    val bytes = HotStuffVoteSpec.serializeData(vote)
    HotStuffVoteSpec.deserializeData(bytes).get should be(vote)
    HotStuffVoteSpec.messageCode should be(39: Byte)
  }

  "QuorumCertificateSpec" should "round-trip a QC through protobuf" in {
    val qc = QuorumCertificate(
      view = 5,
      phase = HotStuffPhase.HOTSTUFF_PHASE_COMMIT,
      blockId = blockId,
      blockHeight = Height(1234),
      signerIndexes = Seq(0, 1, 3),
      aggregatedSignature = sig
    )
    val bytes = QuorumCertificateSpec.serializeData(qc)
    QuorumCertificateSpec.deserializeData(bytes).get should be(qc)
    QuorumCertificateSpec.messageCode should be(40: Byte)
  }

  "HotStuffProposalSpec" should "round-trip a proposal (with justify QC) through protobuf" in {
    val justify  = QuorumCertificate(4, HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT, blockId, Height(1233), Seq(0, 1, 2), sig)
    val proposal = HotStuffProposal(view = 5, blockId = blockId, justify = Some(justify))
    val bytes    = HotStuffProposalSpec.serializeData(proposal)
    HotStuffProposalSpec.deserializeData(bytes).get should be(proposal)
    HotStuffProposalSpec.messageCode should be(41: Byte)
  }

  it should "round-trip a proposal with no justify (genesis/first view)" in {
    val proposal = HotStuffProposal(view = 0, blockId = blockId, justify = None)
    HotStuffProposalSpec.deserializeData(HotStuffProposalSpec.serializeData(proposal)).get should be(proposal)
  }

  "HotStuff message codes 39/40/41" should "be registered without collision" in {
    BasicMessagesRepo.specsByCodes.get(39: Byte) should be(Some(HotStuffVoteSpec))
    BasicMessagesRepo.specsByCodes.get(40: Byte) should be(Some(QuorumCertificateSpec))
    BasicMessagesRepo.specsByCodes.get(41: Byte) should be(Some(HotStuffProposalSpec))
  }
}
