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

/** The aggregated endorsement in `validateFinalizationVoting` (`BlsUtils.verifyAgg`) and the
  * conflicting-endorsement path (`validateConflictingEndorsement` -> `BlockEndorsement.signatureValid`)
  * both verify unconditionally under `BlsUtils.BlsEndorseDomainSeparationTag`. Pinned directly against
  * `state/appender/package.scala`'s on-chain validation, the same site `BlockValidationAfterFinalizationSpec`
  * exercises.
  */
class BlockEndorsementDstSpec extends BaseFinalizationSpec {
  private val generationPeriodLength = 2

  private def settings = DomainPresets.DeterministicFinality
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .configure(
      _.copy(
        generationPeriodLength = generationPeriodLength,
        lightNodeBlockFieldsAbsenceInterval = 0
      )
    )

  "aggregated endorsement" - {
    "correctly-signed endorsement accepted" in new BaseTest {
      override def continue(d: Domain): Unit = {
        d.blockchain.height shouldBe 2

        val block = d.createBlock(
          mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
            .signed(endorsedId = d.lastBlockId, finalizedId = genesisId, committedGenerator2)
        )
        d.appender.appendBlockWithoutFallback(block) should beRight
      }
    }.run()
  }

  "conflicting endorsement" - {
    "correctly-signed conflicting endorsement accepted" in new BaseTest {
      override def continue(d: Domain): Unit = {
        d.blockchain.height shouldBe 2

        val block = d.createBlock(
          mkFinalizationVoting(finalizedHeight = GenesisBlockHeight)
            .withConflict(committedGenerator2, committedGenerator2Idx, d.lastBlock.id(), GenesisBlockHeight)
        )
        d.appender.appendBlockWithoutFallback(block) should beRight
      }
    }.run()
  }

  "cross-context: an aggregated endorsement's bytes signed under the _POP_ DST are rejected" in new BaseTest {
    override def continue(d: Domain): Unit = {
      d.blockchain.height shouldBe 2

      val finalizedId = genesisId
      val endorsedId  = d.lastBlockId
      val message     = com.decentralchain.block.BlockEndorsement.mkMessage(finalizedId, GenesisBlockHeight, endorsedId)
      val kp          = com.decentralchain.crypto.bls.BlsKeyPair(committedGenerator2.privateKey)
      val popSig      = kp.sign(message, BlsUtils.BlsPopDomainSeparationTag)

      val block = d.createBlock(
        mkFinalizationVoting(valid = Seq(committedGenerator2Idx))
          .copy(aggregatedEndorsement = Some(popSig))
      )
      d.appender.appendBlockWithoutFallback(block) should produce("Wrong BLS signature")
    }
  }.run()

  private trait BaseTest {
    val committedGenerator1     = TxHelpers.signer(0)
    val committedGenerator1Idx  = GeneratorIndex(0)

    val committedGenerator2     = TxHelpers.signer(1)
    val committedGenerator2Idx  = GeneratorIndex(1)

    val committedGenerator3 = TxHelpers.signer(2)

    val notCommittedGenerator = TxHelpers.signer(9)

    def committedGenerators = Seq(committedGenerator1, committedGenerator2, committedGenerator3)
    def allGenerators       = notCommittedGenerator +: committedGenerators

    def settings: DCCSettings = BlockEndorsementDstSpec.this.settings

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
