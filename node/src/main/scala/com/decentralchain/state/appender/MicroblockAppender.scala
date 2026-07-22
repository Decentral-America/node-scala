package com.decentralchain.state.appender

import cats.data.EitherT
import cats.syntax.traverse.*
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{MicroBlock, MicroBlockSnapshot}
import com.decentralchain.lang.ValidationError
import com.decentralchain.metrics.*
import com.decentralchain.mining.BlockChallenger
import com.decentralchain.network.*
import com.decentralchain.network.MicroBlockSynchronizer.MicroblockData
import io.decentralchain.protobuf.PBSnapshots
import com.decentralchain.state.{BlockEndorser, Blockchain, Height, NG}
import com.decentralchain.transaction.BlockchainUpdater
import com.decentralchain.transaction.TxValidationError.{InvalidSignature, InvalidStateHash}
import com.decentralchain.utils.ScorexLogging
import com.decentralchain.utx.UtxPool
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Left, Right}

object MicroblockAppender extends ScorexLogging {
  private val microblockProcessingTimeStats = Kamon.timer("microblock-appender.processing-time").withoutTags()

  def apply(
      blockchainUpdater: BlockchainUpdater & Blockchain & NG,
      utxStorage: UtxPool,
      blockEndorser: BlockEndorser,
      scheduler: Scheduler,
      verify: Boolean = true
  )(
      microBlock: MicroBlock,
      snapshot: Option[MicroBlockSnapshot]
  ): Task[Either[ValidationError, BlockId]] =
    Task(microblockProcessingTimeStats.measureSuccessful {
      blockchainUpdater
        .processMicroBlock(microBlock, snapshot, verify)
        .map { totalBlockId =>
          if (microBlock.transactionData.nonEmpty) {
            utxStorage.removeAll(microBlock.transactionData)
            log.trace(
              s"Removing txs of ${microBlock.stringRepr(totalBlockId)} ${microBlock.transactionData.map(_.id()).mkString("(", ", ", ")")} from UTX pool"
            )
          }

          utxStorage.scheduleCleanup()
          // Refresh the self-target endorsement round (see BlockEndorser.voteSelf) for this new tip --
          // needed regardless of whether THIS node mined the microblock, since any node might next
          // need to seal a key block extending it.
          blockEndorser.voteSelf(blockchainUpdater.currentGeneratorSet.getOrElse(Nil))
          totalBlockId
        }
    }).executeOn(scheduler)

  def apply(
      blockchainUpdater: BlockchainUpdater & Blockchain & NG,
      utxStorage: UtxPool,
      allChannels: ChannelGroup,
      peerDatabase: PeerDatabase,
      blockChallenger: Option[BlockChallenger],
      blockEndorser: BlockEndorser,
      scheduler: Scheduler
  )(ch: Channel, md: MicroblockData, snapshot: Option[(Channel, MicroBlockSnapshotResponse)]): Task[Unit] = {
    import md.microBlock
    val microblockTotalResBlockSig = microBlock.totalResBlockSig
    (for {
      _ <- EitherT(Task.now(microBlock.signaturesValid()))
      microBlockSnapshot = snapshot
        .map { case (_, mbs) =>
          microBlock.transactionData.zip(mbs.snapshots).map { case (tx, pbs) =>
            PBSnapshots.fromProtobuf(pbs, tx.id(), Height(blockchainUpdater.height))
          }
        }
        .map(ss => MicroBlockSnapshot(microblockTotalResBlockSig, ss))

      blockId <- EitherT(apply(blockchainUpdater, utxStorage, blockEndorser, scheduler)(microBlock, microBlockSnapshot))
    } yield blockId).value.flatMap {
      case Right(blockId) =>
        Task {
          md.invOpt match {
            case Some(mi) => allChannels.broadcast(mi, except = md.microblockOwners())
            case None     => log.warn(s"${id(ch)} Not broadcasting MicroBlockInv")
          }
          BlockStats.applied(microBlock, blockId)
        }
      case Left(is: InvalidSignature) =>
        Task {
          val idOpt = md.invOpt.map(_.totalBlockId)
          peerDatabase.blacklistAndClose(ch, s"Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $is")
        }
      case Left(ish: InvalidStateHash) =>
        val channelToBlacklist = snapshot.map(_._1).getOrElse(ch)
        val idOpt              = md.invOpt.map(_.totalBlockId)
        peerDatabase.blacklistAndClose(
          channelToBlacklist,
          s"Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $ish"
        )
        md.invOpt.foreach(mi => BlockStats.declined(mi.totalBlockId))

        blockChallenger.traverse(_.challengeMicroblock(md, channelToBlacklist).executeOn(scheduler)).void

      case Left(ve) =>
        Task {
          md.invOpt.foreach(mi => BlockStats.declined(mi.totalBlockId))
          val idOpt = md.invOpt.map(_.totalBlockId)
          log.debug(s"${id(ch)} Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $ve")
        }
    }
  }
}
