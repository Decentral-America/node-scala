# T5: Deterministic HotStuff Equivocation Evidence — Design (rev. 2)

> **Revision 2 (2026-09-01).** Rewritten after an adversarial design review found four design defects
> (C1–C4), four false/incomplete premises (H1–H5), and three hardening gaps (M1–M3) in revision 1.
> Every claim below was re-verified against real code on `dev` @ `49809b487c`. Findings are cited
> inline as [C1]…[M3] where the design decision exists to close them.

## Context

The goal is unchanged: make a detected T2 HotStuff equivocation (a committee member signing two
different blocks at the same `(view, phase)`) actually exclude the offender's stake via the existing
feature-25 `conflictGenerators` mechanism — deterministically, so every node computes the identical
exclusion set from the same block bytes.

The first attempt (F-3 plan Task 5) fed node-local detection straight into `conflictGenerators` and
was rejected for two Critical reasons: the exclusion evaporated after one liquid block, and detection
is node-local so honest nodes could diverge. The replacement principle stands: package the two
conflicting signed `HotStuffVote`s as portable, independently-verifiable evidence
(`HotStuffEquivocationProof`), carry it in the block itself, and have every node re-verify before
trusting it — the same shape as T0's `FinalizationVoting.conflict` / `BlockEndorsement` mechanism.

## Corrected premises (what revision 1 got wrong)

1. **[H1] F-3 Tasks 1–4 are NOT merged.** `dev` @ `49809b487c` contains no `EquivocationTracker`,
   no `DetectedEquivocatorsRegistry`, no `HotStuffEffects.onEquivocation`, no
   `HotStuffEquivocationObservation`, and no `/node/status` field. `HotStuffSafety.equivocators`
   is still dead code (BFT audit F-3 still open). Only the infra side (exporter metric + critical
   alert) landed — it scrapes a field no node binary emits. Detection must be built as part of this
   work, not assumed.
2. **[H2] Schema 1.6.6 is NOT published.** Maven Central's `maven-metadata.xml` ends at 1.6.5
   (verified live 2026-09-01; the 1.6.6 POM 404s). The 1.6.6 artifacts exist only in the local
   `~/.m2` (locally installed 2026-08-22), and the source commit `959331f76` sits on the unmerged
   monorepo branch `feat/hotstuff-equivocation-proof-schema` (monorepo `main` is at 1.6.5 with no
   `HotStuffEquivocationProof`). Merging that branch's two schema commits and running the
   `publish-protobuf-schemas.yml` workflow (version `1.6.6`) is a hard prerequisite.
3. **[H3] A full prior T5 attempt exists on unmerged branch `fix/height-3325-and-hotstuff-slashing`**
   (node-scala, 2026-08-22): `HotStuffEquivocationEvidence`, PB conversions (including the
   Dependencies 1.6.6 bump), coordinator-side detection over vote-pool buckets, and a miner-side
   key-block fold (`Miner.foldHotStuffConflicts`). It had NO receive-side validation and NO
   `conflictGenerators` union — detection/carriage only. This design **formally supersedes** that
   branch and the F-3 plan's Tasks 1–2/5; it deliberately reuses the branch's proven patterns
   (bucket-based detection, miner fold, `Application.scala` provider cell) as reference
   implementations, re-derived against current `dev` (the branch predates the upstream sync and does
   not cherry-pick cleanly — e.g. `FinalizationVoting.withValid` changed shape).

## Design

### 0. Prerequisite: publish schema 1.6.6

Merge monorepo commits `959331f76` + `f1dd8a76b` (branch `feat/hotstuff-equivocation-proof-schema`)
to `main` via PR, then dispatch `.github/workflows/publish-protobuf-schemas.yml` with
`version: 1.6.6` and verify the POM is live on Maven Central before any node-scala CI depends on it.
The schema content itself is confirmed correct (read directly from `proto/dcc/block.proto`):
`HotStuffEquivocationProof { voter_index, view, phase, vote_a, vote_b }` and
`FinalizationVoting.hotstuff_conflicts = 5`.

### 1. Schema dependency bump

`project/Dependencies.scala:43`: `protobuf-schemas` `1.6.5` → `1.6.6`.

### 2. `HotStuffEquivocationProof` wrapper — derived fields only [C3]

New case class in `com.decentralchain.consensus.hotstuff` (same placement the prior branch proved
compiles; `consensus.hotstuff` already imports `network.HotStuffVote`, and `block.FinalizationVoting`
importing `consensus.hotstuff` is module-internal):

```scala
case class HotStuffEquivocationProof(voteA: HotStuffVote, voteB: HotStuffVote) {
  def voterIndex: Int      = voteA.voterIndex
  def view: Int            = voteA.view
  def phase: HotStuffPhase = voteA.phase
  def committeeEpoch: Int  = voteA.committeeEpoch
}
```

**No stored top-level fields.** The schema's redundant `voter_index`/`view`/`phase` are an attack
surface: if the slashed index were read from an unchecked top-level field while signatures verify
against the embedded votes, a real equivocation pair by voter X could be wrapped with
`voter_index = Y` and frame an innocent generator. The wrapper derives everything from `voteA`;
PB **encode** writes the derived values; PB **decode rejects** (fails block parsing, exactly like a
malformed `conflict` endorsement already does in `PBFinalizationVotings`) any proof whose top-level
fields disagree with `vote_a`, or whose `vote_a`/`vote_b` is missing.

This deliberately **reverses the prior branch's silent-drop decode** ("best-effort slashing
evidence"): under this design proofs are consensus-critical inputs to `conflictGenerators`, and
silent drops would also break decode/re-encode round-trip identity for header bytes. Strict decode
is deterministic (same bytes ⇒ same rejection on every node).

Consistency rule (one place, used by both the coordinator and block validation):

```scala
def consistent: Either[String, Unit] = for {
  _ <- Either.raiseUnless(voteA.voterIndex == voteB.voterIndex)("proof votes name different voters")
  _ <- Either.raiseUnless(voteA.view == voteB.view)("proof votes are for different views")
  _ <- Either.raiseUnless(voteA.phase == voteB.phase)("proof votes are for different phases")
  _ <- Either.raiseWhen(voteA.phase == HotStuffPhase.HOTSTUFF_PHASE_UNSPECIFIED)("unspecified phase")
  _ <- Either.raiseUnless(voteA.committeeEpoch == voteB.committeeEpoch)("proof votes span committee epochs") // [C2]
  _ <- Either.raiseUnless(voteA.blockId != voteB.blockId)("proof votes target the same block — not an equivocation")
} yield ()
```

The **epoch-equality requirement is new and load-bearing** [C2]: `committeeEpoch` is inside each
vote's signed bytes (`HotStuffQuorum.voteMessage`, T10), so it cannot be relabeled — but nothing
forces two independently-signed votes to share an epoch, and the same `voterIndex` in two different
epochs can be two different physical generators. A cross-epoch pair is not evidence of anything.
(`HotStuffSafety.equivocators` groups on `(voterIndex, view, phase)` only — the coordinator must
apply `consistent` before treating a detected pair as a proof.)

Signature verification reuses the canonical message builder, never reimplements it:

```scala
def signaturesValid(blsKeyOf: Int => Option[BlsPublicKey]): Either[String, Unit] = for {
  pk <- blsKeyOf(voterIndex).toRight(s"voter index $voterIndex outside committee")
  _  <- verifyOne(voteA, pk, "voteA")
  _  <- verifyOne(voteB, pk, "voteB")
} yield ()
// verifyOne: BlsUtils.verifyBasic(v.signature.arr,
//   HotStuffQuorum.voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt, v.committeeEpoch), pk.arr)
```

### 3. `FinalizationVoting.hotstuffConflicts`

As in revision 1 (and the prior branch, whose diff is the reference):

```scala
case class FinalizationVoting(
    valid: Seq[GeneratorIndex],
    finalizedHeight: Height,
    aggregatedEndorsement: Option[BlsSignature],
    conflict: Seq[BlockEndorsement],
    hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty
)
```

- `nonEmpty` gains `|| hotstuffConflicts.nonEmpty`.
- `combine(old, recent)` concatenates `hotstuffConflicts` exactly as it concatenates `conflict`
  (`FinalizationVoting.scala:37-38`).
- `PBFinalizationVotings.vanilla/protobuf` extended; decode is strict per §2.

### 4. On-chain feature gate: `BlockchainFeatures.HotStuffEquivocationEvidence` (id 28) [H4]

Proto3 "backward compatibility" is wire-level only: a node running today's binary decodes and
**ignores** `hotstuff_conflicts`, computes a different `conflictGenerators` set than an upgraded
node from the same block bytes, and diverges on `isParentFinalized`'s stake denominator and on
deposit-punishment state — the exact §9 divergence class. The standard fix is the codebase's own:
an activation-voted feature.

- New `BlockchainFeature(28, "HotStuff Equivocation Evidence")` in `BlockchainFeature.scala`,
  added to `dict` (making it `implemented`/votable).
- New `Blockchain.supportsHotStuffEquivocationEvidence(height)` helper, exactly mirroring
  `supportsFinalizationVoting` (`Blockchain.scala:309`).
- `validateFinalizationVoting` rejects any block with non-empty `hotstuffConflicts` before
  activation. After activation, upgraded miners may include proofs; by the feature-voting threshold,
  a supermajority of generators runs evidence-aware code by then — the same upgrade-lag risk profile
  as every prior feature.

### 5. Config flag `dcc.hotstuff.slashingEnabled` gates PRODUCTION ONLY [H5]

New `HotStuffSettings.slashingEnabled: Boolean = false` with
`require(!slashingEnabled || enabled)` (prior branch's diff is the reference; update its doc-comment
to this revision).

**The determinism contract, stated explicitly:**

- `slashingEnabled` gates exactly one thing: whether **this node's miner** folds pending evidence
  into blocks it forges. Nothing else.
- **Validation of received proofs and the `conflictGenerators` union are UNCONDITIONAL** in
  evidence-aware binaries (gated only by feature-28 activation, which is chain state, identical on
  every node). A node with `slashingEnabled = false` that receives a valid proof-carrying block
  validates it and applies the exclusion identically to a node with the flag on. Mixed flag settings
  can therefore never produce divergent `conflictGenerators` — the flag only affects who volunteers
  evidence.
- Detection + ERROR log + metric are unconditional whenever `hotstuff.enabled` (observability must
  not depend on the slashing decision).

### 6. Detection & evidence accumulation — in the coordinator (supersedes F-3 Tasks 1–2)

No `state.EquivocationTracker`. Detection happens inside `HotStuffCoordinator.Enabled.onVote`, using
the prior branch's proven insight: `pool.pending` is keyed by the full `(view, phase, blockId)`
target, so a double-signer's votes land in different buckets — gather all buckets sharing
`(view, phase)` before running `HotStuffSafety.equivocators`, then build the proof from the two
distinct-`blockId` votes, and accept it only if `proof.consistent` passes (this is where cross-epoch
pairs are discarded [C2]) and both signatures verify against the current committee. Only verified
proofs are accumulated — an attacker cannot frame an honest voter with a forged vote, because the
forged vote's signature fails before the proof is stored.

New `HotStuffEffects.onEquivocation(proof: HotStuffEquivocationProof): Unit` hook: production
implementation (`NodeHotStuffEffects`) logs ERROR and bumps a process-global
`HotStuffEquivocationObservation` counter exposed as `/node/status`'s `hotStuffEquivocationsTotal`
(present only when > 0) — this carries forward F-3 Task 3 unchanged in substance and finally feeds
the already-deployed infra metric/alert [H1].

**Retention, not drain [M2]:** the coordinator keeps accumulated proofs until either (a) the voter
is already excluded on-chain (`blockchain.conflictGenerators(period)` contains it — meaning some
block carried the evidence), or (b) the proof's epoch has expired (`committeeEpoch <
current period index`), at which point it is pruned as unusable [C2]. A failed forge, an orphaned
microblock, or a reorg therefore cannot permanently lose evidence — the miner just offers it again
at the next key block. (Detection on non-miner nodes still never reaches the chain — there is no
proof gossip; accepted limitation, documented, same trust shape as T0 where only endorsement
*recipients* who mine can embed conflicts.)

### 7. Carriage: key-block forge fold (supersedes rev. 1's `EndorsementStorage` drain) [C4]

Revision 1's plan — extending `EndorsementStorage.tryCollectAndClear` — fails two ways:
`tryCollectAndClear` returns `None` whenever T0 has no new endorsements
(`EndorsementStorage.scala:128-158`), stranding proofs indefinitely; and a synthesized proofs-only
`FinalizationVoting` is rejected by `validateFinalizationVoting`'s emptiness check
(`appender/package.scala:360`). Instead:

- `MinerImpl.forgeBlock` folds pending evidence at the key-block `FinalizationVoting` build site
  (prior branch's `Miner.foldHotStuffConflicts` / `withHotStuffConflicts` pattern, including the
  `Application.scala` `@volatile` provider-cell that bridges Miner-before-coordinator construction
  order). The fold: only when `slashingEnabled`; only proofs whose `committeeEpoch` equals the
  period index of the height being forged; only voters not already in
  `blockchain.conflictGenerators(period)`; deduplicated by voter; synthesizes a
  `FinalizationVoting` when T0 contributed none.
- `validateFinalizationVoting`'s emptiness check is relaxed to
  `fv.valid.isEmpty && fv.conflict.isEmpty && fv.hotstuffConflicts.isEmpty` — a proofs-only FV is
  now legal (post feature-28 activation) [C4].
- Evidence latency is at most one key block. Microblock carriage is deliberately NOT added — the
  block-level `validateFinalizationVoting` call in the microblock append path
  (`BlockchainUpdaterImpl.scala:606`) already validates the combined header FV, so validation
  coverage is identical either way, and key-block-only carriage avoids touching `EndorsementStorage`
  and `MicroBlockMinerImpl` at all.

### 8. Deterministic verification in `validateFinalizationVoting`

New `validateHotStuffEquivocationProof` in `state/appender/package.scala`, called via
`fv.hotstuffConflicts.traverse(...)` structurally parallel to the existing
`validateConflictingEndorsement` traverse (`:382-394`). Full rule set, all deterministic functions
of chain state + block bytes:

1. Feature 28 active at this height (else any non-empty `hotstuffConflicts` fails the block).
2. `proof.consistent` (§2: same voter/view/phase, same epoch, different blockIds, phase specified).
3. **Epoch = block period** [C2]: `proof.committeeEpoch == blockGenerationPeriod.index` (the period
   already computed at `:367-369`). This pins the proof's index space to the same committee
   (`allCommittedGenerators`, `:370`) whose indexes `conflictGenerators` punishes — the wrong
   generator can never be excluded, and stale evidence from a previous period is invalid (it expires;
   the coordinator prunes it, §6).
4. `proof.voterIndex` within `allCommittedGenerators` bounds.
5. **No duplicates / no re-litigation [M3]:** duplicate `voterIndex` within `hotstuffConflicts`
   rejected (mirrors `:363`); voter already in `knownConflictGenerators` rejected (mirrors the
   "Second conflicting endorsement from one generator" rule, `:325`); voter also present in this
   FV's `conflict` endorser indexes rejected (one exclusion per voter per block is enough — and it
   bounds verification work: a miner can never make the network verify more proofs than committee
   members not yet excluded).
6. `proof.signaturesValid` against `allCommittedGenerators(voterIndex)`'s BLS key — two
   `BlsUtils.verifyBasic` calls over `HotStuffQuorum.voteMessage(...)` bytes.
7. All-or-nothing: any failing proof fails the whole block's validation, matching the file's
   existing pattern.

Unlike T0's rule set, a proof from the **miner itself** is allowed (an equivocating leader must be
slashable), and no balance check applies (committee membership at the epoch is the criterion; the
vote was only possible for a committed generator).

DoS posture [M3]: proofs travel only inside miner-signed blocks; rule 5 caps meaningful proofs at
committee size; block size caps raw bytes; all-or-nothing rejection means a garbage-stuffing miner
just invalidates its own block. No arbitrary-peer spam vector exists (proofs are not gossiped).

### 9. Exclusion + persistence — BOTH layers [C1]

Two places derive `conflictGenerators` from a `FinalizationVoting`, and **both** must read the new
field (revision 1 missed the second, which is why its Critical #1 claim was wrong):

- **Liquid (in-memory):** `FinalizationState.append` (`FinalizationState.scala:23`):
  `newConflictGenerators = fv.conflict.map(_.endorserIndex) ++ fv.hotstuffConflicts.map(p => GeneratorIndex(p.voterIndex))`.
  Flows into `isParentFinalized`'s stake denominator and the accumulated set. No new parameter —
  the proofs ride inside the FV the function already receives.
- **Persisted (key-block):** `Caches.doAppend`'s extraction (`Caches.scala:409-412`) gains the same
  union; that single value feeds both the `conflictGeneratorsCache` update (`:427-429`) and
  `RocksDBWriter`'s `Keys.conflictGenerators` persistence (`:753`) — so the exclusion now genuinely
  survives key blocks, restarts (`loadConflictGenerators`), and is period-scoped and
  rollback-deleted (`RocksDBWriter.scala:1163`) exactly like T0's own conflicts.

Consequence to state plainly: `conflictGenerators` membership drives **generation-deposit
forfeiture** (`RocksDBWriter.collectGenerationDepositChanges`, `:1493` `punishmentHeight`), not just
quorum exclusion. This design intentionally applies the same economic penalty T0 conflicts already
carry — it is real slashing, and that is the point of T5.

Reorg semantics (review question 2): deterministic by construction — exclusions are pure functions
of applied block headers; a rollback deletes the period-keyed entries; every node applying the same
blocks computes the same sets at every height. Evidence in an orphaned block is re-offered by the
retention rule (§6).

### 10. Honest-node protection: persist `lastVotedView` [M1]

Slashing converts an honest double-sign from an alert into deposit forfeiture. The audited honest
double-sign paths are: watchdog reset (closed — F-2 fix preserves `lastVotedView`), and **process
restart** (still open: only `lockedQC` is persisted, via `HotStuffLockedQCStore`; a restarted
replica boots with `lastVotedView = -1` and can legitimately re-sign a conflicting vote in a view it
already voted). Before slashing can be enabled anywhere, persist `lastVotedView`:

- New `HotStuffLastVotedViewStore`, a sibling of `HotStuffLockedQCStore` (same atomic-write,
  never-throw, log-and-continue contract; trivial payload — the int as UTF-8), stored next to
  `locked-qc.dat`.
- Persisted on every vote cast; loaded as `initialLastVotedView` at coordinator construction.
- T11's first-ever-boot window remains (nothing to load) — documented residual risk, bounded as
  before; a first-boot replica should not be a committee member with slashing on until it has
  participated (operational note in the settings doc-comment).

### 11. What this explicitly does NOT change

- `HotStuffSafety.equivocators` — unmodified (the coordinator feeds it wider input, per §6).
- Vote/QC acceptance rules, the 3-chain commit rule, pacemaker — untouched.
- T0's endorsement pipeline (`EndorsementStorage`, `BlockEndorser`) — untouched (rev. 1 would have
  modified `EndorsementStorage`; this revision does not).
- No new penalty mechanism — reuses `conflictGenerators` exactly, including its existing deposit
  forfeiture.

## Files (complete, both layers) — see the implementation plan for tasks

- Monorepo: merge schema branch + publish 1.6.6 (workflow dispatch).
- Modify: `project/Dependencies.scala` (1.6.6)
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationProof.scala`
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservation.scala`
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffLastVotedViewStore.scala`
- Create: `node/src/main/scala/io/decentralchain/protobuf/block/PBHotStuffEquivocationProofs.scala`
- Modify: `node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala`
- Modify: `node/src/main/scala/io/decentralchain/protobuf/block/PBFinalizationVotings.scala`
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (feature 28)
- Modify: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (`supportsHotStuffEquivocationEvidence`)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala` (validation + emptiness relaxation + generator-set exclusion)
- Modify: `node/src/main/scala/com/decentralchain/state/FinalizationState.scala` (union) **[C1]**
- Modify: `node/src/main/scala/com/decentralchain/database/Caches.scala` (persisted extraction) **[C1]**
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala` (detection, retention, `onEquivocation`, `initialLastVotedView`)
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala` (log + metric)
- Modify: `node/src/main/scala/com/decentralchain/api/http/NodeApiRoute.scala` (`hotStuffEquivocationsTotal`)
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` (fold at key-block forge)
- Modify: `node/src/main/scala/com/decentralchain/Application.scala` (provider cell + stores wiring)
- Modify: `node/src/main/scala/com/decentralchain/settings/HotStuffSettings.scala` + `application.conf` (`slashingEnabled`)
- Modify (docs, post-landing): `docs/hotstuff-audit-readiness.md` T5 entry; supersession notes on
  `docs/superpowers/plans/2026-08-22-hotstuff-equivocation-slashing.md` and
  `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-detection.md`.

## Self-review against the findings

- **C1** closed: both derivation layers (FinalizationState + Caches) listed and specified.
- **C2** closed: epoch equality in `consistent` + epoch-equals-block-period validation rule 3 +
  coordinator-side expiry pruning.
- **C3** closed: wrapper stores no top-level fields; strict decode rejects mismatches.
- **C4** closed: key-block fold replaces the `EndorsementStorage` drain; emptiness check relaxed.
- **H1** closed: detection/logging/metric built here (coordinator + effects + observation), not assumed.
- **H2** closed: schema publish is prerequisite task 0 with a live-Central verification step.
- **H3** closed: prior branch formally superseded; its proven patterns reused as references.
- **H4** closed: feature 28 activation gates evidence validity on-chain.
- **H5** closed: flag gates production only; validation/union unconditional — stated as a contract.
- **M1** closed: `lastVotedView` persisted; first-boot residual documented.
- **M2** closed: retention-until-on-chain-or-expired replaces drain-and-lose.
- **M3** closed: dedup + already-known + overlap rules bound verification work; DoS posture stated.
