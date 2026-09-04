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
  * COMMIT MODE: `onCommit` always records `hotStuffFinalizedHeight` for observation/metrics. Whether it
  * ALSO mutates the authoritative feature-25 finalized height depends on `authoritative`:
  *   - `authoritative = false` (default, mainnet-only-safe mode, see docs/hotstuff-integration-design.md
  *     §5 design (b)): purely observational, byte-for-byte the pre-existing behaviour. `raiseFinalizedHeight`
  *     is never invoked.
  *   - `authoritative = true` (TESTNET-ONLY opt-in, `hotstuff.authoritative`, ahead of the external audit --
  *     an accepted, scoped risk for testnet, see `HotStuffSettings`): every commit ALSO calls
  *     `raiseFinalizedHeight(blockId, height)`, which the caller wires to
  *     `BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight` -- itself responsible for the
  *     agrees-with-local-canonical-chain safety check (this class has no chain access and does not
  *     duplicate that logic).
  *
  * Mirrors the broadcast/sign pattern of `BlockEndorser.InMemory`. Confine to the coordinator's single
  * thread. `committee` is the committed set for the current generation period; reconstruct per period.
  *
  * @param authoritative       mirrors `HotStuffSettings.authoritative`; gates whether `raiseFinalizedHeight`
  *                            is ever called
  * @param raiseFinalizedHeight injected hook to `BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight`
  *                            (kept as a plain function, not a concrete type, so this class stays
  *                            testable without a real chain -- see `NodeHotStuffEffectsSpecification`)
  */
final class NodeHotStuffEffects(
    committeeProvider: () => GeneratorSet,
    wallet: Wallet,
    allChannels: ChannelGroup,
    authoritative: Boolean = false,
    raiseFinalizedHeight: (BlockId, Int) => Boolean = (_, _) => false
) extends HotStuffEffects
    with StrictLogging {

  private val hotStuffFinalized = new AtomicInteger(0)

  /** Latest height finalized by HotStuff (observational; always maintained regardless of `authoritative`). */
  def hotStuffFinalizedHeight: Int = hotStuffFinalized.get()

  override def broadcast(message: Message): Unit = allChannels.broadcast(message)

  // Committee rotates per generation period, so read it fresh (not a cached val).
  override def myVoterIndexes: Set[Int] =
    committeeProvider().iterator.filter(gi => wallet.privateKeyAccount(gi.address).isRight).map(_.index.toInt).toSet

  override def signVote(voteMessage: Array[Byte], voterIndex: Int, dst: String): Option[BlsSignature] =
    committeeProvider()
      .find(_.index.toInt == voterIndex)
      .flatMap(gi => wallet.privateKeyAccount(gi.address).toOption)
      .map(account => BlsKeyPair(account.privateKey).sign(voteMessage, dst))

  override def onCommit(blockId: BlockId, height: Int): Unit = {
    hotStuffFinalized.updateAndGet(prev => math.max(prev, height))
    HotStuffObservation.publish(height) // surface for /node/status + soak monitoring

    if (authoritative) {
      val applied = raiseFinalizedHeight(blockId, height)
      if (applied) {
        logger.info(s"[HotStuff] AUTHORITATIVE commit: block $blockId finalized at height $height (raised feature-25 finalizedHeight)")
      } else {
        logger.warn(
          s"[HotStuff] authoritative commit for block $blockId at height $height was REFUSED (disagreement with " +
            s"local canonical chain, or already superseded) -- feature-25 finalizedHeight unaffected by this commit"
        )
      }
    } else {
      logger.info(s"[HotStuff] observational commit: block $blockId finalized at height $height (feature-25 remains authoritative)")
    }
  }

  override def onEquivocation(proof: HotStuffEquivocationProof): Unit = {
    logger.error(
      s"[HotStuff] EQUIVOCATION DETECTED: voter #${proof.voterIndex} double-signed view=${proof.view} ${proof.phase} " +
        s"epoch=${proof.committeeEpoch} blocks=${proof.voteA.blockId.trim}/${proof.voteB.blockId.trim} -- " +
        s"Byzantine actor or protocol-violating bug; investigate immediately"
    )
    HotStuffEquivocationObservation.recordEquivocation()
  }
}
