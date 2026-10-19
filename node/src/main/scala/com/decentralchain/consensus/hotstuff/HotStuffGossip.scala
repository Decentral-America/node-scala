package com.decentralchain.consensus.hotstuff

import com.google.common.cache.{Cache, CacheBuilder}
import com.google.common.primitives.Ints
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}

/** First-seen relay gate for HotStuff gossip. A message is marked seen and relayed only AFTER it
  * passes validation, and its key covers the signature material, so a message that fails
  * validation can never displace a valid one that shares its (view, phase, block, voter).
  * Confined to the single HotStuff scheduler thread, like the coordinator.
  */
final class HotStuffGossip[S](relay: (S, Message) => Unit, maxEntries: Long = 100000) {
  private val seen: Cache[String, java.lang.Boolean] = CacheBuilder.newBuilder().maximumSize(maxEntries).build()

  def admit(sender: S, key: String, msg: Message)(valid: => Boolean): Boolean =
    if (seen.getIfPresent(key) != null) false
    else if (!valid) false
    else {
      seen.put(key, java.lang.Boolean.TRUE)
      relay(sender, msg)
      true
    }
}

object HotStuffGossip {
  private def digest(bytes: Array[Byte]): String = ByteStr(crypto.fastHash(bytes)).toString

  def voteKey(v: HotStuffVote): String =
    s"v:${v.view}:${v.phase}:${v.blockId}:${v.blockHeight}:${v.voterIndex}:${v.committeeEpoch}:${digest(v.signature.arr)}"

  def qcKey(qc: QuorumCertificate): String =
    s"q:${qc.view}:${qc.phase}:${qc.blockId}:${qc.blockHeight}:${qc.committeeEpoch}:" +
      digest(qc.aggregatedSignature.arr ++ qc.signerIndexes.flatMap(i => Ints.toByteArray(i)).toArray)

  def proposalKey(p: HotStuffProposal): String =
    s"p:${p.view}:${p.blockId}:${p.justify.fold("none")(qcKey)}"
}
