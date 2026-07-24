package com.decentralchain.consensus.hotstuff.sim

/** Per-link fault-injection knobs, all probabilities in [0.0, 1.0]. */
final case class FaultProfile(
    dropProbability: Double = 0.0,
    duplicateProbability: Double = 0.0,
    minDelayMillis: Long = 1,
    maxDelayMillis: Long = 5
) {
  require(dropProbability >= 0.0 && dropProbability <= 1.0, "dropProbability must be in [0,1]")
  require(duplicateProbability >= 0.0 && duplicateProbability <= 1.0, "duplicateProbability must be in [0,1]")
  require(minDelayMillis >= 0 && maxDelayMillis >= minDelayMillis, "delay range must be non-negative and ordered")
}

/** Deterministic, fault-injecting point-to-point network over a [[SimClock]]. Peers are indexed
  * `0 until nodeCount`. `deliver` is invoked (possibly more than once, possibly never) for each `send`
  * call, always via `clock.schedule`, so delivery order is governed entirely by the clock's seed.
  */
final class SimNetwork[Msg](clock: SimClock, nodeCount: Int, faultProfile: FaultProfile) {
  private val partitioned = scala.collection.mutable.Set.empty[(Int, Int)]

  private def requireValidPeer(index: Int): Unit =
    require(index >= 0 && index < nodeCount, s"peer index=$index out of range [0, $nodeCount)")

  private def requireValidPeers(indices: Set[Int]): Unit = indices.foreach(requireValidPeer)

  def partition(from: Set[Int], to: Set[Int]): Unit = {
    requireValidPeers(from)
    requireValidPeers(to)
    for (a <- from; b <- to if a != b) { partitioned += ((a, b)); partitioned += ((b, a)) }
  }

  def healPartition(from: Set[Int], to: Set[Int]): Unit = {
    requireValidPeers(from)
    requireValidPeers(to)
    for (a <- from; b <- to if a != b) { partitioned -= ((a, b)); partitioned -= ((b, a)) }
  }

  /** Send `msg` from `from` to every peer in `to` (the sender is skipped even if present in `to`),
    * subject to fault injection (partition block, drop, duplicate, random delay).
    */
  def send(from: Int, to: Set[Int])(msg: Msg)(deliver: (Int, Msg) => Unit): Unit = {
    requireValidPeer(from)
    requireValidPeers(to)
    to.foreach { recipient =>
      if (recipient != from && !partitioned.contains((from, recipient))) {
        if (clock.random.nextDouble() >= faultProfile.dropProbability) {
          val copies    = if (clock.random.nextDouble() < faultProfile.duplicateProbability) 2 else 1
          val delaySpan = (faultProfile.maxDelayMillis - faultProfile.minDelayMillis + 1).toInt
          (1 to copies).foreach { _ =>
            val delay = faultProfile.minDelayMillis + clock.random.nextInt(delaySpan)
            clock.schedule(delay)(deliver(recipient, msg))
          }
        }
      }
    }
  }
}
