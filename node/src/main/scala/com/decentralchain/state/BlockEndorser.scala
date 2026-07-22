package com.decentralchain.state

import com.typesafe.scalalogging.StrictLogging
import com.decentralchain.block.BlockEndorsement
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.network.{ChannelGroupExt, EndorseBlock}
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.ChannelGroup

import java.util.concurrent.atomic.AtomicReference

trait BlockEndorser {

  /** Voting happens
    *   for block at endorserHeight
    *   with finalizedBlock at votingHeight
    *   by generators, committed at votingHeight
    */
  def vote(generatorSet: GeneratorSet): Unit

  /** Idempotent re-emit of THIS node's own endorsement(s) for the current voting height.
    *
    * feature-25 finality is miner-aggregated and endorsements are sent once (from `vote`), so with
    * MULTIPLE generators forging the aggregator role rotates every block and a fire-once endorsement can
    * miss whichever node is currently mining -> finality stalls. Re-emitting the SAME already-signed
    * endorsement (bit-identical BLS message) lets it reach the rotated aggregator within the live window.
    * Safe: never signs anything new (no equivocation); the aggregator's EndorsementStorage dedups repeats.
    * Self-terminating: only replays while the same voting is live (height unchanged AND the endorsed
    * height not yet finalized) — matching the per-height `startVoting` clear.
    */
  def rebroadcast(): Unit
}

object BlockEndorser {
  object Disabled extends BlockEndorser {
    override def vote(generatorSet: GeneratorSet): Unit = {}
    override def rebroadcast(): Unit                    = {}
  }

  class InMemory(
      maxSyncRollbackLength: Int,
      blockchain: Blockchain,
      wallet: Wallet,
      endorsementStorage: EndorsementStorage,
      allChannels: ChannelGroup
  ) extends BlockEndorser,
        StrictLogging {

    // This node's own endorsements for the current voting, captured at `vote` time so `rebroadcast`
    // can re-deliver them to a rotated aggregator without re-signing: (votingHeight, endorsedHeight, msgs).
    // AtomicReference (not @volatile var): `vote` runs on the block-appender thread while `rebroadcast` runs
    // on a 3s scheduler; rebroadcast's clear must be a compare-and-set so it can never clobber a fresh vote
    // written in between (a lost update would drop this node's just-cast endorsements for a height).
    private val pending: AtomicReference[Option[(Int, Int, Seq[EndorseBlock])]] = new AtomicReference(None)

    override def vote(generatorSet: GeneratorSet): Unit = {
      val votingHeight   = Height(blockchain.height)
      val endorsedHeight = votingHeight - 1
      val msgs: Seq[EndorseBlock] =
        if (endorsedHeight > GenesisBlockHeight) for {
          votingPeriod <- blockchain.generationPeriodOf(votingHeight).toSeq

          votingBlockHeader   <- blockchain.blockHeader(votingHeight.toInt).toSeq
          endorsedBlockHeader <- blockchain.blockHeader(endorsedHeight.toInt).toSeq

          finalizedHeight = blockchain.finalizedHeightOrFallback(maxSyncRollbackLength)
          if endorsedHeight > finalizedHeight

          finalizedId <- blockchain
            .blockId(finalizedHeight.toInt)
            .toSeq

          endorsedId = endorsedBlockHeader.id()

          committed        = blockchain.committedGenerators(votingPeriod)
          votingBlockMiner = votingBlockHeader.header.generator.toAddress
          minerIndex       = committed.indexWhere { case (addr, _) => addr == votingBlockMiner }
          if minerIndex >= 0 // -1 means no miner among committed, impossible

          balances = generatorSet.collect {
            case x if blockchain.isGeneratingBalanceValid(votingHeight, votingBlockHeader.header, x.balance) => x.address -> x.balance
          }.toMap

          filter = {
            val normalizedEndorsers = committed.map { case (address, blsPk) =>
              (address, blsPk, balances.getOrElse(address, 0L))
            }.toVector

            val conflict = blockchain.conflictGenerators(votingPeriod).upTo(votingHeight)
            EndorsementFilter(
              blockchain.settings.functionalitySettings.maxValidEndorsers,
              GeneratorIndex(minerIndex),
              isMiner = wallet.privateKeyAccount(votingBlockMiner).isRight,
              finalizedId,
              finalizedHeight,
              endorsedId,
              normalizedEndorsers,
              conflict
            )
          }
          if endorsementStorage.startVoting(filter)

          (account, idx) <- for {
            ((committedAddr, _), idx) <- committed.zipWithIndex
            if idx != filter.miner.toInt // A miner doesn’t need to endorse its own blocks - a mining is already an endorsement
            pk <- wallet.privateKeyAccount(committedAddr).toSeq
            if balances.contains(committedAddr)
          } yield (pk, GeneratorIndex(idx))

          endorsement = BlockEndorsement.signed(BlsKeyPair(account.privateKey), idx, finalizedId, finalizedHeight, endorsedId)
          networkMsg  = EndorseBlock.from(endorsement)
          broadcast <- endorsementStorage.tryAdd(networkMsg) match {
            case Right(r) => Some(r)
            case Left(err) =>
              logger.warn(s"Can't add endorsement from #$idx ${account.toAddress}: $err")
              None
          }
          if broadcast
        } yield networkMsg
        else Nil

      msgs.foreach(m => allChannels.broadcast(m))
      // Record for rebroadcast() so a rotated aggregator can still receive these votes this height.
      pending.set(if (msgs.nonEmpty) Some((votingHeight.toInt, endorsedHeight.toInt, msgs)) else None)
    }

    override def rebroadcast(): Unit = pending.get() match {
      case current @ Some((_, endorsedHeight, msgs)) =>
        // Only condition that matters: has THIS specific height already been finalized? Requiring
        // blockchain.height == votingHeight (unchanged since vote()) was too strict -- with multiple
        // rotating generators a new block can (and normally does) arrive well within the 3s rebroadcast
        // interval, closing the window before a rotated aggregator ever got a chance to receive the
        // endorsement even once. The endorsement targets a specific past height/id and stays valid to
        // rebroadcast regardless of how many newer blocks have since arrived, until it's finalized (or
        // vote() replaces `pending` with a fresher one, which it does on every new block anyway).
        val stillLiveVoting = blockchain.finalizedHeightOrFallback(maxSyncRollbackLength).toInt < endorsedHeight
        if (stillLiveVoting) msgs.foreach(m => allChannels.broadcast(m))
        else pending.compareAndSet(current, None) // clear only if vote() hasn't written a fresher value meanwhile
      case None => ()
    }
  }
}
