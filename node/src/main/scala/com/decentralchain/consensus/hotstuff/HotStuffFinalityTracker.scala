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

  def latestFinalizedBlock: Option[HotStuffFinalizedBlock] = latest.get()

  def finalizedHeight: Option[Height] = latest.get().map(_.height)
}
