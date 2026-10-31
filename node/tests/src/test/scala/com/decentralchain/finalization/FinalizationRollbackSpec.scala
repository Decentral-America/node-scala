package com.decentralchain.finalization

import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.NumericExt
import com.decentralchain.transaction.TxHelpers

/** "Bug 2" closure (consensus investigation reference, section 9): a finalized height advanced by a
  * vote carried in a microblock that is later discarded must roll back, and the next key block must
  * not be built from, or validated against, the discarded microblock's data.
  *
  * Chain used by every case (generation period length 2, three committed generators):
  *   1. genesis
  *   2. CommitToGeneration from all three generators for the period starting at 3
  *   3. key block (liquid) + micro1 (a transfer, no vote) + micro2 (a vote that finalizes height 2)
  *
  * micro2 is then discarded through each of the two paths that can discard a microblock:
  *   - the network path: the next key block references micro1, not micro2;
  *   - the rollback path: `removeAfter` to a block below the liquid block (extension / debug rollback).
  *
  * Every case first proves the precondition -- micro2 really did advance the liquid finalized height
  * -- so the rollback assertions can never pass vacuously.
  */
class FinalizationRollbackSpec extends BaseFinalizationSpec {
  private val generator1 = TxHelpers.signer(0)
  private val generator2 = TxHelpers.signer(1)
  private val generator3 = TxHelpers.signer(2)
  private val generators = Seq(generator1, generator2, generator3)

  private val settings = DomainPresets.DeterministicFinality
    .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
    .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0, maxValidEndorsers = 1))

  private val initBalances = Seq(
    AddrWithBalance(generator1.toAddress, 5000.dcc),
    AddrWithBalance(generator2.toAddress, 2000.dcc),
    AddrWithBalance(generator3.toAddress, 3000.dcc)
  )

  /** State observed while block 3 is liquid. */
  private case class Liquid(
      genesisId: ByteStr,
      block2: Block,
      finalizedAfterBlock2: Option[Height],
      block3: Block,
      micro1Id: ByteStr,
      finalizedBeforeMicro2: Height,
      finalizedAfterMicro2: Height,
      forgedAtMicro1: Block,
      stateHashAtMicro1: ByteStr
  )

  // Fixed per spec instance (not per call) so both arms of a comparison build byte-identical transactions.
  private val baseTs = TxHelpers.timestamp

  private def liquidFinalizedHeight(d: Domain): Height = d.liquidState.value.finalizationState.finalizedHeight

  /** Appends blocks 2, 3, micro1 and (if `withMicro2`) micro2. Transaction timestamps are fixed so both arms of a comparison build
    * byte-identical transactions.
    */
  private def buildLiquid(d: Domain, withMicro2: Boolean, ts: Long): Liquid = {
    val genesisId = d.blockchain.lastBlockId.value

    val commits = generators.zipWithIndex.map { case (g, i) => TxHelpers.commitToGeneration(Height(3), g, timestamp = ts + i) }
    val block2  = d.createBlock(Block.ProtoBlockVersion, commits, generator = generator2, strictTime = true)
    d.appender.appendBlockWithoutFallback(block2) should beRight
    val finalizedAfterBlock2 = d.blockchain.finalizedHeight

    val block3 = d.createBlock(Block.ProtoBlockVersion, Nil, generator = generator2, strictTime = true)
    d.appender.appendBlockWithoutFallback(block3) should beRight

    val micro1   = d.createMicroBlock(signer = Some(generator2))(TxHelpers.transfer(generator1, generator2.toAddress, timestamp = ts + 10))
    val micro1Id = d.appendMicroBlock(micro1)

    val finalizedBeforeMicro2 = liquidFinalizedHeight(d)
    val forgedAtMicro1        = d.liquidState.value.liquidBlockOf(micro1Id).value.block
    val stateHashAtMicro1     = d.blockchain.lastStateHash(Some(micro1Id))

    if (withMicro2) {
      val micro2 = d.createMicroBlock(
        signer = Some(generator2),
        finalizationVoting = Some(
          mkFinalizationVoting(valid = Seq(GeneratorIndex(0)))
            .signed(endorsedId = block3.header.reference, finalizedId = genesisId, validEndorsers = generator1)
        )
      )(TxHelpers.transfer(generator1, generator2.toAddress, timestamp = ts + 11))
      d.appendMicroBlockE(micro2) should beRight
    }

    Liquid(
      genesisId,
      block2,
      finalizedAfterBlock2,
      block3,
      micro1Id,
      finalizedBeforeMicro2,
      liquidFinalizedHeight(d),
      forgedAtMicro1,
      stateHashAtMicro1
    )
  }

  /** Observable finalization state after the discard; compared across the two arms. */
  private case class Outcome(persistedAt3: Option[Int], current: Option[Int], liquidAt4: Int, liquidAt5: Int)

  /** Network discard path: block 4 references micro1, so micro2 is discarded. Then block 5 is appended on top. */
  private def discardByKeyBlockReference(withMicro2: Boolean): Outcome = {
    var outcome = Option.empty[Outcome]
    withDomain(settings, initBalances) { d =>
      val l = buildLiquid(d, withMicro2, ts = baseTs)

      if (withMicro2) withClue("precondition: micro2 must advance the liquid finalized height: ") {
        l.finalizedAfterMicro2 should be > l.finalizedBeforeMicro2
      }

      val block4 = d.createBlock(Block.ProtoBlockVersion, Nil, ref = Some(l.micro1Id), generator = generator2, strictTime = true)
      withClue("block 4 (references micro1) is accepted with full verification (signature, state hash, voting): ") {
        d.appender.appendBlockWithoutFallback(block4) should beRight
      }
      d.blockchain.height shouldBe 4
      d.blockchain.lastBlockId.value shouldBe block4.id()
      block4.signatureValid() shouldBe true

      withClue("the persisted block 3 is exactly the block forged at micro1, captured before micro2 existed: ") {
        val persisted3 = d.rocksDBWriter.lastBlock.value
        persisted3.id() shouldBe l.forgedAtMicro1.id()
        persisted3.header shouldBe l.forgedAtMicro1.header
        persisted3.signatureValid() shouldBe true
        persisted3.transactionData.map(_.id()) shouldBe l.forgedAtMicro1.transactionData.map(_.id())
      }
      withClue("the persisted state hash of block 3 is the one computed at micro1, not one that includes micro2: ") {
        d.rocksDBWriter.lastStateHash(None) shouldBe l.stateHashAtMicro1
      }
      withClue("finalized height persisted for height 3 returns to its value before micro2: ") {
        d.blockchain.finalizedHeightAt(Height(3)) shouldBe Some(l.finalizedBeforeMicro2)
      }

      val liquidAt4 = liquidFinalizedHeight(d)

      val block5 = d.createBlock(Block.ProtoBlockVersion, Nil, generator = generator2, strictTime = true)
      withClue("the next key block (5) is accepted with full verification: ") {
        d.appender.appendBlockWithoutFallback(block5) should beRight
      }
      block5.signatureValid() shouldBe true
      d.blockchain.height shouldBe 5

      outcome = Some(
        Outcome(
          d.blockchain.finalizedHeightAt(Height(3)).map(_.toInt),
          d.blockchain.finalizedHeight.map(_.toInt),
          liquidAt4.toInt,
          liquidFinalizedHeight(d).toInt
        )
      )
    }
    outcome.value
  }

  "network discard path (next key block references an earlier microblock)" - {
    "finalized height returns to its prior value and blocks 4 and 5 are valid" in {
      discardByKeyBlockReference(withMicro2 = true)
    }

    "the resulting finalization state is identical to a node that never saw the discarded microblock" in {
      discardByKeyBlockReference(withMicro2 = true) shouldBe discardByKeyBlockReference(withMicro2 = false)
    }
  }

  "rollback path (removeAfter below the liquid block)" - {
    "discards the liquid block with micro2; finalized height returns to its prior value and the rebuilt chain is valid" in
      withDomain(settings, initBalances) { d =>
        val ts                 = baseTs + 100
        val l                  = buildLiquid(d, withMicro2 = true, ts)
        val liquidAtBlock3Base = d.liquidState.value.finalizationState.baseFinalizedHeight
        l.finalizedAfterMicro2 should be > l.finalizedBeforeMicro2

        d.blockchainUpdater.removeAfter(l.block2.id()) should beRight
        d.liquidState shouldBe empty
        d.blockchain.height shouldBe 2
        d.blockchain.lastBlockId.value shouldBe l.block2.id()
        withClue("finalized height after the rollback equals its value before block 3 was appended: ") {
          d.blockchain.finalizedHeight shouldBe l.finalizedAfterBlock2
        }

        withClue("block 3 is re-appended on the rolled-back state: ") {
          d.appender.appendBlockWithoutFallback(l.block3) should beRight
        }
        withClue("the re-created liquid state starts from the pre-micro2 finalized height, not the discarded advance: ") {
          liquidFinalizedHeight(d) shouldBe liquidAtBlock3Base
          liquidFinalizedHeight(d) shouldBe l.finalizedBeforeMicro2
        }

        val micro1 = d.createMicroBlock(signer = Some(generator2))(TxHelpers.transfer(generator1, generator2.toAddress, timestamp = ts + 10))
        d.appendMicroBlock(micro1)

        val block4 = d.createBlock(Block.ProtoBlockVersion, Nil, generator = generator2, strictTime = true)
        withClue("the next key block is accepted with full verification: ") {
          d.appender.appendBlockWithoutFallback(block4) should beRight
        }
        block4.signatureValid() shouldBe true
        withClue("the re-built block 3 has the state hash computed at micro1 before the rollback: ") {
          d.rocksDBWriter.lastStateHash(None) shouldBe l.stateHashAtMicro1
        }
        d.blockchain.finalizedHeightAt(Height(3)) shouldBe Some(l.finalizedBeforeMicro2)
      }

    "a persisted finalized-height advance: the per-height value rolls back with the block that carried it" in
      withDomain(settings, initBalances) { d =>
        val ts = baseTs + 200
        val l  = buildLiquid(d, withMicro2 = true, ts)
        l.finalizedAfterMicro2 should be > l.finalizedBeforeMicro2

        // Block 4 references micro2 this time, so the advance is persisted with block 3.
        val micro2Id = d.liquidState.value.microBlockIds.head
        val block4   = d.createBlock(Block.ProtoBlockVersion, Nil, ref = Some(micro2Id), generator = generator2, strictTime = true)
        d.appender.appendBlockWithoutFallback(block4) should beRight
        d.blockchain.finalizedHeightAt(Height(3)) shouldBe Some(l.finalizedAfterMicro2)
        val finalizedAt2 = d.blockchain.finalizedHeightAt(Height(2))

        d.blockchainUpdater.removeAfter(l.block2.id()) should beRight
        d.blockchain.height shouldBe 2
        d.liquidState shouldBe empty
        withClue("finalizedHeightAt(3) is gone with block 3; finalizedHeightAt(2) is untouched: ") {
          d.blockchain.finalizedHeightAt(Height(3)) shouldBe empty
          d.blockchain.finalizedHeightAt(Height(2)) shouldBe finalizedAt2
        }
        // The node-global finalizedHeight is deliberately monotonic (RocksDBWriter.rollbackTo resets it only when
        // the rollback target is BELOW it): block 2 itself was finalized by a quorum vote and is still on the chain.
        // It is not consensus state -- the next liquid block derives its FinalizationState from the per-height
        // finalizedHeightAt(2), asserted below.
        d.blockchain.finalizedHeight shouldBe Some(l.finalizedAfterMicro2)

        d.appender.appendBlockWithoutFallback(l.block3) should beRight
        withClue("the re-created liquid state starts from finalizedHeightAt(2), not the rolled-back advance: ") {
          liquidFinalizedHeight(d) shouldBe l.finalizedBeforeMicro2
        }
        val block4b = d.createBlock(Block.ProtoBlockVersion, Nil, generator = generator2, strictTime = true)
        d.appender.appendBlockWithoutFallback(block4b) should beRight
        d.blockchain.finalizedHeightAt(Height(3)) shouldBe Some(l.finalizedBeforeMicro2)
      }
  }
}
