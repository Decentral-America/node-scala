package com.decentralchain.state

import com.decentralchain.block.{Block, BlockSnapshot}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.mining.MiningConstraint
import com.decentralchain.state.diffs.BlockDiffer
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.{DeterministicFinality, DCCSettingsOps}
import com.decentralchain.transaction.TxHelpers

/** Audit M2, light-node companion to `CommitToGenerationPopV2Spec`. `BlockDiffer.fromBlock`'s
  * snapshot branch runs no `TransactionDiffer` -- `validateCommitmentsOnSnapshotPath` is the ONLY
  * place a light node re-verifies a peer-supplied CommitToGenerationTransaction's PoP -- so this
  * site must be gated on feature 30 exactly like `CommitToGenerationTransactionDiff`, or an attacker
  * simply routes the same legacy/v2 confusion through a light node instead. Modeled on the harness
  * shape in `LightNodeTest` (see its C1 rogue-key cases around line 224).
  */
class BlsCryptoV2SnapshotPathPopSpec extends PropSpec with WithDomain {
  private val generationPeriodLength = 8
  private val activationHeight       = Height(3)

  private def settingsWithBlsV2At(h: Int) =
    DeterministicFinality
      .copy(enableLightMode = true)
      .configure(_.copy(generationPeriodLength = generationPeriodLength, lightNodeBlockFieldsAbsenceInterval = 0))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight.toInt, BlockchainFeatures.BlsCryptoV2 -> h)

  private val sender = TxHelpers.defaultSigner

  private def getTxSnapshots(d: Domain, block: Block): Seq[(StateSnapshot, TxMeta.Status)] = {
    val lb                                            = d.liquidState.get.liquidBlockOf(block.header.reference).get
    val (refBlock, refSnapshot, carry, prevStateHash) = (lb.block, lb.data.snapshot, lb.data.carryFee, lb.data.liquidStateHash)

    val hs = d.posSelector.validateGenerationSignature(block).explicitGet()

    val referencedBlockchain = SnapshotBlockchain(
      d.rocksDBWriter,
      refSnapshot,
      refBlock,
      d.liquidState.get.hitSource,
      carry,
      Some(d.settings.blockchainSettings.rewardsSettings.initial),
      Some(prevStateHash)
    )

    val snapshot =
      BlockDiffer
        .fromBlock(referencedBlockchain, Some(refBlock), block, None, MiningConstraint.Unlimited, hs, None)
        .explicitGet()
        .snapshot

    snapshot.transactions.values.toSeq.map(txInfo => txInfo.snapshot -> txInfo.status)
  }

  property("H-1 (pre-activation): snapshot path accepts legacy PoP, rejects v2 PoP") {
    val h = 5
    withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
      // The commitment tx is validated as part of the block being CREATED next, so the chain must
      // sit one block below h-1 for that next block to land at height h-1 (pre-activation).
      while (d.blockchain.height < h - 2) d.appendBlock()
      d.blockchain.height shouldBe h - 2

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start

      // legacy PoP: build a valid honest block/snapshot pair to get structurally valid per-tx
      // snapshots (exactly what a malicious peer would serve), then apply it -- should succeed.
      val legacyTx       = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = false)
      val legacyBlock     = d.createBlock(Block.ProtoBlockVersion, Seq(legacyTx))
      val legacySnapshots = getTxSnapshots(d, legacyBlock)
      d.appendBlockE(legacyBlock, Some(BlockSnapshot(legacyBlock.id(), legacySnapshots))) should beRight
    }
  }

  property("H-1 (pre-activation), v2 PoP: snapshot path rejects it") {
    val h = 5
    withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < h - 2) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val prevBlock    = d.lastBlock

      // Use an honest legacy commitment to obtain structurally valid snapshots (as a malicious peer
      // would serve), but ship a v2-signed tx in the block -- must be rejected pre-activation.
      val honestTx    = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = false)
      val honestBlock = d.createBlock(Block.ProtoBlockVersion, Seq(honestTx))
      val txSnapshots = getTxSnapshots(d, honestBlock)

      val v2Tx    = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = true)
      val v2Block = d.createBlock(Block.ProtoBlockVersion, Seq(v2Tx), stateHash = Some(honestBlock.header.stateHash))

      d.appendBlockE(v2Block, Some(BlockSnapshot(v2Block.id(), txSnapshots))) should beLeft
      d.lastBlock shouldBe prevBlock
    }
  }

  property("H and H+1 (post-activation): snapshot path accepts v2 PoP, rejects legacy PoP") {
    val h = 5
    Seq(h, h + 1).foreach { targetHeight =>
      withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
        while (d.blockchain.height < targetHeight - 1) d.appendBlock()
        d.blockchain.height shouldBe targetHeight - 1

        val periodStart = d.blockchain.currentGenerationPeriod.get.next.start

        val v2Tx        = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = true)
        val v2Block     = d.createBlock(Block.ProtoBlockVersion, Seq(v2Tx))
        val v2Snapshots = getTxSnapshots(d, v2Block)
        d.appendBlockE(v2Block, Some(BlockSnapshot(v2Block.id(), v2Snapshots))) should beRight
      }
    }
  }

  property("H (post-activation), legacy PoP: snapshot path rejects it") {
    val h = 5
    withDomain(settingsWithBlsV2At(h), AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < h - 1) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val prevBlock    = d.lastBlock

      val honestTx    = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = true)
      val honestBlock = d.createBlock(Block.ProtoBlockVersion, Seq(honestTx))
      val txSnapshots = getTxSnapshots(d, honestBlock)

      val legacyTx    = TxHelpers.commitToGeneration(periodStart, sender, cryptoV2 = false)
      val legacyBlock = d.createBlock(Block.ProtoBlockVersion, Seq(legacyTx), stateHash = Some(honestBlock.header.stateHash))

      d.appendBlockE(legacyBlock, Some(BlockSnapshot(legacyBlock.id(), txSnapshots))) should beLeft
      d.lastBlock shouldBe prevBlock
    }
  }
}
