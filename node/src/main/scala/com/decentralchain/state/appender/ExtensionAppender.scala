package com.decentralchain.state.appender

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.lang.ValidationError
import com.decentralchain.metrics.{BlockStats, Metrics}
import com.decentralchain.network.{ExtensionBlocks, InvalidBlockStorage, PeerDatabase, formatBlocks, id}
import com.decentralchain.state.*
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import com.decentralchain.state.StateSnapshot
import com.decentralchain.transaction.*
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.utils.{ScorexLogging, Time}
import com.decentralchain.utx.UtxPool
import io.netty.channel.Channel
import monix.eval.Task
import monix.execution.Scheduler
import org.influxdb.dto.Point

import scala.util.{Left, Right}

object ExtensionAppender extends ScorexLogging {

  def apply(
      blockchainUpdater: BlockchainUpdater & Blockchain,
      utxStorage: UtxPool,
      pos: PoSSelector,
      time: Time,
      invalidBlocks: InvalidBlockStorage,
      peerDatabase: PeerDatabase,
      scheduler: Scheduler
  )(ch: Channel, extensionBlocks: ExtensionBlocks): Task[Either[ValidationError, Option[BigInt]]] = {
    def appendExtension(extension: ExtensionBlocks): Either[ValidationError, Option[BigInt]] =
      if (extension.remoteScore <= blockchainUpdater.score) {
        log.trace(s"Ignoring extension $extension because declared remote was not greater than local score ${blockchainUpdater.score}")
        Right(None)
      } else {
        extension.blocks
          .collectFirst { case b if !b.signatureValid() => GenericError(s"Block $b has invalid signature") }
          .toLeft(extension)
          .flatMap { extensionWithValidSignatures =>
            val newBlocks = extensionWithValidSignatures.blocks.dropWhile(blockchainUpdater.contains)

            newBlocks.headOption.map(_.header.reference) match {
              case Some(lastCommonBlockId) =>
                val initialHeight = blockchainUpdater.height

                val droppedBlocksEi = for {
                  commonBlockHeight <- blockchainUpdater.heightOf(lastCommonBlockId).toRight(GenericError("Fork contains no common parent"))
                  droppedBlocks     <- {
                    if (commonBlockHeight < initialHeight)
                      blockchainUpdater.removeAfter(lastCommonBlockId)
                    else Right(Seq.empty)
                  }
                } yield (commonBlockHeight, droppedBlocks)

                droppedBlocksEi.flatMap { case (commonBlockHeight, droppedBlocks) =>
                  newBlocks.zipWithIndex.foreach { case (block, idx) =>
                    val rideV6Activated = blockchainUpdater.isFeatureActivated(BlockchainFeatures.RideV6, commonBlockHeight + idx + 1)
                    ParSignatureChecker.checkTxSignatures(block.transactionData, rideV6Activated)
                  }

                  // Microblocks discarded by this fork must be returned to the UTX pool, exactly as
                  // appendKeyBlock does on the broadcast path (utx.setPrioritySnapshots(discardedDiffs)).
                  // Otherwise a same-height fork received via sync -- one that references the base of our
                  // current liquid block and ignores our microblocks -- silently drops those
                  // microblock-only txs forever: they live only in the discarded liquid block, so
                  // `droppedBlocks` (from removeAfter) never contains them, and this path previously threw
                  // the discardedDiffs away. Captured here per successfully-applied block (the lazy view
                  // stops at the first failure, so only applied blocks contribute) and re-offered on
                  // success below; the priority pool re-validates and cleanup drops any the incoming fork
                  // already included.
                  val discardedSnapshots      = Seq.newBuilder[StateSnapshot]
                  val forkApplicationResultEi = {
                    newBlocks.view
                      .map { b =>
                        b -> appendExtensionBlock(blockchainUpdater, pos, time, verify = true, txSignParCheck = false)(
                          b,
                          extension.snapshots.get(b.id())
                        )
                          .map {
                            case (Applied(discardedDiffs, _, _), height) =>
                              discardedSnapshots ++= discardedDiffs
                              BlockStats.applied(b, BlockStats.Source.Ext, height)
                            case _ =>
                          }
                      }
                      .zipWithIndex
                      .collectFirst { case ((b, Left(e)), i) => (i, b, e) }
                      .fold[Either[ValidationError, Unit]](Right(())) { case (i, declinedBlock, e) =>
                        e match {
                          case _: TxValidationError.BlockFromFuture =>
                          case _                                    => invalidBlocks.add(declinedBlock.id(), e)
                        }

                        newBlocks.view
                          .dropWhile(_ != declinedBlock)
                          .foreach(BlockStats.declined(_, BlockStats.Source.Ext))

                        if (i == 0) log.warn(s"Can't process fork starting with $lastCommonBlockId, error appending block $declinedBlock: $e")
                        else
                          log.warn(
                            s"Processed only ${i + 1} of ${newBlocks.size} blocks from extension, error appending next block $declinedBlock: $e"
                          )

                        Left(e)
                      }
                  }

                  forkApplicationResultEi match {
                    case Left(e) =>
                      blockchainUpdater.removeAfter(lastCommonBlockId).explicitGet()
                      droppedBlocks.foreach { x =>
                        blockchainUpdater.processBlock(x.block, x.hitSource, x.snapshot, x.generatorSet).explicitGet()
                      }
                      Left(e)

                    case Right(_) =>
                      val depth = initialHeight - commonBlockHeight
                      if (depth > 0) {
                        Metrics.write(
                          Point
                            .measurement("rollback")
                            .addField("depth", initialHeight - commonBlockHeight)
                            .addField("txs", droppedBlocks.size)
                        )
                      }

                      val newTransactions = newBlocks.view.flatMap(_.transactionData).toSet
                      utxStorage.removeAll(newTransactions)
                      utxStorage.addAndScheduleCleanup(droppedBlocks.flatMap(_._1.transactionData).filterNot(newTransactions))
                      val discarded = discardedSnapshots.result()
                      if (discarded.nonEmpty) utxStorage.setPrioritySnapshots(discarded)
                      Right(Some(blockchainUpdater.score))
                  }
                }

              case None =>
                log.debug("No new blocks found in extension")
                Right(None)
            }
          }
      }

    log.debug(s"${id(ch)} Attempting to append extension ${formatBlocks(extensionBlocks.blocks)}")
    Task(appendExtension(extensionBlocks)).executeOn(scheduler).map {
      case Right(maybeNewScore) =>
        log.debug(s"${id(ch)} Successfully appended extension ${formatBlocks(extensionBlocks.blocks)}")
        Right(maybeNewScore)
      case Left(ve) =>
        val errorMessage = s"${id(ch)} Error appending extension ${formatBlocks(extensionBlocks.blocks)}: $ve"

        ve match {
          case _: TxValidationError.BlockFromFuture =>
            // don't blacklist on block from future
            log.debug(errorMessage)
          case _ =>
            log.warn(errorMessage)
            peerDatabase.blacklistAndClose(ch, errorMessage)
        }

        Left(ve)
    }
  }
}
