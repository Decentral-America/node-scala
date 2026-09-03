package com.decentralchain.finalization.conflict

import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils}
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalactic.source.Position

class MultipleConflictEndorserSuite extends BaseFinalizationSpec {
  private val validGenerator = TxHelpers.signer(0)

  private val conflictGenerator1     = TxHelpers.signer(1)
  private val conflictGenerator1Idx  = GeneratorIndex(1)
  private val conflictGenerator2     = TxHelpers.signer(2)
  private val conflictGenerator2Addr = conflictGenerator2.toAddress
  private val conflictGenerator2Idx  = GeneratorIndex(2)

  private val baseSettings    = DomainPresets.DeterministicFinality.addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
  private val defaultSettings = baseSettings.configure(
    _.copy(
      generationPeriodLength = 2,
      lightNodeBlockFieldsAbsenceInterval = 0
    )
  )

  private val generators = Seq(validGenerator, conflictGenerator1, conflictGenerator2)

  "saved conflict endorsers" in withDomain(defaultSettings, AddrWithBalance.enoughBalances(generators*)) { d =>
    log.debug(s"Append block 2 with commitments")
    val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
    val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = validGenerator, strictTime = true)
    d.appender.appendBlock(block2WithCommitments)

    val block3 = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = validGenerator, strictTime = true)
    log.debug(s"Append block 3")
    d.appender.appendBlock(block3)

    log.debug(s"Append microblock with conflict endorsement")
    def appendConflictEndorsements(): Unit = {
      val microBlockWithTxn = d.createMicroBlock(
        signer = Some(validGenerator),
        finalizationVoting = Some(
          mkFinalizationVoting()
            .withConflict(conflictGenerator1, conflictGenerator1Idx, block2WithCommitments.id())
            .withConflict(conflictGenerator2, conflictGenerator2Idx, block2WithCommitments.id())
        )
      )(TxHelpers.transfer(conflictGenerator1, conflictGenerator2Addr))
      d.appendMicroBlock(microBlockWithTxn)
    }
    appendConflictEndorsements()

    def checkConflictGenerators(
        at: Int = d.blockchain.height,
        expected: Set[GeneratorIndex] = Set(conflictGenerator1Idx, conflictGenerator2Idx)
    ): Unit = {
      val period = d.blockchain.generationPeriodOf(Height(at)).value
      d.blockchain.conflictGenerators(period).all shouldBe expected
    }
    checkConflictGenerators()

    log.debug("Append block 4")
    val block4Txs = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(5), x))
    val block4    = d.createBlock(version = Block.ProtoBlockVersion, txs = block4Txs, generator = validGenerator, strictTime = true)
    d.appender.appendBlock(block4)
    checkConflictGenerators()

    log.debug("Append block 5 of new period")
    d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = validGenerator, strictTime = true))
    checkConflictGenerators(expected = Set.empty)

    log.debug("Append block 6")
    d.appender.appendBlock(d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = validGenerator, strictTime = true))
    checkConflictGenerators(at = 4)
    checkConflictGenerators(expected = Set.empty)

    appendConflictEndorsements()
    checkConflictGenerators()
  }

  "hotstuff equivocation proof voter survives the key-block boundary [C1 regression]" in {
    withDomain(defaultSettings, AddrWithBalance.enoughBalances(generators*)) { d =>
      log.debug("Append block 2 with commitments")
      val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = validGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)

      def signedVote(signer: com.decentralchain.account.KeyPair, voterIndex: Int, view: Int, blockIdByte: Byte, epoch: Int): HotStuffVote = {
        val blockId = ByteStr(Array.fill(32)(blockIdByte))
        val height  = Height(10)
        val msg     = HotStuffQuorum.voteMessage(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, height.toInt, epoch)
        val kp      = BlsKeyPair(signer.privateKey)
        HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, height, voterIndex, ByteStr(kp.sign(msg, BlsUtils.BlsHsVoteDomainSeparationTag).arr), epoch)
      }

      val periodIndex = d.blockchain.generationPeriodOf(Height(3)).value.index
      val proof = HotStuffEquivocationProof(
        signedVote(conflictGenerator1, conflictGenerator1Idx.toInt, view = 5, blockIdByte = 1, epoch = periodIndex),
        signedVote(conflictGenerator1, conflictGenerator1Idx.toInt, view = 5, blockIdByte = 2, epoch = periodIndex)
      )

      log.debug("Append key block 3 with a hotstuff equivocation proof (no T0 conflict)")
      val block3 = d.createBlock(
        version = Block.ProtoBlockVersion,
        txs = Nil,
        generator = validGenerator,
        strictTime = true,
        finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
      )
      d.appender.appendBlock(block3)

      val period = d.blockchain.generationPeriodOf(Height(3)).value
      d.blockchain.conflictGenerators(period).upTo(Height(3)) shouldBe Set(conflictGenerator1Idx)

      log.debug("Append block 4 -- exclusion must survive past the block that carried the proof")
      val block4 = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = validGenerator, strictTime = true)
      d.appender.appendBlock(block4)

      d.blockchain.conflictGenerators(period).upTo(Height(4)) shouldBe Set(conflictGenerator1Idx)
    }
  }
}
