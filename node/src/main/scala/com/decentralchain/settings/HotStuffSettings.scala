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
  * @param settledDepth how many blocks behind the tip HotStuff runs. A view targets the canonical
  *                     key-block at height `tip - settledDepth`; this must exceed the inter-node tip
  *                     skew so that, by the time a leader proposes a view, every node has SETTLED that
  *                     block (its id is final, not the liquid tip id) and votes converge on one target.
  *                     Too small ⇒ replicas still see the height as their liquid tip and reject the
  *                     proposal ⇒ no quorum. Too large ⇒ HotStuff finalizes further behind the tip.
  */
case class HotStuffSettings(
    enabled: Boolean,
    roundTimeout: FiniteDuration,
    settledDepth: Int = 3
) derives ConfigReader {
  if (enabled) {
    require(roundTimeout.toMillis > 0, "dcc.hotstuff.round-timeout must be positive when hotstuff is enabled")
    require(settledDepth >= 1, "dcc.hotstuff.settled-depth must be >= 1 when hotstuff is enabled")
  }
}
