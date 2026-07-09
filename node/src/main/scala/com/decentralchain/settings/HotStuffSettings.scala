package com.decentralchain.settings

import pureconfig.*

import scala.concurrent.duration.FiniteDuration

/** Configuration for the T2 HotStuff BFT fast-finality engine (see CONSENSUS.md).
  *
  * IMPORTANT — SAFETY GATE: `enabled` defaults to `false`. The HotStuff engine is under active
  * development on `feature/hotstuff-t2` and every part of the pipeline is gated behind this flag.
  * When disabled (the default), the node behaves exactly as before and finality is provided solely
  * by Deterministic Finality (feature 25). Do NOT enable on mainnet until the engine is implemented,
  * unit/integration-tested, externally audited, and soaked on testnet. Enabling only affects
  * behaviour once the engine (later steps) is wired in; at this step the settings are parsed and
  * validated but drive no consensus behaviour.
  *
  * @param enabled      master switch for the T2 HotStuff fast-finality path
  * @param roundTimeout pacemaker round timeout; on expiry the round advances and finality falls back
  *                     to feature-25 Deterministic Finality (the chain never halts)
  */
case class HotStuffSettings(
    enabled: Boolean,
    roundTimeout: FiniteDuration
) derives ConfigReader {
  if (enabled) require(roundTimeout.toMillis > 0, "dcc.hotstuff.round-timeout must be positive when hotstuff is enabled")
}
