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
  * @param authoritative TESTNET-ONLY opt-in escape hatch from the observational-only commit mode. When
  *                     `true` (and only when `enabled` is also `true`), a genuine HotStuff `commitQC` is
  *                     allowed to RAISE the authoritative feature-25 finalized height (never lower it),
  *                     but ONLY for a block already present on this node's own canonical/synced chain --
  *                     see `NodeHotStuffEffects.onCommit` / `BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight`.
  *                     Defaults to `false`: today's fully-observational behaviour (`hotStuffFinalizedHeight`
  *                     metric only, feature-25 remains sole authority) is unchanged unless BOTH flags are
  *                     explicitly set. Do NOT set `true` on mainnet -- this is ahead of the external audit
  *                     (see docs/hotstuff-audit-readiness.md) and is accepted, scoped risk for testnet only.
  * @param slashingEnabled T5 rev.2 (docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md):
  *                     gates ONLY whether THIS node's miner folds pending equivocation proofs into
  *                     blocks it forges. Proof VALIDATION and the conflictGenerators union are
  *                     unconditional (gated solely by feature-29 activation) -- a node with this
  *                     flag off applies exclusions from received proof-carrying blocks identically,
  *                     so mixed flag settings can never diverge consensus. TESTNET-ONLY until
  *                     externally audited; consequences are real (generation-deposit forfeiture).
  *                     OPERATIONAL NOTE: a replica's very first boot has no persisted lastVotedView
  *                     (T11 first-boot window) -- do not run a first-boot replica as a committee
  *                     member with slashing active until it has participated once.
  */
case class HotStuffSettings(
    enabled: Boolean,
    roundTimeout: FiniteDuration,
    settledDepth: Int = 3,
    authoritative: Boolean = false,
    slashingEnabled: Boolean = false
) derives ConfigReader {
  if (enabled) {
    require(roundTimeout.toMillis > 0, "dcc.hotstuff.round-timeout must be positive when hotstuff is enabled")
    require(settledDepth >= 1, "dcc.hotstuff.settled-depth must be >= 1 when hotstuff is enabled")
  }
  require(!authoritative || enabled, "hotstuff.authoritative requires hotstuff.enabled")
  require(!slashingEnabled || enabled, "hotstuff.slashing-enabled requires hotstuff.enabled")
}
