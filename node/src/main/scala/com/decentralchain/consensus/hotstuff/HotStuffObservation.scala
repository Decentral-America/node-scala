package com.decentralchain.consensus.hotstuff

import java.util.concurrent.atomic.AtomicInteger

/** Process-global observation hook for the HotStuff-committed height.
  *
  * HotStuff commit is observational — feature-25 remains the authoritative finalized height — but for
  * soak monitoring it is useful to see how far HotStuff has committed relative to the chain tip and to
  * feature-25's finalized height. `NodeHotStuffEffects.onCommit` publishes here (monotonic), and the
  * REST `/node/status` route reads it. It stays at the sentinel (`-1` ⇒ `None`) unless the HotStuff
  * coordinator is enabled and has committed at least one block, so `/node/status` is byte-for-byte
  * unchanged when HotStuff is disabled (the default). One node per JVM, so a process global is safe.
  */
object HotStuffObservation {
  private val committedHeight = new AtomicInteger(-1)

  /** Record an observational HotStuff commit height (monotonic). */
  def publish(height: Int): Unit = committedHeight.updateAndGet(prev => math.max(prev, height))

  /** The highest HotStuff-committed height, or `None` if HotStuff has never committed (disabled/idle). */
  def committedHeightOpt: Option[Int] = committedHeight.get() match {
    case h if h < 0 => None
    case h          => Some(h)
  }
}
