package com.decentralchain.finalization.conflict

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.history.Domain
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

/** Same chain shape as HotStuffEquivocationPenaltySuite: block 2 commits, block 3 carries the proof
  * against member #2, block 4 is the first block with #2 excluded.
  */
class CommitteeQuorumWeightSuite extends BaseFinalizationSpec {
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

  private def run(hardening: Int)(check: Domain => Unit): Unit =
    withDomain(settings(hardening), AddrWithBalance.enoughBalances(committee*)) { d =>
      val commits = committee.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = commits, generator = miner, strictTime = true))
      d.appender.appendBlockWithoutFallback(
        d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = miner,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
      ) should beRight
      // A transfer in block 4 moves live balance but not generating balance.
      d.appender.appendBlockWithoutFallback(
        d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Seq(TxHelpers.transfer(TxHelpers.signer(1), TxHelpers.address(90), 1000)),
          generator = miner,
          strictTime = true
        )
      ) should beRight
      check(d)
    }

  "at or above the hardening height" - {
    "a proven equivocator weighs 0 and the others weigh their generating balance at the target's parent" in run(hardening = 1) { d =>
      val set = d.blockchain.committedGeneratorSetAt(Height(4))
      set.find(_.index == offIdx).map(_.balance) shouldBe Some(0L)
      val ref = d.blockchain.blockId(3)
      set.filterNot(_.index == offIdx).foreach { g => g.balance shouldBe d.blockchain.generatingBalance(g.address, ref) }
    }
  }

  "below the hardening height" - {
    "the previous live-balance weighting is kept" in run(hardening = 1000) { d =>
      val set = d.blockchain.committedGeneratorSetAt(Height(4))
      set.foreach(g => g.balance shouldBe d.blockchain.balance(g.address))
    }
  }
}
