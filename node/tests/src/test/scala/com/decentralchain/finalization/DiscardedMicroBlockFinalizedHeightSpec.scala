package com.decentralchain.finalization

import com.decentralchain.block.Block
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.NumericExt
import com.decentralchain.transaction.TxHelpers

/** Task D (docs/superpowers/plans/2026-08-30-testnet-final.md): the finalization-state rollback bug.
  * Two intertwined defects, both inherited unmodified from upstream Waves:
  *
  *   1. `FinalizationState.append` advances `finalizedHeight` whenever a microblock's cumulative
  *      voting first satisfies `isParentFinalized`. If that specific microblock is later discarded
  *      (the next key block references an EARLIER microblock instead), the advanced `finalizedHeight`
  *      never rolls back -- `BlockchainUpdaterImpl` persists whatever `ng.finalizationState`
  *      happens to be at block-append time, not the value as of the block actually being persisted.
  *   2. `NgState.forgeBlock` keeps folding discarded microblocks' `finalizationVoting` into the
  *      forged block's header, corrupting that block's own signature.
  *
  * Both arms below produce the IDENTICAL canonical chain (block2, block3+micro1, block4) -- they
  * only differ in whether a since-discarded microblock's vote was briefly seen. A correct
  * implementation must produce identical `finalizedHeightAt` results and a valid block4 signature in
  * both arms; a real cross-node divergence (or an outright rejected/stalled block) proves the bug.
  */
class DiscardedMicroBlockFinalizedHeightSpec extends BaseFinalizationSpec {
  private val generator1    = TxHelpers.signer(0)
  private val generator1Idx = GeneratorIndex(0)

  private val generator2     = TxHelpers.signer(1)
  private val generator2Addr = generator2.toAddress

  private val generator3 = TxHelpers.signer(2)

  private val baseSettings    = DomainPresets.DeterministicFinality.addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
  private val defaultSettings = baseSettings.configure(
    _.copy(
      generationPeriodLength = 2,
      lightNodeBlockFieldsAbsenceInterval = 0,
      maxValidEndorsers = 1
    )
  )

  private val generators = Seq(generator1, generator2, generator3)

  private val initBalances = Seq(
    AddrWithBalance(generator1.toAddress, 5000.dcc),
    AddrWithBalance(generator2.toAddress, 2000.dcc),
    AddrWithBalance(generator3.toAddress, 3000.dcc)
  )

  // Runs the scenario. If `appendFinalizingMicro` is true, a second microblock carrying
  // the endorsement that flips parentFinalized is appended and then DISCARDED by the next
  // key block (which references the FIRST microblock). Otherwise that microblock never exists.
  // In BOTH cases the resulting canonical chain is identical: block2, block3+micro1, block4.
  private def run(appendFinalizingMicro: Boolean, verifyBlock: Boolean = true): (Option[Int], Option[Int]) = {
    var result: (Option[Int], Option[Int]) = (None, None)
    withDomain(defaultSettings, initBalances) { d =>
      val genesisBlockId = d.blockchain.lastBlockId.value

      val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), sender = x))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = generator2, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)

      val block3 = d.createBlock(version = Block.ProtoBlockVersion, txs = Nil, generator = generator2, strictTime = true)
      d.appender.appendBlock(block3)

      log.debug("Append micro1 WITHOUT any endorsement (stays on the canonical chain)")
      val micro1   = d.createMicroBlock(signer = Some(generator2))(TxHelpers.transfer(generator1, generator2Addr))
      val micro1Id = d.appendMicroBlock(micro1)

      if (appendFinalizingMicro) {
        log.debug("Append micro2 WITH the endorsement that reaches finalization (will be discarded)")
        val micro2 = d.createMicroBlock(
          signer = Some(generator2),
          finalizationVoting = Some(
            mkFinalizationVoting(valid = Seq(generator1Idx))
              .signed(endorsedId = block3.header.reference, finalizedId = genesisBlockId, validEndorsers = generator1)
          )
        )(TxHelpers.transfer(generator1, generator2Addr))
        d.appendMicroBlockE(micro2) should beRight
      }

      d.liquidState.foreach { ng =>
        val forged = ng.liquidBlockOf(micro1Id)
        log.debug(
          s"ng.base=${ng.base.id()} microBlockIds=${ng.microBlockIds} micro1Id=$micro1Id " +
            s"forgedAtMicro1 defined=${forged.isDefined} sigValid=${forged.map(_.block.signatureValid())} discarded=${forged.map(_.discarded.size)}"
        )
        // The block forged AT micro1 (the one block4 will reference) must have a valid signature
        // regardless of whether a later, since-discarded microblock (micro2) carried voting data --
        // forgeBlock must not fold micro2's finalizationVoting into micro1's forged header.
        withClue(s"forgeBlock($micro1Id).signatureValid() with appendFinalizingMicro=$appendFinalizingMicro: ") {
          forged.map(_.block.signatureValid()) shouldBe Some(true)
        }
      }

      log.debug(s"Append block 4 referencing micro1 ($micro1Id)")
      val block4 = d.createBlock(
        version = Block.ProtoBlockVersion,
        txs = Nil,
        ref = Some(micro1Id),
        generator = generator2,
        strictTime = true
      )
      val block4Result =
        if (verifyBlock) d.appender.appendBlockWithoutFallback(block4)
        else {
          d.appender.adjustTime(block4)
          val hs = d.posSelector.validateGenerationSignature(block4).getOrElse(block4.header.generationSignature)
          d.blockchainUpdater.processBlock(
            block4,
            hs,
            None,
            d.blockchainUpdater.currentGeneratorSet.getOrElse(Seq.empty),
            None,
            verify = false,
            txSignParCheck = false
          )
        }
      log.debug(s"block4 append result: $block4Result")

      val persistedAt3 = d.blockchain.finalizedHeightAt(Height(3)).map(_.toInt)
      val current      = d.blockchain.finalizedHeight.map(_.toInt)
      log.debug(
        s"appendFinalizingMicro=$appendFinalizingMicro verify=$verifyBlock height=${d.blockchain.height} " +
          s"finalizedHeightAt(3)=$persistedAt3 finalizedHeight=$current lastBlockRef=${d.blockchain.lastBlockHeader.map(_.header.reference)}"
      )
      result = (persistedAt3, current)
    }
    result
  }

  "persisted finalizedHeight after a microblock fork (verify=true)" in {
    val withDiscarded = run(appendFinalizingMicro = true)
    val withoutIt     = run(appendFinalizingMicro = false)

    log.debug(s"verify=true  WITH discarded finalizing microblock: $withDiscarded")
    log.debug(s"verify=true  WITHOUT it (same canonical chain):    $withoutIt")

    withDiscarded shouldBe withoutIt
  }

  "persisted finalizedHeight after a microblock fork (verify=false, import path)" in {
    val withDiscarded = run(appendFinalizingMicro = true, verifyBlock = false)
    val withoutIt     = run(appendFinalizingMicro = false, verifyBlock = false)

    log.debug(s"verify=false WITH discarded finalizing microblock: $withDiscarded")
    log.debug(s"verify=false WITHOUT it (same canonical chain):    $withoutIt")

    withDiscarded shouldBe withoutIt
  }
}
