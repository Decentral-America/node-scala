package com.decentralchain.database

import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.{GeneratorSet, Height, StateSnapshot}
import com.decentralchain.transaction.DiscardedBlocks

trait Storage {
  def append(
      snapshot: StateSnapshot,
      carryFee: Long,
      totalFee: Long,
      reward: Option[Long],
      hitSource: ByteStr,
      computedBlockStateHash: ByteStr,
      block: Block,
      newFinalizedHeight: Height,
      generatorSet: GeneratorSet
  ): Unit
  def lastBlock: Option[Block]
  def rollbackTo(height: Height): Either[String, DiscardedBlocks]
  def safeRollbackHeight: Height
}
