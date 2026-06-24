package com.decentralchain.settings

import pureconfig.*

import scala.concurrent.duration.*

/** HotStuff T2 fast-finality settings.
  *
  * Disabled by default — enable only after T0 (DeterministicFinality) has been proven
  * stable on mainnet for ≥60 days (per CONSENSUS.md Phase 2 prerequisite).
  */
case class HotStuffSettings(
    enabled: Boolean,
    roundTimeoutMs: Long
) derives ConfigReader {
  def roundTimeout: FiniteDuration = roundTimeoutMs.millis
}

object HotStuffSettings {
  val Default: HotStuffSettings = HotStuffSettings(
    enabled = false,
    roundTimeoutMs = 400L
  )
}
