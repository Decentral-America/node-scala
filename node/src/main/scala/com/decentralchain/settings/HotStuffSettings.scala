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
  *                     allowed to RAISE the "authoritative" feature-25 finalized height (never lower it),
  *                     but ONLY for a block already present on this node's own canonical/synced chain --
  *                     see `NodeHotStuffEffects.onCommit` / `BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight`.
  *                     Defaults to `false`: today's fully-observational behaviour (`hotStuffFinalizedHeight`
  *                     metric only, feature-25 remains sole authority) is unchanged unless BOTH flags are
  *                     explicitly set. Do NOT set `true` on mainnet -- this is ahead of the external audit
  *                     (see docs/hotstuff-audit-readiness.md) and is accepted, scoped risk for testnet only.
  *
  *                     PLAIN STATEMENT (audit F-1, HIGH under `authoritative = true`; documented per the
  *                     audit's recommendation option (b)): despite the name, the floor this flag lets
  *                     HotStuff raise is ADVISORY, NOT ENFORCING. There is no rollback refusal anywhere
  *                     in the codebase keyed on this value -- `removeAfter`/`rollbackTo`'s only depth
  *                     guard is `safeRollbackHeight` (independent of `finalizedHeight`), and
  *                     `ExtensionAppender`'s fork choice is pure score comparison with no finality check
  *                     at all. A block HotStuff has certified via this flag CAN be reorged away, and the
  *                     node will not refuse the rollback or even log it as a violation -- a rollback below
  *                     the floor silently CAPS the floor back down to the new tip
  *                     (`RocksDBWriter.scala`'s `hotStuffAuthoritativeFloor` handling) instead of being
  *                     rejected. The floor's only two real behavioural effects are (1) shortening the id
  *                     list `Blockchain.lastBlockIds` offers in `GetSignatures` (a probabilistic damper on
  *                     negotiating a deep fork, not a guard), and (2) suppressing feature-25's own
  *                     `BlockEndorser` voting/rebroadcast below the floor -- which is a second-order hazard
  *                     specific to this flag: if HotStuff raises the floor on a branch that later loses a
  *                     reorg, feature-25 endorsement activity was suppressed for a range of heights on the
  *                     branch that actually won, an interaction that is not otherwise tested or documented.
  *                     In short: `authoritative = true` provides a REPORTED finality number plus an
  *                     endorsement-suppression side effect, not reorg enforcement. Treat the name as
  *                     aspirational until (a) an explicit rollback refusal below the floor is designed,
  *                     reviewed, and shipped as its own consensus change -- see `docs/hotstuff-bft-audit-
  *                     2026-08-31.md` F-1 for why that is nontrivial (it can wedge a node that finalized a
  *                     minority branch). See also `docs/hotstuff-audit-readiness.md` for the corresponding
  *                     operator-facing caveat.
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
