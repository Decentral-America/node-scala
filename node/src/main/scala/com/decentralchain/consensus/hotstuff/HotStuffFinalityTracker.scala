package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.state.Height
import com.decentralchain.utils.ScorexLogging

import java.util.concurrent.atomic.AtomicReference

final case class HotStuffFinalizedBlock(blockId: BlockId, height: Height)

/** Thread-safe record of the highest Commit QC this node has observed.
  * Safe to read from REST threads while the Akka actor writes.
  */
final class HotStuffFinalityTracker extends ScorexLogging {
  private val latest = new AtomicReference[Option[HotStuffFinalizedBlock]](None)

  /** Records a Commit QC. Returns true if this is a new highest finalized height. */
  def updateWith(qc: HotStuffQC): Boolean = {
    require(qc.round == HotStuffRound.Commit, "Only Commit QCs establish fast finality")
    val candidate = HotStuffFinalizedBlock(qc.blockId, qc.height)
    val prev      = latest.getAndUpdate(cur => if (cur.forall(_.height < candidate.height)) Some(candidate) else cur)
    val recorded  = prev.forall(_.height < candidate.height)
    if (recorded)
      log.info(s"HotStuff Commit QC: height=${qc.height} block=${qc.blockId.trim} validators=${qc.signerIndices.size}")
    recorded
  }

  /** Drop the recorded finalized block if a rollback put it above the new chain tip — otherwise the tracker
    * would keep reporting a block orphaned by the reorg. `updateWith` only ever moves the height up, so without
    * this a same-height reorg leaves a stale advisory record. Called from a BlockchainUpdateTriggers.onRollback.
    */
  def rollbackTo(toHeight: Height): Unit = {
    val prev = latest.getAndUpdate {
      case Some(fb) if fb.height > toHeight => None
      case other                            => other
    }
    if (prev.exists(_.height > toHeight))
      log.info(s"HotStuff finality record reset on rollback to $toHeight (was ${prev.map(_.height)})")
  }

  def latestFinalizedBlock: Option[HotStuffFinalizedBlock] = latest.get()

  def finalizedHeight: Option[Height] = latest.get().map(_.height)
}
