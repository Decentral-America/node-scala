package com.decentralchain.consensus.hotstuff

import java.util.concurrent.atomic.AtomicInteger

/** Process-global observation hook for verified HotStuff equivocation proofs, mirroring
  * `HotStuffObservation`'s pattern. `NodeHotStuffEffects.onEquivocation` publishes here on every
  * verified proof (see `HotStuffCoordinator.Enabled.onVote`), and the REST `/node/status` route reads
  * `totalCount` so the already-deployed exporter metric + critical alert
  * (`dcc_hotstuff_equivocations_total`) finally has a real value behind it. Stays at `0` unless the
  * HotStuff coordinator is enabled and has actually recorded a verified equivocation, so `/node/status`
  * is byte-for-byte unchanged when HotStuff is off/healthy. One node per JVM, so a process global is
  * safe.
  */
object HotStuffEquivocationObservation {
  private val equivocations = new AtomicInteger(0)

  /** Record one verified equivocation observation (monotonic counter). */
  def recordEquivocation(): Unit = equivocations.incrementAndGet()

  /** Total verified equivocations observed by this process so far. */
  def totalCount: Int = equivocations.get()
}
