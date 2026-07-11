package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature}
import com.decentralchain.network.{ChannelGroupExt, Message}
import com.decentralchain.state.GeneratorSet
import com.decentralchain.wallet.Wallet
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.group.ChannelGroup

import java.util.concurrent.atomic.AtomicInteger

/** Production [[HotStuffEffects]]: broadcast over the peer channel group, sign with the node's own
  * committed BLS key(s), and record the HotStuff-finalized height.
  *
  * COMMIT MODE = design (b), observational (see docs/hotstuff-integration-design.md §5): `onCommit`
  * records `hotStuffFinalizedHeight` for observation/metrics but does NOT mutate the authoritative
  * feature-25 finalized height. This is the zero-risk mode for the initial testnet soak; switching to
  * design (a) (HotStuff raising the authoritative finalized height) is a separate, audited change to
  * `BlockchainUpdaterImpl`.
  *
  * Mirrors the broadcast/sign pattern of `BlockEndorser.InMemory`. Confine to the coordinator's single
  * thread. `committee` is the committed set for the current generation period; reconstruct per period.
  */
final class NodeHotStuffEffects(
    committeeProvider: () => GeneratorSet,
    wallet: Wallet,
    allChannels: ChannelGroup
) extends HotStuffEffects
    with StrictLogging {

  private val hotStuffFinalized = new AtomicInteger(0)

  /** Latest height finalized by HotStuff (observational; feature-25 remains authoritative). */
  def hotStuffFinalizedHeight: Int = hotStuffFinalized.get()

  override def broadcast(message: Message): Unit = allChannels.broadcast(message)

  // Committee rotates per generation period, so read it fresh (not a cached val).
  override def myVoterIndexes: Set[Int] =
    committeeProvider().iterator.filter(gi => wallet.privateKeyAccount(gi.address).isRight).map(_.index.toInt).toSet

  override def signVote(voteMessage: Array[Byte], voterIndex: Int): Option[BlsSignature] =
    committeeProvider()
      .find(_.index.toInt == voterIndex)
      .flatMap(gi => wallet.privateKeyAccount(gi.address).toOption)
      .map(account => BlsKeyPair(account.privateKey).sign(voteMessage))

  override def onCommit(blockId: BlockId, height: Int): Unit = {
    hotStuffFinalized.updateAndGet(prev => math.max(prev, height))
    HotStuffObservation.publish(height) // surface for /node/status + soak monitoring
    logger.info(s"[HotStuff] observational commit: block $blockId finalized at height $height (feature-25 remains authoritative)")
  }
}
