package com.decentralchain.state.appender

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils}
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.network.HotStuffVote
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

/** Task 8 (audit H2 follow-up): `validateHotStuffEquivocationProofs` must verify a block-carried
  * proof's embedded votes under the DST that was live when they were cast, derived from the
  * CONTAINING block's height (never the live tip -- proofs are consensus-replayed). Additionally,
  * any proof carried by a block in the SAME generation period as feature 30's activation height must
  * be refused outright, since honest signers may legitimately disagree about their live
  * `supportsBlsCryptoV2()` tip answer during that period. Modelled on
  * `HotStuffEquivocationValidationSpecification` (same `withCommittedCommittee` harness,
  * `generationPeriodLength = 2`).
  */
class BlsCryptoV2EquivocationProofBoundarySpec extends BaseFinalizationSpec {
  private val minerGenerator = TxHelpers.signer(0)
  private val voterA         = TxHelpers.signer(1)
  private val voterB         = TxHelpers.signer(2)
  private val voterC         = TxHelpers.signer(3)

  private val voterAIdx = GeneratorIndex(1)
  private val voterBIdx = GeneratorIndex(2)
  private val voterCIdx = GeneratorIndex(3)

  private val generators = Seq(minerGenerator, voterA, voterB, voterC)

  private val generationPeriodLength = 2
  private val prepare                = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private def settingsWithBlsV2At(h: Int): DCCSettings = DomainPresets.DeterministicFinality
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .configure(
      _.copy(
        generationPeriodLength = generationPeriodLength,
        lightNodeBlockFieldsAbsenceInterval = 0
      )
    )
    .setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> h)

  private def signedVote(signer: KeyPair, voterIndex: Int, view: Int, blockIdByte: Byte, epoch: Int, dst: String): HotStuffVote = {
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val height  = Height(10)
    val msg     = HotStuffQuorum.voteMessage(view, prepare, blockId, height.toInt, epoch)
    val kp      = BlsKeyPair(signer.privateKey)
    HotStuffVote(view, prepare, blockId, height, voterIndex, ByteStr(kp.sign(msg, dst).arr), epoch)
  }

  private def proofFor(signer: KeyPair, voterIndex: Int, epoch: Int, dst: String, view: Int = 5): HotStuffEquivocationProof =
    HotStuffEquivocationProof(
      signedVote(signer, voterIndex, view, 1, epoch, dst),
      signedVote(signer, voterIndex, view, 2, epoch, dst)
    )

  /** Commits `generators`, so at the period covering block height 3 they form the committee at
    * indexes matching `voterAIdx`/`voterBIdx`/`voterCIdx` above (miner is index 0). Returns the
    * domain positioned right after block 2 (commitments), ready to append block 3 carrying the FV
    * under test. With `generationPeriodLength = 2` and `DeterministicFinality` activation at height 0,
    * block height 3 falls in the SECOND period, index 1.
    */
  private def withCommittedCommittee(settings: DCCSettings, commitCryptoV2: Boolean = false)(
      test: (com.decentralchain.history.Domain, Block) => Unit
  ): Unit =
    withDomain(settings, AddrWithBalance.enoughBalances(generators*)) { d =>
      val txs = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x, cryptoV2 = commitCryptoV2))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = minerGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)
      test(d, block2WithCommitments)
    }

  private val periodIndex = 1 // block 3's generation period index, per withCommittedCommittee's doc

  private def appendProofBlock(d: com.decentralchain.history.Domain, proof: HotStuffEquivocationProof) = {
    val block3 = d.createBlock(
      version = Block.ProtoBlockVersion,
      txs = Nil,
      generator = minerGenerator,
      strictTime = true,
      finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
    )
    d.appender.appendBlockWithoutFallback(block3)
  }

  "BlsCryptoV2 equivocation-proof DST + activation-boundary rule" - {

    "1. containing block BELOW activation: legacy-signed proof accepted, v2-signed rejected" in
      withCommittedCommittee(settingsWithBlsV2At(100)) { (d, _) =>
        // Block 3 (this test's target) is far below activation height 100.
        d.blockchain.height shouldBe 2

        val legacyProof = proofFor(voterA, voterAIdx.toInt, periodIndex, BlsUtils.BlsDomainSeparationTag)
        appendProofBlock(d, legacyProof) should beRight

        // Different voter (B), since A was already excluded by the accepted proof above.
        val v2Proof = proofFor(voterB, voterBIdx.toInt, periodIndex, BlsUtils.BlsHsVoteDomainSeparationTagV2)
        val result  = appendProofBlock(d, v2Proof)
        result.isLeft shouldBe true
        result.left.value.toString should include("signature invalid for voter")
      }

    "2. containing block in a period STRICTLY AFTER activation: v2-signed accepted, legacy rejected" in
      withCommittedCommittee(settingsWithBlsV2At(1), commitCryptoV2 = true) { (d, _) =>
        // Activation height 1 falls in period 0 (blocks [1,2]); block 3 falls in period 1, strictly after.
        d.blockchain.height shouldBe 2

        val v2Proof = proofFor(voterA, voterAIdx.toInt, periodIndex, BlsUtils.BlsHsVoteDomainSeparationTagV2)
        appendProofBlock(d, v2Proof) should beRight

        val legacyProof = proofFor(voterB, voterBIdx.toInt, periodIndex, BlsUtils.BlsDomainSeparationTag)
        val result       = appendProofBlock(d, legacyProof)
        result.isLeft shouldBe true
        result.left.value.toString should include("signature invalid for voter")
      }

    "3. containing block in the SAME generation period as activation: rejected under either DST, mentions activation period" in
      withCommittedCommittee(settingsWithBlsV2At(3)) { (d, _) =>
        // Activation height 3 == block 3's own height, so block 3's generation period (index 1) IS the
        // activation period.
        d.blockchain.height shouldBe 2

        val legacyProof = proofFor(voterA, voterAIdx.toInt, periodIndex, BlsUtils.BlsDomainSeparationTag)
        val legacyResult = appendProofBlock(d, legacyProof)
        legacyResult.isLeft shouldBe true
        legacyResult.left.value.toString should include("activation period")

        val v2Proof = proofFor(voterA, voterAIdx.toInt, periodIndex, BlsUtils.BlsHsVoteDomainSeparationTagV2)
        val v2Result = appendProofBlock(d, v2Proof)
        v2Result.isLeft shouldBe true
        v2Result.left.value.toString should include("activation period")
      }

    "4. fresh chain (feature 30 pre-activated at/before feature 29's height 1): boundary rule is a no-op, legitimate proof accepted" in
      withCommittedCommittee(settingsWithBlsV2At(1), commitCryptoV2 = true) { (d, _) =>
        // Feature 29 (DeterministicFinality/HotStuffEquivocationEvidence) and feature 30 (BlsCryptoV2)
        // are both pre-activated at height 1 here, so there is no generation period in which a proof
        // could exist that also equals the activation period -- the boundary rule must cost nothing.
        d.blockchain.height shouldBe 2

        val proof = proofFor(voterC, voterCIdx.toInt, periodIndex, BlsUtils.BlsHsVoteDomainSeparationTagV2)
        appendProofBlock(d, proof) should beRight
      }
  }
}
