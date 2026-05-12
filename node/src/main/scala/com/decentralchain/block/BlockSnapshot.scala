package com.decentralchain.block

import com.decentralchain.block.Block.BlockId
import com.decentralchain.state.{StateSnapshot, TxMeta}

case class BlockSnapshot(blockId: BlockId, snapshots: Seq[(StateSnapshot, TxMeta.Status)])
