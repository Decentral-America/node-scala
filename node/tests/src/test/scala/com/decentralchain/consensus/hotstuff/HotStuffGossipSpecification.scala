package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

class HotStuffGossipSpecification extends FlatSpec {
  private val kp                      = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(21))
  private val committee: GeneratorSet = Seq(
    GeneratorInfo(GeneratorIndex(0), KeyPair(ByteStr(Array.fill[Byte](32)(71))).toAddress, kp.publicKey, 100L)
  )
  private val blockId = ByteStr(Array.fill[Byte](32)(0x11))
  private val phase   = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private def vote(sig: ByteStr): HotStuffVote = HotStuffVote(5, phase, blockId, Height(10), 0, sig, 0)
  private val genuine                          = vote(kp.sign(HotStuffQuorum.voteMessage(5, phase, blockId, 10, 0), HotStuffQuorum.VoteDst).byteStr)
  private val junk                             = vote(ByteStr(Array.fill[Byte](96)(7)))

  "a junk-signed vote" should "be neither relayed nor allowed to pre-empt the genuine vote for the same (view, phase, block, voter)" in {
    val relayed                         = mutable.ListBuffer.empty[Message]
    val g                               = new HotStuffGossip[String]((_, m) => relayed += m)
    def offer(v: HotStuffVote): Boolean = g.admit("peer", HotStuffGossip.voteKey(v), v)(HotStuffQuorum.verifyVote(v, committee))

    offer(junk) shouldBe false
    offer(genuine) shouldBe true
    offer(genuine) shouldBe false // dedup after a successful admit
    relayed.toList shouldBe List(genuine)
  }

  "a rejected message" should "not be marked seen" in {
    val relayed = mutable.ListBuffer.empty[Message]
    val g       = new HotStuffGossip[String]((_, m) => relayed += m)
    val key     = HotStuffGossip.voteKey(genuine)
    g.admit("peer", key, genuine)(false) shouldBe false
    g.admit("peer", key, genuine)(true) shouldBe true
    relayed.toList shouldBe List(genuine)
  }

  "keys" should "include the signature material" in {
    HotStuffGossip.voteKey(junk) should not be HotStuffGossip.voteKey(genuine)
    val qcA = QuorumCertificate(5, phase, blockId, Height(10), Seq(0), ByteStr(Array.fill[Byte](96)(1)), 0)
    val qcB = qcA.copy(aggregatedSignature = ByteStr(Array.fill[Byte](96)(2)))
    HotStuffGossip.qcKey(qcA) should not be HotStuffGossip.qcKey(qcB)
    HotStuffGossip.proposalKey(HotStuffProposal(5, blockId, Some(qcA))) should not be HotStuffGossip.proposalKey(
      HotStuffProposal(5, blockId, Some(qcB))
    )
  }
}
