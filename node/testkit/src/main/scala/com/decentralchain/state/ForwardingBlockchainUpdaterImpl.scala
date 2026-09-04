package com.decentralchain.state

import com.decentralchain.account.Address
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.transaction.BlockchainUpdater

class ForwardingBlockchainUpdaterImpl(delegate: CompleteBlockchainUpdater) extends Blockchain with BlockchainUpdater with NG {
  export delegate.{
    settings,
    height,
    finalizedHeight,
    finalizedHeightAt,
    score,
    blockHeader,
    hitSource,
    carryFee,
    heightOf,
    approvedFeatures,
    activatedFeatures,
    featureVotes,
    blockReward,
    blockRewardVotes,
    dccAmount,
    transferById,
    transactionInfo,
    transactionInfos,
    transactionMeta,
    transactionSnapshot,
    containsTransaction,
    assetDescription,
    resolveAlias,
    leaseDetails,
    filledVolumeAndFee,
    balanceAtHeight,
    balanceSnapshots,
    accountScript,
    hasAccountScript,
    assetScript,
    accountData,
    hasData,
    leaseBalance,
    leaseBalances,
    balance,
    balances,
    dccBalances,
    effectiveBalanceBanHeights,
    resolveERC20Address,
    lastStateHash,
    processBlock,
    processMicroBlock,
    computeNextReward,
    removeAfter,
    lastBlockInfo,
    isLastBlockId,
    shutdown,
    microBlock,
    bestLastBlockInfo,
    microblockIds,
    liquidBlock,
    liquidBlockSnapshot,
    microBlockSnapshot,
    liquidTransactions,
    liquidBlockMeta,
    bestLiquidSnapshot,
    bestLiquidSnapshotAndFees,
    snapshotBlockchain,
    currentGeneratorSet,
    conflictGenerators
  }

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)] = delegate.committedGenerators(at)

  // NG.referencedBlock is abstract (no trait-level default), so it must be implemented explicitly
  // here regardless. It must delegate to the real implementation, or this forwarder would silently
  // lose the delegate's persisted-block fallback and re-introduce the miner/appender carry-fee
  // divergence -- see BlockDiffer.carryFeeFromPreviousBlock.
  override def referencedBlock(reference: ByteStr): Option[Block] = delegate.referencedBlock(reference)

  // referencedBlockchain (used by appender/package.scala's appendKeyBlock and Miner.forgeBlock, upstream
  // PR #4034's pinned-read fix) can't be a plain export: the real BlockchainUpdaterImpl builds its result
  // as a fresh SnapshotBlockchain wrapping the raw underlying RocksDBWriter directly, which bypasses
  // ANY override a test subclass makes here (e.g. committedGenerators) -- a real test-harness gap the
  // pinned-read fix exposed, not a production bug. Re-route committedGenerators (and only that, since
  // it's the one override this class exists to support) back through this object's own (possibly
  // subclass-overridden) implementation.
  override def referencedBlockchain(reference: ByteStr): Blockchain = {
    val inner = delegate.referencedBlockchain(reference)
    new Blockchain {
      export inner.{committedGenerators => _, *}
      override def committedGenerators(at: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)] =
        ForwardingBlockchainUpdaterImpl.this.committedGenerators(at)
    }
  }
}
