package com.decentralchain.finalization

import com.decentralchain.block.{Block, FinalizationVoting}
import com.decentralchain.crypto.bls.BlsUtils
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.*
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.{FreeSpec, produce}
import com.decentralchain.transaction.TxHelpers

import scala.compiletime.uninitialized

/** Task 6 (audit H2): the aggregated endorsement in `validateFinalizationVoting`
  * (`BlsUtils.verifyAgg`) and the conflicting-endorsement path (`validateConflictingEndorsement` ->
  * `BlockEndorsement.signatureValid`) must both be gated on feature 30 (`BlockchainFeatures.BlsCryptoV2`)
  * at the height of the block CARRYING the voting -- `blockHeight = Height(blockchain.height + 1)`
  * inside `validateFinalizationVoting`, the same "containing block" height discipline task 5 used for
  * PoP verification. Pinned directly against `state/appender/package.scala`'s on-chain validation, the
  * same site `BlockValidationAfterFinalizationSpec` exercises.
  */
class BlsCryptoV2EndorsementSpec extends BaseFinalizationSpec {
  private val generationPeriodLength = 2

  private def settingsWithBlsV2At(h: Int) = DomainPresets.DeterministicFinality
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .configure(
      _.copy(
        generationPeriodLength = generationPeriodLength,
        lightNodeBlockFieldsAbsenceInterval = 0
      )
    )
    .setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> h)

  "activation boundary" - {
    "aggregated endorsement" - {
      "pre-activation (block height h-1): legacy accepted, v2 rejected" in new BaseTest(h = 4) {
        override def continue(d: Domain): Unit = {
          // block2WithCommitments lands at height 2; block3 (this test's target) lands at h-1 = 3,
          // i.e. one height before BlsCryptoV2 activates at h = 4.
          d.blockchain.height shouldBe 2

          val legacyBlock = d.createBlock(
            mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
              .signed(endorsedId = d.lastBlockId, finalizedId = genesisId, cryptoV2 = false, committedGenerator2)
          )
          d.appender.appendBlockWithoutFallback(legacyBlock) should beRight
        }
      }.run()

      "pre-activation (block height h-1): v2-signed aggregated endorsement rejected" in new BaseTest(h = 4) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val v2Block = d.createBlock(
            mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
              .signed(endorsedId = d.lastBlockId, finalizedId = genesisId, cryptoV2 = true, committedGenerator2)
          )
          d.appender.appendBlockWithoutFallback(v2Block) should produce("Wrong BLS signature")
        }
      }.run()

      "post-activation (block height h): v2 accepted, legacy rejected" in new BaseTest(h = 3) {
        override def continue(d: Domain): Unit = {
          // With activation h = 3, block3 (this test's target) lands exactly at the activation height.
          d.blockchain.height shouldBe 2

          val v2Block = d.createBlock(
            mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
              .signed(endorsedId = d.lastBlockId, finalizedId = genesisId, cryptoV2 = true, committedGenerator2)
          )
          d.appender.appendBlockWithoutFallback(v2Block) should beRight
        }
      }.run()

      "post-activation (block height h): legacy-signed aggregated endorsement rejected" in new BaseTest(h = 3) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val legacyBlock = d.createBlock(
            mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
              .signed(endorsedId = d.lastBlockId, finalizedId = genesisId, cryptoV2 = false, committedGenerator2)
          )
          d.appender.appendBlockWithoutFallback(legacyBlock) should produce("Wrong BLS signature")
        }
      }.run()
    }

    "conflicting endorsement" - {
      "pre-activation (block height h-1): legacy accepted, v2 rejected" in new BaseTest(h = 4) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val legacyBlock = d.createBlock(
            mkFinalizationVoting(finalizedHeight = GenesisBlockHeight)
              .withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlock.id(), GenesisBlockHeight, cryptoV2 = false)
          )
          d.appender.appendBlockWithoutFallback(legacyBlock) should beRight
        }
      }.run()

      "pre-activation (block height h-1): v2-signed conflicting endorsement rejected" in new BaseTest(h = 4) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val v2Block = d.createBlock(
            mkFinalizationVoting(finalizedHeight = GenesisBlockHeight)
              .withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlock.id(), GenesisBlockHeight, cryptoV2 = true)
          )
          d.appender.appendBlockWithoutFallback(v2Block) should produce("Invalid conflicting endorsement signature")
        }
      }.run()

      "post-activation (block height h): v2 accepted, legacy rejected" in new BaseTest(h = 3) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val v2Block = d.createBlock(
            mkFinalizationVoting(finalizedHeight = GenesisBlockHeight)
              .withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlock.id(), GenesisBlockHeight, cryptoV2 = true)
          )
          d.appender.appendBlockWithoutFallback(v2Block) should beRight
        }
      }.run()

      "post-activation (block height h): legacy-signed conflicting endorsement rejected" in new BaseTest(h = 3) {
        override def continue(d: Domain): Unit = {
          d.blockchain.height shouldBe 2

          val legacyBlock = d.createBlock(
            mkFinalizationVoting(finalizedHeight = GenesisBlockHeight)
              .withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlock.id(), GenesisBlockHeight, cryptoV2 = false)
          )
          d.appender.appendBlockWithoutFallback(legacyBlock) should produce("Invalid conflicting endorsement signature")
        }
      }.run()
    }
  }

  "cross-context: an aggregated endorsement's bytes signed under the _POP_ DST are rejected post-activation" in new BaseTest(h = 3) {
    override def continue(d: Domain): Unit = {
      d.blockchain.height shouldBe 2

      val finalizedId = genesisId
      val endorsedId  = d.lastBlockId
      val message     = com.decentralchain.block.BlockEndorsement.mkMessage(finalizedId, GenesisBlockHeight, endorsedId)
      val kp          = com.decentralchain.crypto.bls.BlsKeyPair(committedGenerator2.privateKey)
      val popSig      = kp.sign(message, BlsUtils.BlsPopDomainSeparationTagV2)

      val block = d.createBlock(
        mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
          .copy(aggregatedEndorsement = Some(popSig))
      )
      d.appender.appendBlockWithoutFallback(block) should produce("Wrong BLS signature")
    }
  }.run()

  private trait BaseTest(val h: Int) {
    val committedGenerator1     = TxHelpers.signer(0)
    val committedGenerator1Idx  = GeneratorIndex(0)

    val committedGenerator2     = TxHelpers.signer(1)
    val committedGenerator2Idx  = GeneratorIndex(1)

    val committedGenerator3 = TxHelpers.signer(2)

    val notCommittedGenerator = TxHelpers.signer(9)

    def committedGenerators = Seq(committedGenerator1, committedGenerator2, committedGenerator3)
    def allGenerators       = notCommittedGenerator +: committedGenerators

    def settings: DCCSettings = settingsWithBlsV2At(h)

    var genesisId: com.decentralchain.block.Block.BlockId = uninitialized

    def continue(d: Domain): Unit

    def run(): Unit = withDomain(settings, AddrWithBalance.enoughBalances(allGenerators*)) { d =>
      genesisId = d.blockchain.blockHeader(GenesisBlockHeight.toInt).value.id()

      val txs                   = committedGenerators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = notCommittedGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)

      continue(d)
    }

    extension (d: Domain) {
      def createBlock(finalizationVoting: FinalizationVoting): Block = d.createBlock(
        version = Block.ProtoBlockVersion,
        txs = Nil,
        generator = committedGenerator1,
        strictTime = true,
        finalizationVoting = Some(finalizationVoting)
      )
    }
  }
}
