package com.decentralchain.history

import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.MicroBlock

class MicroBlockWithTotalId(val microBlock: MicroBlock, val totalBlockId: BlockId)
object MicroBlockWithTotalId {
  implicit def toMicroBlock(mb: MicroBlockWithTotalId): MicroBlock = mb.microBlock
}
