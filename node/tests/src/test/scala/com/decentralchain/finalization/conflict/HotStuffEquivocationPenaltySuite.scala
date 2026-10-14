package com.decentralchain.finalization.conflict

import com.decentralchain.TestValues
import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.block.serialization.BlockHeaderSerializer
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.history.Domain
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.{GeneratorIndex, Height, Portfolio}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.CommitToGenerationTransaction.DepositInDcclets
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase
import play.api.libs.json.JsArray

/** A HotStuff equivocator forfeits its generation deposit, exactly like a T0 conflicting endorser,
  * once the proof-carrying block is at or above `hotStuffEquivocationPenaltyHeight`.
  *
  * Live testnet 2026-10-13 (equivocation drill, TESTNET-FINAL-PLAN Task 6.5): the proof in block
  * 56354 excluded the offender from 56355 on and released its deposit lock, but never debited the
  * deposit -- calculatePenalties only folded T0 `conflict` endorsements, so the offender got its
  * deposit back.
  */
class HotStuffEquivocationPenaltySuite extends BaseFinalizationSpec {
  private val miner     = TxHelpers.signer(0)
  private val offender  = TxHelpers.signer(2)
  private val offIdx    = GeneratorIndex(2)
  private val committee = Seq(miner, TxHelpers.signer(1), offender, TxHelpers.signer(3))

  // generationPeriodLength = 2: block 3 is the first block of period index 1, the period the
  // committee commits to in block 2. Block 3 carries the proof, block 4 applies the penalty.
  private def settings(penaltyHeight: Int) =
    DomainPresets.DeterministicFinality
      .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
      .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0, hotStuffEquivocationPenaltyHeight = penaltyHeight))

  private val periodIndex = 1

  private def vote(signer: KeyPair, blockIdByte: Byte): HotStuffVote = {
    val phase   = HotStuffPhase.HOTSTUFF_PHASE_PREPARE
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val sig     = BlsKeyPair(signer.privateKey).sign(HotStuffQuorum.voteMessage(5, phase, blockId, 10, periodIndex), HotStuffQuorum.VoteDst)
    HotStuffVote(5, phase, blockId, Height(10), offIdx.toInt, ByteStr(sig.arr), periodIndex)
  }

  private val proof = HotStuffEquivocationProof(vote(offender, 1), vote(offender, 2))

  private val afterCommit = ENOUGH_AMT - TestValues.commitToGenerationFee

  /** Appends blocks 2 (commitments), 3 (proof) and 4 (penalty), checking the offender before block 4. */
  private def run(penaltyHeight: Int)(after4: (Domain, Block) => Unit): Unit =
    withDomain(settings(penaltyHeight), AddrWithBalance.enoughBalances(committee*)) { d =>
      val commits = committee.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = commits, generator = miner, strictTime = true))

      val block3 = d.createBlock(
        version = Block.ProtoBlockVersion,
        txs = Nil,
        generator = miner,
        strictTime = true,
        finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
      )
      d.appender.appendBlockWithoutFallback(block3) should beRight
      d.blockchain.dccPortfolio(offender.toAddress) shouldBe Portfolio(balance = afterCommit, generationDeposit = DepositInDcclets)

      d.appender.appendBlockWithoutFallback(
        d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = miner, strictTime = true)
      ) should beRight
      d.blockchain.height shouldBe 4
      after4(d, block3)
    }

  "HotStuff equivocator forfeits its generation deposit when the proof is at the penalty height" in run(penaltyHeight = 3) { (d, _) =>
    d.blockchain.dccPortfolio(offender.toAddress) shouldBe Portfolio(balance = afterCommit - DepositInDcclets)
    d.blockchain.balanceAtHeight(offender.toAddress, 4).value shouldBe (4, afterCommit - DepositInDcclets)
  }

  "HotStuff equivocator keeps its deposit when the proof is below the penalty height (pre-activation history)" in run(penaltyHeight = 4) { (d, _) =>
    d.blockchain.dccPortfolio(offender.toAddress) shouldBe Portfolio(balance = afterCommit)
  }

  "block JSON shows the carried equivocation proof" in run(penaltyHeight = 3) { (_, block3) =>
    val conflicts = (BlockHeaderSerializer.toJson(block3.header) \ "finalizationVoting" \ "hotstuffConflicts").as[JsArray].value
    conflicts.size shouldBe 1
    (conflicts.head \ "voterIndex").as[Int] shouldBe offIdx.toInt
    (conflicts.head \ "committeeEpoch").as[Int] shouldBe periodIndex
    (conflicts.head \ "phase").as[String] shouldBe "HOTSTUFF_PHASE_PREPARE"
    (conflicts.head \ "voteA" \ "blockId").as[String] shouldBe proof.voteA.blockId.toString
    (conflicts.head \ "voteB" \ "blockId").as[String] shouldBe proof.voteB.blockId.toString
  }
}
