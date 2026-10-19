package com.decentralchain.finalization.conflict

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

class HotStuffProofValidOverlapSuite extends BaseFinalizationSpec {
  private val miner     = TxHelpers.signer(0)
  private val offender  = TxHelpers.signer(2)
  private val offIdx    = GeneratorIndex(2)
  private val committee = Seq(miner, TxHelpers.signer(1), offender, TxHelpers.signer(3))

  private def settings(hardening: Int) =
    DomainPresets.DeterministicFinality
      .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
      .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0, finalityHardeningHeight = hardening))

  private def vote(signer: KeyPair, b: Byte): HotStuffVote = {
    val phase   = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
    val blockId = ByteStr(Array.fill(32)(b))
    val sig     = BlsKeyPair(signer.privateKey).sign(HotStuffQuorum.voteMessage(5, phase, blockId, 10, 1), HotStuffQuorum.VoteDst)
    HotStuffVote(5, phase, blockId, Height(10), offIdx.toInt, ByteStr(sig.arr), 1)
  }
  private val proof = HotStuffEquivocationProof(vote(offender, 1), vote(offender, 2))

  private def appendOverlappingBlock3(hardening: Int)(check: Either[?, ?] => Unit): Unit =
    withDomain(settings(hardening), AddrWithBalance.enoughBalances(committee*)) { d =>
      val commits = committee.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = commits, generator = miner, strictTime = true))
      val parentId    = d.lastBlock.id()
      val finalizedId = d.blockchain.blockId(1).get
      val voting      = mkFinalizationVoting(valid = Seq(offIdx)).signed(parentId, finalizedId, offender).copy(hotstuffConflicts = Seq(proof))
      val block3      = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = miner, strictTime = true, finalizationVoting = Some(voting))
      check(d.appender.appendBlockWithoutFallback(block3))
    }

  "from the hardening height a voting that lists a voter as valid AND carries its equivocation proof is rejected" in
    appendOverlappingBlock3(hardening = 3) { r =>
      r.isLeft shouldBe true
      r.left.toOption.get.toString should include("carries both a valid endorsement and an equivocation proof")
    }

  "below the hardening height the same block is still accepted (existing history replays unchanged)" in
    appendOverlappingBlock3(hardening = 4)(r => r should beRight)
}
