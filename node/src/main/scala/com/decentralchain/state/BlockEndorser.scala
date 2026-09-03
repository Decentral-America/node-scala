package com.decentralchain.state

import com.typesafe.scalalogging.StrictLogging
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{BlockEndorsement, FinalizationVoting, SignedBlockHeader}
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.network.{ChannelGroupExt, EndorseBlock}
import com.decentralchain.state.EndorsementFilter
import com.decentralchain.wallet.Wallet
import io.netty.channel.group.ChannelGroup

import java.util.concurrent.atomic.AtomicReference

trait BlockEndorser {

  /** Voting to finalize the block AT endorsedHeight (the just-appended block's own parent) -- matches
    * what a microblock extending the just-appended block will embed as its finalizationVoting (a
    * microblock's own reference is that same parent, unchanged across every microblock in the liquid
    * period).
    */
  def vote(generatorSet: GeneratorSet): Unit

  /** Voting to finalize the just-appended block ITSELF (both key blocks AND microblocks call this,
    * since a NEW key block sealing this height's liquid period will reference the just-appended block
    * directly). Without this, a key block extending a liquid-extended tip has no matching endorsements
    * for its own reference field -- the only endorsements available target the tip's PARENT (from
    * `vote`), a different candidate, so embedding them fails block-append validation ("Wrong BLS
    * signature") instead of just being empty.
    */
  def voteSelf(generatorSet: GeneratorSet): Unit

  /** Idempotent re-emit of THIS node's own endorsement(s) for the current voting height(s).
    *
    * feature-25 finality is miner-aggregated and endorsements are sent once (from `vote`/`voteSelf`), so
    * with MULTIPLE generators forging the aggregator role rotates every block and a fire-once endorsement
    * can miss whichever node is currently mining -> finality stalls. Re-emitting the SAME already-signed
    * endorsement (bit-identical BLS message) lets it reach the rotated aggregator within the live window.
    * Safe: never signs anything new (no equivocation); the aggregator's EndorsementStorage dedups repeats.
    * Self-terminating: only replays while the same voting is live (the endorsed height not yet finalized).
    */
  def rebroadcast(): Unit

  /** Collect the self-target round (see `voteSelf`) for the given reference block ID. Called by
    * Miner.forgeBlock when sealing a new key block, using that key block's own reference (the tip it
    * extends) as endorsedId -- the exact candidate voteSelf produces.
    */
  def tryCollectSelf(endorsedId: BlockId): Option[FinalizationVoting]

  /** Whether this endorser ever participates in finality voting. `false` only for
    * [[BlockEndorser.Disabled]] (used by tests, the importer and the block generator), where every
    * collect call is a guaranteed no-op -- letting callers skip pointless grace/poll waits. Always
    * `true` in production, which wires [[BlockEndorser.InMemory]].
    */
  def enabled: Boolean
}

object BlockEndorser {
  object Disabled extends BlockEndorser {
    override def vote(generatorSet: GeneratorSet): Unit                          = {}
    override def voteSelf(generatorSet: GeneratorSet): Unit                      = {}
    override def rebroadcast(): Unit                                             = {}
    override def tryCollectSelf(endorsedId: BlockId): Option[FinalizationVoting] = None
    override val enabled: Boolean                                                = false
  }

  class InMemory(
      maxSyncRollbackLength: Int,
      blockchain: Blockchain,
      wallet: Wallet,
      endorsementStorage: EndorsementStorage,
      selfEndorsementStorage: EndorsementStorage,
      allChannels: ChannelGroup
  ) extends BlockEndorser,
        StrictLogging {

    override val enabled: Boolean = true

    // This node's own endorsements for the current voting, captured at `vote`/`voteSelf` time so
    // `rebroadcast` can re-deliver them without re-signing: (votingHeight, endorsedHeight, msgs).
    // AtomicReference (not @volatile var): `vote`/`voteSelf` run on the block-appender thread while
    // `rebroadcast` runs on a 3s scheduler; rebroadcast's clear must be a compare-and-set so it can
    // never clobber a fresher vote written in between.
    private val pending: AtomicReference[Option[(Int, Int, Seq[EndorseBlock])]] = new AtomicReference(None)
    // Same, for the self-target round. Separate reference: the two rounds' targets change on
    // different cadences (parent-target only on key-block append; self-target on every tip change,
    // key block or microblock) and must be rebroadcast/cleared independently.
    private val pendingSelf: AtomicReference[Option[(Int, Int, Seq[EndorseBlock])]] = new AtomicReference(None)

    private def rebroadcastOne(ref: AtomicReference[Option[(Int, Int, Seq[EndorseBlock])]], label: String): Unit = ref.get() match {
      case current @ Some((_, endorsedHeight, msgs)) =>
        // Only condition that matters: has THIS specific height already been finalized? Requiring
        // blockchain.height == votingHeight (unchanged since vote()) was too strict -- with multiple
        // rotating generators a new block can (and normally does) arrive well within the 3s rebroadcast
        // interval, closing the window before a rotated aggregator ever got a chance to receive the
        // endorsement even once. The endorsement targets a specific past height/id and stays valid to
        // rebroadcast regardless of how many newer blocks have since arrived, until it's finalized (or
        // vote()/voteSelf() replaces the pending value with a fresher one, which happens on every new
        // block/microblock anyway).
        val stillLiveVoting = blockchain.finalizedHeightOrFallback(maxSyncRollbackLength).toInt < endorsedHeight
        logger.debug(s"rebroadcast($label): endorsedHeight=$endorsedHeight msgs=${msgs.size} stillLiveVoting=$stillLiveVoting")
        if (stillLiveVoting) msgs.foreach(m => allChannels.broadcast(m))
        else ref.compareAndSet(current, None) // clear only if vote()/voteSelf() hasn't written a fresher value meanwhile
      case None => ()
    }

    /** Shared endorsement-casting logic for both the parent-target (`vote`) and self-target (`voteSelf`)
      * rounds -- identical except which (endorsedHeight, endorsedId) pair and which EndorsementStorage
      * instance they target.
      */
    private def castVote(
        votingHeight: Height,
        endorsedHeight: Height,
        endorsedId: BlockId,
        votingBlockHeader: SignedBlockHeader,
        generatorSet: GeneratorSet,
        storage: EndorsementStorage,
        label: String
    ): Seq[EndorseBlock] =
      if (endorsedHeight > GenesisBlockHeight) {
        val msgs: Seq[EndorseBlock] = for {
          votingPeriod <- blockchain.generationPeriodOf(votingHeight).toSeq

          // Always endorse with the latest finalized block (upstream PR #4034), not a per-endorsedHeight
          // reconstruction -- simpler, and the endorsedHeight > finalizedHeight guard below skips voting
          // for something already finalized.
          finalizedHeight = blockchain.finalizedHeightOrFallback(maxSyncRollbackLength)
          if endorsedHeight > finalizedHeight

          finalizedId <- blockchain
            .blockId(finalizedHeight.toInt)
            .toSeq

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
          _ = logger.debug(
            s"$label(): votingHeight=$votingHeight endorsedHeight=$endorsedHeight endorsedId=$endorsedId finalizedHeight=$finalizedHeight " +
              s"committed=${committed.map(_._1)} minerIndex=${filter.miner} isMiner=${filter.isMiner} " +
              s"balances=${balances.keySet}"
          )
          if storage.startVoting(filter)

          (account, idx) <-
            for {
              ((committedAddr, _), idx) <- committed.zipWithIndex
              if idx != filter.miner.toInt // A miner doesn't need to endorse its own blocks - a mining is already an endorsement
              pk <- wallet.privateKeyAccount(committedAddr).toSeq
              if balances.contains(committedAddr)
            } yield (pk, GeneratorIndex(idx))

          endorsement = BlockEndorsement.signed(BlsKeyPair(account.privateKey), idx, finalizedId, finalizedHeight, endorsedId)
          networkMsg  = EndorseBlock.from(endorsement)
          broadcast <- storage.tryAdd(networkMsg) match {
            case Right(r)  => Some(r)
            case Left(err) =>
              logger.warn(s"Can't add endorsement from #$idx ${account.toAddress}: $err")
              None
          }
          if broadcast
        } yield networkMsg

        logger.debug(
          s"$label(): produced ${msgs.size} message(s) for votingHeight=$votingHeight endorsedHeight=$endorsedHeight endorsedId=$endorsedId"
        )
        msgs
      } else Nil

    override def vote(generatorSet: GeneratorSet): Unit = {
      val votingHeight            = Height(blockchain.height)
      val endorsedHeight          = votingHeight - 1
      val msgs: Seq[EndorseBlock] = (for {
        votingBlockHeader   <- blockchain.blockHeader(votingHeight.toInt).toSeq
        endorsedBlockHeader <- blockchain.blockHeader(endorsedHeight.toInt).toSeq
        msg <- castVote(
          votingHeight,
          endorsedHeight,
          endorsedBlockHeader.id(),
          votingBlockHeader,
          generatorSet,
          endorsementStorage,
          "vote"
        )
      } yield msg)

      msgs.foreach(m => allChannels.broadcast(m))
      // Record for rebroadcast() so a rotated aggregator can still receive these votes this height.
      pending.set(if (msgs.nonEmpty) Some((votingHeight.toInt, endorsedHeight.toInt, msgs)) else None)
    }

    override def voteSelf(generatorSet: GeneratorSet): Unit = {
      val votingHeight            = Height(blockchain.height)
      val endorsedHeight          = votingHeight
      val msgs: Seq[EndorseBlock] = (for {
        votingBlockHeader <- blockchain.blockHeader(votingHeight.toInt).toSeq
        msg <- castVote(
          votingHeight,
          endorsedHeight,
          votingBlockHeader.id(),
          votingBlockHeader,
          generatorSet,
          selfEndorsementStorage,
          "voteSelf"
        )
      } yield msg)

      msgs.foreach(m => allChannels.broadcast(m))
      // Record for rebroadcast() -- the self-target round changes on EVERY tip update (key block or
      // microblock), unlike the parent-target round which only changes on key-block append, so a
      // single-shot broadcast has a much narrower window to reach every endorser on a low-traffic
      // chain producing only key blocks a block interval apart. Without this, real network latency
      // between geographically-distributed nodes can miss that window every single round indefinitely.
      pendingSelf.set(if (msgs.nonEmpty) Some((votingHeight.toInt, endorsedHeight.toInt, msgs)) else None)
    }

    override def tryCollectSelf(endorsedId: BlockId): Option[FinalizationVoting] = selfEndorsementStorage.tryCollectAndClear(endorsedId)

    override def rebroadcast(): Unit = {
      rebroadcastOne(pending, "vote")
      rebroadcastOne(pendingSelf, "voteSelf")
    }
  }
}
