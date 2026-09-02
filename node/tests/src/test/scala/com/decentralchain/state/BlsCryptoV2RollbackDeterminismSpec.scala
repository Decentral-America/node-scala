package com.decentralchain.state

import com.decentralchain.block.Block
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.test.DomainPresets.{DeterministicFinality, DCCSettingsOps}
import com.decentralchain.test.PropSpec
import com.decentralchain.test.produce
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}

/** Task 9, Step 3 (feature-30 BlsCryptoV2 plan): rollback-across-activation determinism.
  *
  * Proves the feature-30 PoP gate (`CommitToGenerationTransactionDiff`'s `cryptoV2 =
  * blockchain.supportsBlsCryptoV2(blockchain.height)`, where `blockchain` is the snapshot chain that
  * already includes the block being validated -- i.e. this reads as "the height of the block carrying
  * the transaction") is a pure function of the CONTAINING block's height, not of when validation
  * happened to run: rolling back across the activation height and re-appending the same two
  * transactions in the same positions must reproduce the exact same acceptance outcomes and the exact
  * same resulting `committedGenerators` state.
  *
  * The boundary property is pinned directly: a v2 commitment placed at EXACTLY the activation height H
  * must be accepted and a legacy commitment at exactly H must be REJECTED; a legacy commitment at
  * exactly H-1 must be accepted and a v2 commitment at exactly H-1 must be REJECTED. Pinning all four
  * of these at the boundary (rather than leaving a one-block margin on either side) is what actually
  * rules out a gate that is off by one in either direction -- e.g. reading `blockchain.height - 1`
  * (a live-tip-style read that is one block stale relative to the block being validated) would still
  * pass a test that only checked H-1 and H+1, because H-1 stays legacy and H+1 stays v2 either way.
  * This was confirmmed by mutation: swapping the gate to `blockchain.height - 1` turns the exact-H
  * cases red; restoring the gate turns them green again (see the commit message for the transcript).
  */
class BlsCryptoV2RollbackDeterminismSpec extends PropSpec with WithDomain {
  // Large enough that H-1 and H (and the period the commitments register INTO, one period later)
  // don't cross an unrelated generation-period boundary the test isn't controlling for.
  private val generationPeriodLength = 100
  private val activationHeight       = 3

  // BlsCryptoV2 activates at height H.
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

      // 1. Legacy-PoP commitment at H-1 (pre-activation), v2-PoP commitment at H (post-activation,
      // the activation height itself).
      appendCommitmentBlockAt(d, h - 1, legacyTx)
      appendCommitmentBlockAt(d, h, v2Tx)

      val committedGeneratorsFirstRun = d.blockchain.committedGenerators(period)
      val heightFirstRun              = d.blockchain.height
      committedGeneratorsFirstRun.map(_._1) should contain allOf (legacySender.toAddress, v2Sender.toAddress)

      // 2. Rollback to H-2 (one block before the legacy commitment block) and re-append the SAME two
      // transactions in the same order/positions.
      d.rollbackTo(h - 2)
      d.blockchain.height shouldBe h - 2

      appendCommitmentBlockAt(d, h - 1, legacyTx)
      appendCommitmentBlockAt(d, h, v2Tx)

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

  property("BOUNDARY: v2 PoP at exactly H is accepted, legacy PoP at exactly H is rejected") {
    withDomain(freshSettings, AddrWithBalance.enoughBalances(legacySender, v2Sender)) { d =>
      val period   = advanceToPeriodBase(d)
      val legacyTx = TxHelpers.commitToGeneration(period.start, legacySender, cryptoV2 = false)
      val v2Tx     = TxHelpers.commitToGeneration(period.start, v2Sender, cryptoV2 = true)

      while (d.blockchain.height < h - 1) d.appendBlock()
      d.blockchain.height shouldBe h - 1

      // A legacy-signed PoP submitted in the block landing exactly at H (the activation height, i.e.
      // already post-activation) must be rejected -- a gate reading `height - 1` instead of `height`
      // would still treat this block as pre-activation and wrongly accept it.
      d.appendBlockE(legacyTx) should produce("Invalid commitment signature")
      d.blockchain.height shouldBe h - 1 // rejected: chain unchanged

      // The v2-signed PoP for the same block/period is accepted at exactly H.
      d.appendBlockE(v2Tx) should beRight
      d.blockchain.height shouldBe h
    }
  }

  property("BOUNDARY: legacy PoP at exactly H-1 is accepted, v2 PoP at exactly H-1 is rejected") {
    withDomain(freshSettings, AddrWithBalance.enoughBalances(legacySender, v2Sender)) { d =>
      val period   = advanceToPeriodBase(d)
      val legacyTx = TxHelpers.commitToGeneration(period.start, legacySender, cryptoV2 = false)
      val v2Tx     = TxHelpers.commitToGeneration(period.start, v2Sender, cryptoV2 = true)

      while (d.blockchain.height < h - 2) d.appendBlock()
      d.blockchain.height shouldBe h - 2

      // A v2-signed PoP submitted in the block landing exactly at H-1 (still pre-activation) must be
      // rejected -- a gate reading `height + 1` instead of `height` (or any off-by-one that treats
      // H-1 as already post-activation) would wrongly accept it.
      d.appendBlockE(v2Tx) should produce("Invalid commitment signature")
      d.blockchain.height shouldBe h - 2 // rejected: chain unchanged

      // The legacy-signed PoP for the same block/period is accepted at exactly H-1.
      d.appendBlockE(legacyTx) should beRight
      d.blockchain.height shouldBe h - 1
    }
  }

  property("NEGATIVE control: the H-1 block's legacy-PoP transaction is rejected if resubmitted inside a block at H+1") {
    withDomain(freshSettings, AddrWithBalance.enoughBalances(legacySender)) { d =>
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

      d.appendBlockE(legacyTx) should produce("Invalid commitment signature")
      d.blockchain.height shouldBe h // rejected: chain unchanged
    }
  }
}
