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
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}

/** Audit M2, light-node companion to `CommitToGenerationPopMessageSpec`/`CommitToGenerationPopV2Spec`.
  * `BlockDiffer.fromBlock`'s snapshot branch runs no `TransactionDiffer` --
  * `validateCommitmentsOnSnapshotPath` is the ONLY place a light node re-verifies a peer-supplied
  * CommitToGenerationTransaction's PoP -- so this site must verify the chain/sender-bound PoP exactly
  * like `CommitToGenerationTransactionDiff`, or an attacker could route a forged PoP through a light
  * node instead. Modeled on the harness shape in `LightNodeTest` (see its C1 rogue-key cases).
  */
class LightNodeSnapshotPathPopSpec extends PropSpec with WithDomain {
  private val generationPeriodLength = 8
  private val activationHeight       = Height(3)

  private def settings =
    DeterministicFinality
      .copy(enableLightMode = true)
      .configure(_.copy(generationPeriodLength = generationPeriodLength, lightNodeBlockFieldsAbsenceInterval = 0))
      .setFeaturesHeight(BlockchainFeatures.DeterministicFinality -> activationHeight.toInt)

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

  property("snapshot path accepts a correctly-signed PoP") {
    withDomain(settings, AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < 3) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start

      val tx        = TxHelpers.commitToGeneration(periodStart, sender)
      val block     = d.createBlock(Block.ProtoBlockVersion, Seq(tx))
      val snapshots = getTxSnapshots(d, block)
      d.appendBlockE(block, Some(BlockSnapshot(block.id(), snapshots))) should beRight
    }
  }

  property("snapshot path rejects a PoP signed under the wrong chainId (cross-chain replay, audit M2)") {
    withDomain(settings, AddrWithBalance.enoughBalances(sender)) { d =>
      while (d.blockchain.height < 3) d.appendBlock()

      val periodStart = d.blockchain.currentGenerationPeriod.get.next.start
      val prevBlock   = d.lastBlock

      // Honest tx to obtain structurally valid snapshots (as a malicious peer would serve), but ship
      // a tx whose PoP was signed under a DIFFERENT chainId -- must be rejected.
      val honestTx    = TxHelpers.commitToGeneration(periodStart, sender)
      val honestBlock = d.createBlock(Block.ProtoBlockVersion, Seq(honestTx))
      val txSnapshots = getTxSnapshots(d, honestBlock)

      val endorserKp    = com.decentralchain.crypto.bls.BlsKeyPair(sender.privateKey)
      val wrongChainSig = CommitToGenerationTransaction.mkPopSignature(endorserKp, periodStart, sender.publicKey, chainId = 'X'.toByte)
      val forgedTx      = honestTx.copy(commitmentSignature = wrongChainSig)
      val forgedBlock   = d.createBlock(Block.ProtoBlockVersion, Seq(forgedTx), stateHash = Some(honestBlock.header.stateHash))

      d.appendBlockE(forgedBlock, Some(BlockSnapshot(forgedBlock.id(), txSnapshots))) should beLeft
      d.lastBlock shouldBe prevBlock
    }
  }
}
