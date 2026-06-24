package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.Address
import com.decentralchain.block.Block.BlockId
import com.decentralchain.consensus.hotstuff.HotStuffEngine.*
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.network.{HotStuffQCMessage, HotStuffQCSpec, HotStuffVoteMessage, HotStuffVoteSpec, RawBytes}
import com.decentralchain.settings.HotStuffSettings
import com.decentralchain.state.{GeneratorSet, Height}
import com.decentralchain.utils.ScorexLogging
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import org.apache.pekko.actor.{Actor, Cancellable, Props}

/** Drives the 3-round HotStuff BFT protocol for fast block finality.
  *
  * Each new key block triggers one instance of the protocol:
  *   Prepare → PreCommit → Commit (≥2/3 balance-weighted BLS votes per round)
  *
  * If any round exceeds [[HotStuffSettings.roundTimeout]], the engine falls back
  * to T0 (DeterministicFinality). The chain NEVER halts regardless of outcome.
  *
  * Leader = the block's forger (FairPoS schedule). Validators are all nodes that
  * have committed via CommitToGenerationTransaction for the current generation period.
  */
class HotStuffEngine(
    myAddress: Address,
    blsKeyPair: BlsKeyPair,
    settings: HotStuffSettings,
    allChannels: ChannelGroup,
    finalityTracker: HotStuffFinalityTracker
) extends Actor
    with ScorexLogging {

  import context.dispatcher

  private var state: State = Idle

  private object metrics {
    val roundLatency       = Kamon.histogram("hotstuff.round.latency_ms").withoutTags()
    val roundTimeouts      = Kamon.counter("hotstuff.round.timeouts").withoutTags()
    val fastFinalityHeight = Kamon.gauge("hotstuff.finality.height").withoutTags()
    def qcFormedByRound(round: HotStuffRound) =
      Kamon.counter("hotstuff.qc.formed").withTag("round", round.name)
  }

  private var roundStartMs: Long = 0L

  override def receive: Receive = {
    case msg: BlockApplied        => onBlockApplied(msg)
    case msg: VoteReceived        => onVoteReceived(msg)
    case msg: QCReceived          => onQCReceived(msg)
    case RoundTimeout(id, height) => onRoundTimeout(id, height)
  }

  // ---- inbound event handlers ----

  private def onBlockApplied(msg: BlockApplied): Unit = {
    cancelCurrentRound()

    if (msg.validators.isEmpty) {
      state = Idle
      return
    }

    val amValidator = msg.validators.exists(_.address == myAddress)
    if (!amValidator) {
      state = Idle
      return
    }

    beginRound(msg.blockId, msg.height, HotStuffRound.Prepare, msg.validators, msg.forgerAddress)
  }

  private def onVoteReceived(msg: VoteReceived): Unit = state match {
    case ar: ActiveRound if msg.vote.blockId == ar.blockId && msg.vote.round == ar.round =>
      ar.collector match {
        case None => // we're not the leader — ignore votes
        case Some(c) =>
          c.add(msg.vote) match {
            case Right(Some(qc)) => onLeaderFormsQC(qc, ar)
            case Right(None)     => // accumulating
            case Left(err)       => log.warn(s"HotStuff: rejected vote from index ${msg.vote.voterIndex}: $err")
          }
      }
    case _ => // stale or wrong round
  }

  private def onQCReceived(msg: QCReceived): Unit = state match {
    case ar: ActiveRound if ar.collector.isEmpty &&
        msg.qc.blockId == ar.blockId &&
        msg.qc.round == ar.round =>
      msg.qc.verify(ar.validators) match {
        case Left(err) =>
          log.warn(s"HotStuff: invalid ${ar.round.name} QC at h=${ar.height}: $err")
        case Right(_) if !msg.qc.meetsThreshold(ar.validators) =>
          log.warn(s"HotStuff: ${ar.round.name} QC at h=${ar.height} below 2/3 threshold — ignoring")
        case Right(_) =>
          ar.round.next match {
            case Some(nextRound) =>
              ar.deadline.cancel()
              beginRound(ar.blockId, ar.height, nextRound, ar.validators, ar.leaderAddress)
            case None =>
              // Commit QC received via gossip from the leader
              onCommitQC(msg.qc, ar, alreadyBroadcast = false)
          }
      }
    case _ => // stale or wrong round
  }

  private def onRoundTimeout(blockId: BlockId, height: Height): Unit = state match {
    case ar: ActiveRound if ar.blockId == blockId && ar.height == height =>
      metrics.roundTimeouts.increment()
      log.info(
        s"HotStuff: ${ar.round.name} round timeout at h=$height (${settings.roundTimeoutMs}ms) — T0 DeterministicFinality takes over"
      )
      state = Idle
    case _ =>
  }

  // ---- protocol transitions ----

  private def beginRound(
      blockId: BlockId,
      height: Height,
      round: HotStuffRound,
      validators: GeneratorSet,
      leaderAddress: Address
  ): Unit = {
    val amLeader = myAddress == leaderAddress
    val myInfo   = validators.find(_.address == myAddress)
    val myIndex  = myInfo.map(_.index.toInt).getOrElse(-1)

    val collector = if (amLeader) Some(new HotStuffVoteCollector(blockId, height, round, validators)) else None
    val deadline  = context.system.scheduler.scheduleOnce(settings.roundTimeout, self, RoundTimeout(blockId, height))
    val ar        = ActiveRound(blockId, height, round, validators, leaderAddress, collector, deadline)
    state = ar
    roundStartMs = System.currentTimeMillis()

    log.debug(
      s"HotStuff: ${round.name} started h=$height block=${blockId.trim} leader=$leaderAddress amLeader=$amLeader"
    )

    // Every validator (including the leader) casts their own vote and broadcasts it.
    val vote = HotStuffVote.sign(myIndex, blockId, height, round, blsKeyPair)
    allChannels.broadcast(RawBytes.from(HotStuffVoteSpec, HotStuffVoteMessage.from(vote)))

    // Leader immediately processes its own self-vote.
    // Capturing `ar` directly avoids any state cast — the collector exists iff we're the leader.
    collector.foreach { c =>
      c.add(vote) match {
        case Right(Some(qc)) => onLeaderFormsQC(qc, ar)
        case _               =>
      }
    }
  }

  private def onLeaderFormsQC(qc: HotStuffQC, ar: ActiveRound): Unit = {
    ar.deadline.cancel()
    val latency = System.currentTimeMillis() - roundStartMs
    metrics.roundLatency.record(latency)
    metrics.qcFormedByRound(qc.round).increment()
    log.debug(
      s"HotStuff: leader formed ${qc.round.name} QC at h=${qc.height} signers=${qc.signerIndices.size} latency=${latency}ms"
    )
    allChannels.broadcast(RawBytes.from(HotStuffQCSpec, HotStuffQCMessage.from(qc)))

    qc.round match {
      case HotStuffRound.Commit =>
        onCommitQC(qc, ar, alreadyBroadcast = true)
      case _ =>
        qc.round.next.foreach { nextRound =>
          beginRound(qc.blockId, qc.height, nextRound, ar.validators, ar.leaderAddress)
        }
    }
  }

  private def onCommitQC(qc: HotStuffQC, ar: ActiveRound, alreadyBroadcast: Boolean): Unit = {
    ar.deadline.cancel()
    val isNew = finalityTracker.updateWith(qc)
    if (isNew) {
      metrics.fastFinalityHeight.update(qc.height.toInt.toDouble)
      if (!alreadyBroadcast)
        allChannels.broadcast(RawBytes.from(HotStuffQCSpec, HotStuffQCMessage.from(qc)))
    }
    state = Idle
  }

  private def cancelCurrentRound(): Unit = state match {
    case ar: ActiveRound => ar.deadline.cancel()
    case _               =>
  }
}

object HotStuffEngine {
  def props(
      myAddress: Address,
      blsKeyPair: BlsKeyPair,
      settings: HotStuffSettings,
      allChannels: ChannelGroup,
      finalityTracker: HotStuffFinalityTracker
  ): Props = Props(new HotStuffEngine(myAddress, blsKeyPair, settings, allChannels, finalityTracker))

  // ---- inbound commands ----

  /** Sent by BlockAppender after a key block is successfully applied. */
  final case class BlockApplied(
      blockId: BlockId,
      height: Height,
      forgerAddress: Address,
      validators: GeneratorSet
  )

  /** Sent by the network layer when a HotStuffVoteMessage is received from a peer. */
  final case class VoteReceived(vote: HotStuffVote)

  /** Sent by the network layer when a HotStuffQCMessage is received from a peer. */
  final case class QCReceived(qc: HotStuffQC)

  /** Internal scheduler message — fires when a round exceeds its timeout budget. */
  private final case class RoundTimeout(blockId: BlockId, height: Height)

  // ---- actor state ----

  private sealed trait State
  private case object Idle extends State

  private final case class ActiveRound(
      blockId: BlockId,
      height: Height,
      round: HotStuffRound,
      validators: GeneratorSet,
      leaderAddress: Address,
      collector: Option[HotStuffVoteCollector], // Some iff this node is the leader
      deadline: Cancellable
  ) extends State
}
