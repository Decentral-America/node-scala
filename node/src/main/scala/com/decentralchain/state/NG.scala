package com.decentralchain.state

import com.decentralchain.api.BlockMeta
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.transaction.Transaction

trait NG {
  def microBlock(id: ByteStr): Option[MicroBlock]

  def bestLastBlockInfo(maxMicroblockTimestampMs: Long): Option[BlockMinerInfo]

  def microblockIds: Seq[BlockId]

  def liquidBlock(id: ByteStr): Option[Block]

  /** The block that `reference` names, resolved the same way the appender resolves its
    * `maybePrevBlock`: a block inside the open liquid period if `reference` is one of its total
    * block ids, otherwise the last persisted block when `reference` names it.
    *
    * Exists so `BlockDiffer.createInitialBlockSnapshot` (the miner) can feed
    * `BlockDiffer.carryFeeFromPreviousBlock` the identical previous block the appender feeds it --
    * see that method's docs and the height-2640 stall it caused when the two disagreed.
    */
  def referencedBlock(reference: ByteStr): Option[Block]

  def liquidBlockSnapshot(id: ByteStr): Option[StateSnapshot]

  def microBlockSnapshot(totalBlockId: ByteStr): Option[StateSnapshot]

  def liquidTransactions(id: ByteStr): Option[Seq[(TxMeta, Transaction)]]

  def liquidBlockMeta: Option[BlockMeta]

  def bestLiquidSnapshot: Option[StateSnapshot]

  def bestLiquidSnapshotAndFees: Option[(StateSnapshot, Long, Long)]

  def snapshotBlockchain: SnapshotBlockchain

  def currentGeneratorSet: Option[GeneratorSet]
}
