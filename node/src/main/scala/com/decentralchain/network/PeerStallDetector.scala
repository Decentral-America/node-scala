package com.decentralchain.network

/** Counts consecutive `scheduleConnectTask` ticks where this node has zero live peer connections
  * AND `PeerDatabase.nextCandidate` returned no candidate at all -- i.e. genuinely stuck, not just
  * "haven't finished a normal reconnect yet" (a candidate existing but the handshake still in
  * flight is NOT a stall). Once `threshold` consecutive such ticks are observed, `tick` returns
  * `true` exactly once (on the crossing tick) so the caller (`NetworkServer.scheduleConnectTask`)
  * can trigger a single `clearSuspension()` call rather than repeating it every tick. Any tick with
  * a live connection OR an available candidate resets the counter to zero.
  *
  * This is the in-process replacement for `peer-watchdog.yml`'s external SSH+docker-restart
  * remedy (docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md) -- unlike that
  * script, this NEVER touches blacklist, only suspension (see `PeerDatabase.clearSuspension`).
  *
  * Not thread-safe by design: `NetworkServer.scheduleConnectTask` is a single self-rescheduling
  * Netty timer loop (`workerGroup.schedule`), never invoked concurrently with itself.
  */
private[network] class PeerStallDetector(threshold: Int) {
  require(threshold > 0, s"threshold must be positive, got $threshold")

  private var consecutiveStuckTicks = 0
  private var alreadyFired          = false

  /** @return true exactly on the tick where the stall threshold is newly crossed. */
  def tick(hasConnections: Boolean, hasCandidate: Boolean): Boolean =
    if (hasConnections || hasCandidate) {
      reset()
      false
    } else {
      consecutiveStuckTicks += 1
      if (consecutiveStuckTicks >= threshold && !alreadyFired) {
        alreadyFired = true
        true
      } else false
    }

  def reset(): Unit = {
    consecutiveStuckTicks = 0
    alreadyFired = false
  }
}
