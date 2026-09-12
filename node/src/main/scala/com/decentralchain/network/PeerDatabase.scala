package com.decentralchain.network

import java.net.{InetAddress, InetSocketAddress}

import io.netty.channel.Channel

trait PeerDatabase {
  def addCandidate(socketAddress: InetSocketAddress): Boolean
  def touch(socketAddress: InetSocketAddress): Unit

  def nextCandidate(excluded: Set[InetSocketAddress]): Option[InetSocketAddress]

  def blacklist(host: InetAddress, reason: String): Unit
  def blacklistAndClose(channel: Channel, reason: String): Unit
  def isBlacklisted(address: InetAddress): Boolean
  def clearBlacklist(): Unit

  /** Clears the transient suspension cache ONLY — never touches blacklist. Suspension fires on
    * every connection close for any reason (see `PeerDatabaseImpl.suspend`'s doc comment) and is
    * NOT a for-cause ban, unlike blacklist (which is reason-carrying and deliberate). This exists
    * for `NetworkServer`'s in-process stall-detection self-heal
    * (docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md) — auto-clearing
    * blacklist instead would re-admit peers banned for cause, which must never happen automatically.
    */
  def clearSuspension(): Unit

  def knownPeers: Map[InetSocketAddress, Long]

  def detailedBlacklist: Map[InetAddress, (Long, String)]
  def detailedSuspended: Map[InetAddress, Long]

  def suspend(host: InetSocketAddress): Unit
}

object PeerDatabase {

  object NoOp extends PeerDatabase {
    override def addCandidate(socketAddress: InetSocketAddress): Boolean = true

    override def touch(socketAddress: InetSocketAddress): Unit = {}

    override def blacklist(host: InetAddress, reason: String): Unit = {}

    override def knownPeers: Map[InetSocketAddress, Long] = Map.empty

    override def nextCandidate(excluded: Set[InetSocketAddress]): Option[InetSocketAddress] = None

    override def detailedBlacklist: Map[InetAddress, (Long, String)] = Map.empty

    override def clearBlacklist(): Unit = ()

    override def clearSuspension(): Unit = ()

    override def suspend(host: InetSocketAddress): Unit = {}

    override def isBlacklisted(address: InetAddress): Boolean = false

    override val detailedSuspended: Map[InetAddress, Long] = Map.empty

    override def blacklistAndClose(channel: Channel, reason: String): Unit = channel.close()
  }
}
