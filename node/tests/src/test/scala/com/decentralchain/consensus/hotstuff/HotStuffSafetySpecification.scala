package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, QuorumCertificate}
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffSafetySpecification extends FlatSpec {
  private def bid(b: Byte): BlockId = ByteStr(Array.fill[Byte](32)(b))
  private val sig                   = ByteStr(Array.fill[Byte](96)(1)) // signature content irrelevant to safety rules

  private def qc(view: Int, phase: HotStuffPhase, block: BlockId): QuorumCertificate =
    QuorumCertificate(view, phase, block, Height(view + 1), Seq(0, 1, 2), sig)

  private val A  = bid(1)
  private val A2 = bid(3) // extends A
  private val B  = bid(2) // conflicting branch

  // Injected ancestry: a block extends itself, and A2 extends A. Nothing else is related.
  private def extendsBranch(child: BlockId, ancestor: BlockId): Boolean =
    child == ancestor || (child == A2 && ancestor == A)

  private val PREPARE    = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
  private val PRE_COMMIT = HotStuffPhase.HOTSTUFF_PHASE_PRE_COMMIT
  private val COMMIT     = HotStuffPhase.HOTSTUFF_PHASE_COMMIT

  "safeToVote" should "allow voting when nothing is locked" in {
    HotStuffSafety.safeToVote(HotStuffProposal(1, A, None), SafetyState(), extendsBranch) should be(true)
  }

  it should "allow voting when the proposal extends the locked branch (safety path)" in {
    val locked = SafetyState(lockedQC = Some(qc(5, PRE_COMMIT, A)))
    HotStuffSafety.safeToVote(HotStuffProposal(6, A2, None), locked, extendsBranch) should be(true)
  }

  it should "allow a conflicting branch only when the justify QC is newer than the lock (liveness path)" in {
    val locked = SafetyState(lockedQC = Some(qc(5, PRE_COMMIT, A)))
    HotStuffSafety.safeToVote(HotStuffProposal(8, B, Some(qc(7, PREPARE, B))), locked, extendsBranch) should be(true)
  }

  it should "REFUSE a conflicting branch with a stale justify (core safety guarantee)" in {
    val locked = SafetyState(lockedQC = Some(qc(5, PRE_COMMIT, A)))
    HotStuffSafety.safeToVote(HotStuffProposal(6, B, Some(qc(4, PREPARE, B))), locked, extendsBranch) should be(false)
  }

  it should "refuse to double-vote or vote in a non-advancing view" in {
    val voted = SafetyState(lastVotedView = 6)
    HotStuffSafety.safeToVote(HotStuffProposal(6, A, None), voted, extendsBranch) should be(false)
    HotStuffSafety.safeToVote(HotStuffProposal(5, A, None), voted, extendsBranch) should be(false)
  }

  "update" should "lock only on a higher-view PRE_COMMIT QC and never regress" in {
    val s1 = HotStuffSafety.update(qc(5, PRE_COMMIT, A), SafetyState())
    s1.lockedQC.map(_.view) should be(Some(5))
    val s2 = HotStuffSafety.update(qc(3, PRE_COMMIT, B), s1) // lower view must not regress the lock
    s2.lockedQC.map(_.view) should be(Some(5))
    val s3 = HotStuffSafety.update(qc(9, PREPARE, B), s2) // PREPARE must not lock
    s3.lockedQC.map(_.view) should be(Some(5))
    s3.prepareQC.map(_.view) should be(Some(9)) // but prepareQC tracks the highest-view QC
  }

  "committedBlock" should "finalize only on a COMMIT QC" in {
    HotStuffSafety.committedBlock(qc(5, COMMIT, A)) should be(Some(A))
    HotStuffSafety.committedBlock(qc(5, PRE_COMMIT, A)) should be(None)
    HotStuffSafety.committedBlock(qc(5, PREPARE, A)) should be(None)
  }

  "equivocators" should "flag a voter signing two different blocks at the same view+phase" in {
    def v(voter: Int, block: BlockId) = HotStuffVote(5, PREPARE, block, Height(6), voter, sig)
    HotStuffSafety.equivocators(Seq(v(0, A), v(0, B), v(1, A))) should be(Set(0))
    HotStuffSafety.equivocators(Seq(v(0, A), v(1, A))) should be(Set.empty[Int])
  }
}
