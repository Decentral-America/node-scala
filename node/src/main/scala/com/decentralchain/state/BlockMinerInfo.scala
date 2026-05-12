package com.decentralchain.state

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr

case class BlockMinerInfo(baseTarget: Long, generationSignature: ByteStr, timestamp: Long, blockId: BlockId)
