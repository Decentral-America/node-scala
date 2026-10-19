package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

/** A proposal is unauthenticated on the wire; a replica must vote for it only once the expected
  * leader's own signed PREPARE vote for the same (view, blockId) is in hand, and never for a view
  * outside [0, pacemaker.view + maxProposalViewAhead]. 4 members x 100 stake, so two votes never
  * reach the 2/3 quorum and no QC interferes with the assertions.
  */
class HotStuffProposalAuthenticationSpecification extends FlatSpec {
  private val n                           = 4
  private val kps: IndexedSeq[BlsKeyPair] = (0 until n).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 11).toByte)))
  private val addrs: IndexedSeq[Address]  = (0 until n).map(i => KeyPair(ByteStr(Array.fill[Byte](32)((i + 61).toByte))).toAddress)
  private val committee: GeneratorSet     = (0 until n).map(i => GeneratorInfo(GeneratorIndex(i), addrs(i), kps(i).publicKey, 100L))
  private val me                          = 1
  private val blockId: BlockId            = ByteStr(Array.fill[Byte](32)(0x3c.toByte))
  private val height                      = 10
  private val prepare                     = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private final class Harness(generatorOfHeight: Int => Option[Address], committeeAt: Option[Int => GeneratorSet] = None) {
    val sent       = mutable.ListBuffer.empty[Message]
    val persisted  = mutable.ListBuffer.empty[Int]
    private val fx = new HotStuffEffects {
      def broadcast(m: Message): Unit                                             = sent += m
      def myVoterIndexes: Set[Int]                                                = Set(me)
      def signVote(msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] = Option.when(idx == me)(kps(me).sign(msg, dst))
      def onCommit(blockId: BlockId, height: Int): Unit                           = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit                  = ()
    }
    val coordinator = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true,
      onLastVotedViewPersist = v => persisted += v,
      committeeAt = committeeAt,
      requireLeaderVote = true,
      generatorAt = generatorOfHeight,
      maxProposalViewAhead = 1000
    )
    def myPrepareVotes: List[HotStuffVote] =
      sent.collect { case v: HotStuffVote if v.voterIndex == me && v.phase == prepare => v }.toList
  }

  private def prepareVote(signer: Int, view: Int, voteHeight: Int = height, asIndex: Option[Int] = None): HotStuffVote = {
    val sig = kps(signer).sign(HotStuffQuorum.voteMessage(view, prepare, blockId, voteHeight, 0), HotStuffQuorum.VoteDst)
    HotStuffVote(view, prepare, blockId, Height(voteHeight), asIndex.getOrElse(signer), sig.byteStr, 0)
  }

  "a proposal with an absurd view" should "be dropped without voting and without persisting lastVotedView" in {
    val h = new Harness(_ => Some(addrs(0)))
    h.coordinator.onProposal(HotStuffProposal(Int.MaxValue, blockId, None), height)
    h.coordinator.onProposal(HotStuffProposal(Int.MaxValue - 1, blockId, None), height)
    h.myPrepareVotes shouldBe empty
    h.persisted shouldBe empty
    h.coordinator.currentView.shouldBe(0)
  }

  it should "be dropped even when the rotation leader of that view signed a vote for it (view beyond pacemaker + maxProposalViewAhead)" in {
    val h    = new Harness(_ => None)
    val view = 5000 // leaderFor(5000) = 5000 mod 4 = 0
    h.coordinator.onVote(prepareVote(0, view))
    h.coordinator.onProposal(HotStuffProposal(view, blockId, None), height)
    h.myPrepareVotes shouldBe empty
    h.persisted shouldBe empty
  }

  "a happy-path proposal (view == height)" should "be voted for only after the block generator's own PREPARE vote arrives" in {
    val h = new Harness(hh => Option.when(hh == height)(addrs(0))) // generator of block 10 = member 0
    h.coordinator.onProposal(HotStuffProposal(height, blockId, None), height)
    h.myPrepareVotes shouldBe empty
    h.persisted shouldBe empty

    h.coordinator.onVote(prepareVote(3, height)) // member 3 is neither the generator nor leaderFor(10) = 2
    h.myPrepareVotes shouldBe empty
    h.persisted shouldBe empty

    h.coordinator.onVote(prepareVote(0, height)) // the generator's own vote authenticates the proposal
    h.myPrepareVotes.map(_.view) shouldBe List(height)
    h.persisted.toList shouldBe List(height)
  }

  it should "not be authenticated by a vote that claims the leader's slot for a different target height" in {
    // Height 11 belongs to a different committee in which slot 0 is held by member 3's key. A vote
    // signed for (view 10, blockId, height 11) by member 3 verifies as slot 0 of THAT committee, but
    // it is not the generator of block 10 voting for the block at height 10.
    val permuted = committee.map(g => if (g.index.toInt == 0) g.copy(address = addrs(3), blsPublicKey = kps(3).publicKey) else g)
    val h        = new Harness(hh => Option.when(hh == height)(addrs(0)), Some(hh => if (hh == height + 1) permuted else committee))
    h.coordinator.onVote(prepareVote(3, height, voteHeight = height + 1, asIndex = Some(0)))
    h.coordinator.onProposal(HotStuffProposal(height, blockId, None), height)
    h.myPrepareVotes shouldBe empty
    h.coordinator.onVote(prepareVote(3, height, voteHeight = height + 1, asIndex = Some(0)))
    h.myPrepareVotes shouldBe empty
    h.persisted shouldBe empty

    h.coordinator.onVote(prepareVote(0, height))
    h.myPrepareVotes.map(_.view) shouldBe List(height)
  }

  "a view-change proposal (view != height)" should "need the rotation leader's vote" in {
    val h    = new Harness(_ => None)
    val view = 7 // leaderFor(7) = 7 mod 4 = 3
    h.coordinator.onProposal(HotStuffProposal(view, blockId, None), height)
    h.coordinator.onVote(prepareVote(0, view))
    h.myPrepareVotes shouldBe empty

    h.coordinator.onVote(prepareVote(3, view))
    h.myPrepareVotes.map(_.view) shouldBe List(view)
    h.persisted.toList shouldBe List(view)
  }

  it should "also be authenticated by a leader vote that arrived before the proposal" in {
    val h    = new Harness(_ => None)
    val view = 7
    h.coordinator.onVote(prepareVote(3, view))
    h.coordinator.onProposal(HotStuffProposal(view, blockId, None), height)
    h.myPrepareVotes.map(_.view) shouldBe List(view)
  }

  "the parked-proposal buffer" should "keep room for a current-view proposal when it is full of far-future ones" in {
    val h = new Harness(_ => None)
    (0 until HotStuffCoordinator.Enabled.MaxPendingProposals).foreach { i =>
      h.coordinator.onProposal(HotStuffProposal(900 - i, ByteStr(Array.fill[Byte](32)(i.toByte)), None), height)
    }
    val view = 7 // leaderFor(7) = 3
    h.coordinator.onProposal(HotStuffProposal(view, blockId, None), height)
    h.coordinator.onVote(prepareVote(3, view))
    h.myPrepareVotes.map(_.view) shouldBe List(view)
  }
}
