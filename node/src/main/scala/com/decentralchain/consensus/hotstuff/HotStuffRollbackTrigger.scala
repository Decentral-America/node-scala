package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.{Block, MicroBlock}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.events.BlockchainUpdateTriggers
import com.decentralchain.state.{Blockchain, Height, StateSnapshot}

/** Resets the (advisory) HotStuff finality record when the chain rolls back, so the tracker can never keep
  * reporting a finalized block that a reorg has orphaned. The HotStuff engine itself self-heals — its next
  * `onBlockApplied` cancels the current round and starts a fresh one for the new tip — so only the tracker's
  * monotonic record needs an explicit reset. Fast finality is block-granular, so micro-block rollbacks are
  * ignored. This is the safe correctness increment that complements the read-side canonical filter in
  * FinalityApiRoute; the full BFT lock rule remains gated on the external consensus audit.
  */
final class HotStuffRollbackTrigger(tracker: HotStuffFinalityTracker) extends BlockchainUpdateTriggers {
  override def onProcessBlock(
      block: Block,
      snapshot: StateSnapshot,
      reward: Option[Long],
      hitSource: ByteStr,
      blockchainBeforeWithReward: Blockchain
  ): Unit = ()

  override def onProcessMicroBlock(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      totalBlockId: ByteStr,
      totalTransactionsRoot: ByteStr
  ): Unit = ()

  override def onRollback(blockchainBefore: Blockchain, toBlockId: ByteStr, toHeight: Int): Unit =
    tracker.rollbackTo(Height(toHeight))

  override def onMicroBlockRollback(blockchainBefore: Blockchain, toBlockId: ByteStr): Unit = ()
}
