package com.decentralchain.state

import com.decentralchain.block.Block
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.test.DomainPresets.{DeterministicFinality, DCCSettingsOps}
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}

/** Task 9, Step 3 (feature-30 BlsCryptoV2 plan): rollback-across-activation determinism.
  *
  * Proves the feature-30 PoP gate (`CommitToGenerationTransactionDiff`'s `cryptoV2 =
  * blockchain.supportsBlsCryptoV2(blockchain.height)`, where `blockchain` is the snapshot chain that
  * already includes the block being validated -- i.e. this reads as "the height of the block carrying
  * the transaction") is a pure function of the CONTAINING block's height, not of when validation
  * happened to run: rolling back across the activation height and re-appending the same two
  * transactions in the same positions must reproduce the exact same acceptance outcomes and the exact
  * same resulting `committedGenerators` state -- and moving a legacy-signed PoP from a pre-activation
  * height to a post-activation height must still be rejected (the negative control that rules out "any
  * PoP is accepted once its bytes have been seen/validated before" or some other non-height-derived
  * accidental pass).
  */
class BlsCryptoV2RollbackDeterminismSpec extends PropSpec with WithDomain {
  // Large enough that H-1, H, and H+1 (and the period the commitments register INTO, one period
  // later) don't cross an unrelated generation-period boundary the test isn't controlling for.
  private val generationPeriodLength = 100
  private val activationHeight       = 3

  // BlsCryptoV2 activates at height H: legacy commitment lands at H-1, v2 commitment at H+1.
  private val h = activationHeight + 5

  private def freshSettings =
    DeterministicFinality
      .configure(_.copy(generationPeriodLength = generationPeriodLength, lightNodeBlockFieldsAbsenceInterval = 0))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight, BlockchainFeatures.BlsCryptoV2 -> h)

  private val legacySender = TxHelpers.signer(101)
  private val v2Sender     = TxHelpers.signer(102)

  /** The generation period a `CommitToGenerationTransaction` created NOW would register into
    * (`currentGenerationPeriod.next`). A commitment only shows up in `committedGenerators` once the
    * chain actually reaches that period, not the period it was submitted during.
    */
  private def targetPeriod(d: Domain): GenerationPeriod = d.blockchain.currentGenerationPeriod.get.next

  /** Advances the chain to `activationHeight` (DeterministicFinality's own activation height), so
    * `currentGenerationPeriod` is defined and stable for the rest of the run, then returns the
    * generation period any commitment minted from here on will register into.
    */
  private def advanceToPeriodBase(d: Domain): GenerationPeriod = {
    while (d.blockchain.height < activationHeight) d.appendBlock()
    targetPeriod(d)
  }

  /** Appends no-op blocks until the chain is one block below `targetHeight`, then builds+appends a
    * block carrying `tx` (a `CommitToGenerationTransaction`, legacy or v2 PoP) so it lands exactly at
    * `targetHeight`. `tx` is passed in (rather than freshly minted here) so a replay run re-submits
    * the EXACT SAME transaction bytes -- only the surrounding chain state differs between runs.
    */
  private def appendCommitmentBlockAt(d: Domain, targetHeight: Int, tx: CommitToGenerationTransaction): Unit = {
    while (d.blockchain.height < targetHeight - 1) d.appendBlock()
    d.blockchain.height shouldBe targetHeight - 1

    val block = d.createBlock(Block.ProtoBlockVersion, Seq(tx))
    d.appendBlockE(block) should beRight
    d.blockchain.height shouldBe targetHeight
  }

  property("rollback across the activation height reproduces identical acceptance and state") {
    withDomain(freshSettings, AddrWithBalance.enoughBalances(legacySender, v2Sender)) { d =>
      val period   = advanceToPeriodBase(d)
      val legacyTx = TxHelpers.commitToGeneration(period.start, legacySender, cryptoV2 = false)
      val v2Tx     = TxHelpers.commitToGeneration(period.start, v2Sender, cryptoV2 = true)

      // 1. Legacy-PoP commitment at H-1 (pre-activation), v2-PoP commitment at H+1 (post-activation).
      appendCommitmentBlockAt(d, h - 1, legacyTx)
      d.appendBlock() // filler block landing exactly at H
      d.blockchain.height shouldBe h
      appendCommitmentBlockAt(d, h + 1, v2Tx)

      val committedGeneratorsFirstRun = d.blockchain.committedGenerators(period)
      val heightFirstRun              = d.blockchain.height
      committedGeneratorsFirstRun.map(_._1) should contain allOf (legacySender.toAddress, v2Sender.toAddress)

      // 2. Rollback to H-2 (one block before the legacy commitment block) and re-append the SAME two
      // transactions in the same order/positions.
      d.rollbackTo(h - 2)
      d.blockchain.height shouldBe h - 2

      appendCommitmentBlockAt(d, h - 1, legacyTx)
      d.appendBlock() // filler block landing exactly at H
      d.blockchain.height shouldBe h
      appendCommitmentBlockAt(d, h + 1, v2Tx)

      // Both transactions succeeded again on replay (the gate re-derives the SAME era from height,
      // not from "have I seen this tx before"), and the resulting state is identical to the first run.
      d.blockchain.height shouldBe heightFirstRun
      d.blockchain.committedGenerators(period) shouldBe committedGeneratorsFirstRun
      d.blockchain.committedGenerators(period).map(_._1) should contain allOf (
        legacySender.toAddress,
        v2Sender.toAddress
      )
    }
  }

  property("NEGATIVE control: the H-1 block's legacy-PoP transaction is rejected if resubmitted inside a block at H+1") {
    withDomain(freshSettings, AddrWithBalance.enoughBalances(legacySender, v2Sender)) { d =>
      val period   = advanceToPeriodBase(d)
      val legacyTx = TxHelpers.commitToGeneration(period.start, legacySender, cryptoV2 = false)

      // Sanity: this exact tx DOES succeed if placed at H-1 (pre-activation) -- proves the tx itself
      // is well-formed and the rejection below is about height/era, not some unrelated defect.
      appendCommitmentBlockAt(d, h - 1, legacyTx)
      d.blockchain.height shouldBe h - 1

      // Roll back so the SAME tx (same id, same signature bytes) can be tried at H+1 instead.
      d.rollbackTo(h - 2)
      d.blockchain.height shouldBe h - 2

      d.appendBlock() // -> H-1
      d.appendBlock() // -> H
      d.blockchain.height shouldBe h

      val legacyBlockAtHPlus1 = d.createBlockE(Block.ProtoBlockVersion, Seq(legacyTx))
      legacyBlockAtHPlus1 should beLeft
      d.blockchain.height shouldBe h // rejected: chain unchanged
    }
  }
}
