package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffEngineSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private def blk(b: Byte): BlockId = ByteStr(Array.fill[Byte](32)(b))

  private val PREPARE    = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val PRE_COMMIT = HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT
  private val COMMIT     = HotStuffPhase.HOTSTUFF_PHASE_COMMIT

  // A real, BLS-valid QC signed by members 0,1,2 (75/100 stake >= 2/3).
  private def realQC(view: Int, phase: HotStuffPhase, block: BlockId, height: Int): QuorumCertificate = {
    val msg   = HotStuffQuorum.voteMessage(view, phase, block, height)
    val votes = Seq(0, 1, 2).map(i => HotStuffVote(view, phase, block, Height(height), i, kps(i).sign(msg).byteStr))
    HotStuffQuorum.formQC(votes, committee).toOption.get
  }

  private def forgedQC(view: Int, phase: HotStuffPhase, block: BlockId, height: Int): QuorumCertificate =
    QuorumCertificate(view, phase, block, Height(height), Seq(0, 1, 2), ByteStr(Array.fill[Byte](96)(0)))

  private val A    = blk(1)
  private val B    = blk(2)
  private val init = EngineState(committee)

  private def committed(as: Seq[HotStuffAction]): Boolean = as.exists { case _: HotStuffAction.Committed => true; case _ => false }

  "onQC" should "reject an unverifiable QC without changing state" in {
    val (s, actions) = HotStuffEngine.onQC(init, forgedQC(1, COMMIT, A, 10))
    s should be(init)
    actions.exists { case _: HotStuffAction.Rejected => true; case _ => false } should be(true)
  }

  it should "lock and advance the view on a valid PRE_COMMIT QC, without committing" in {
    val (s, actions) = HotStuffEngine.onQC(init, realQC(5, PRE_COMMIT, A, 100))
    s.safety.lockedQC.map(_.view) should be(Some(5))
    s.committedBlockId should be(None)
    committed(actions) should be(false)
  }

  it should "commit on a valid COMMIT QC" in {
    val (s, actions) = HotStuffEngine.onQC(init, realQC(6, COMMIT, A, 100))
    s.committedBlockId should be(Some(A))
    s.committedHeight should be(100)
    actions.contains(HotStuffAction.Committed(A, 100)) should be(true)
  }

  it should "not re-commit a lower/equal height (monotonic commit)" in {
    val (s1, _)       = HotStuffEngine.onQC(init, realQC(6, COMMIT, A, 100))
    val (s2, actions) = HotStuffEngine.onQC(s1, realQC(7, COMMIT, B, 99))
    s2.committedHeight should be(100)
    committed(actions) should be(false)
  }

  "onProposal" should "reject a proposal whose justify QC does not verify" in {
    val (s, vote) = HotStuffEngine.onProposal(init, HotStuffProposal(5, A, Some(forgedQC(4, PREPARE, A, 99))), (_, _) => true)
    vote should be(false)
    s should be(init)
  }

  it should "vote for a safe proposal and record the vote" in {
    val (s, vote) = HotStuffEngine.onProposal(init, HotStuffProposal(1, A, None), (_, _) => true)
    vote should be(true)
    s.safety.lastVotedView should be(1)
  }

  it should "refuse a conflicting proposal while locked with a stale justify (safety)" in {
    val (locked, _) = HotStuffEngine.onQC(init, realQC(5, PRE_COMMIT, A, 100))
    val (_, vote)   = HotStuffEngine.onProposal(locked, HotStuffProposal(6, B, None), (_, _) => false)
    vote should be(false)
  }

  "onTimeout" should "advance the view (never halts)" in {
    val (s, actions) = HotStuffEngine.onTimeout(init.copy(pacemaker = PacemakerState(3)))
    s.pacemaker.view should be(4)
    actions should contain(HotStuffAction.EnteredView(4))
  }
}
