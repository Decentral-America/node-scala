package com.decentralchain.transaction

import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{Block, BlockSnapshot, MicroBlock, MicroBlockSnapshot}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult
import com.decentralchain.state.{Blockchain, GeneratorSet, Height}
import monix.reactive.Observable

trait BlockchainUpdater {
  def processBlock(
      block: Block,
      hitSource: ByteStr,
      snapshot: Option[BlockSnapshot],
      generatorSet: GeneratorSet,
      challengedHitSource: Option[ByteStr] = None,
      verify: Boolean = true,
      txSignParCheck: Boolean = true
  ): Either[ValidationError, BlockApplyResult]
  def processMicroBlock(microBlock: MicroBlock, snapshot: Option[MicroBlockSnapshot], verify: Boolean = true): Either[ValidationError, BlockId]
  def computeNextReward: Option[Long]
  def removeAfter(blockId: ByteStr): Either[ValidationError, DiscardedBlocks]
  def lastBlockInfo: Observable[LastBlockInfo]
  def isLastBlockId(id: ByteStr): Boolean
  def referencedBlockchain(reference: ByteStr): Blockchain
  def shutdown(): Unit

  /** T2 HotStuff authoritative-finality hook (testnet-only opt-in, `hotstuff.authoritative` -- see
    * `HotStuffSettings`). Raises the authoritative feature-25 `finalizedHeight` to (at least)
    * `certifiedHeight`, but ONLY if `certifiedBlockId` is exactly the block this node's OWN
    * canonical/synced chain already has at that height -- i.e. HotStuff can never inject or cause
    * acceptance of a block the ordinary sync/validation path hasn't already processed independently.
    * A disagreeing certified block (e.g. the T10 cross-epoch-fork hazard, or any other divergence) is
    * REFUSED: the raise does not apply, feature-25's own value remains the floor, and the caller should
    * log/alert. Monotonic: never lowers `finalizedHeight`, and is idempotent for an already-applied or
    * stale/lower height. Returns `true` iff the raise was actually applied.
    *
    * Default no-op (always refuses) so every OTHER `BlockchainUpdater` implementation (test mocks, the
    * `EmptyBlockchain`-based harness in `MiningFailuresSuite`, etc.) is unaffected without needing its
    * own override -- only `BlockchainUpdaterImpl` (the real chain) implements this for real. */
  def raiseHotStuffFinalizedHeight(certifiedBlockId: ByteStr, certifiedHeight: Height): Boolean = false
}

case class LastBlockInfo(id: BlockId, height: Height, score: BigInt, finalizedHeight: Height, ready: Boolean)
