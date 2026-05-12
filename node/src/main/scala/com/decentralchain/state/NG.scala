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

  def liquidBlockSnapshot(id: ByteStr): Option[StateSnapshot]

  def microBlockSnapshot(totalBlockId: ByteStr): Option[StateSnapshot]

  def liquidTransactions(id: ByteStr): Option[Seq[(TxMeta, Transaction)]]

  def liquidBlockMeta: Option[BlockMeta]

  def bestLiquidSnapshot: Option[StateSnapshot]

  def bestLiquidSnapshotAndFees: Option[(StateSnapshot, Long, Long)]

  def snapshotBlockchain: SnapshotBlockchain

  def currentGeneratorSet: Option[GeneratorSet]
}
