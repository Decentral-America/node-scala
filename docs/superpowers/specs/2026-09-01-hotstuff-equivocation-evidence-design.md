# T5: Deterministic HotStuff Equivocation Evidence — Design

## Context

The prior F-3 plan (`docs/superpowers/plans/2026-09-01-hotstuff-equivocation-detection.md`, Tasks 1-4 merged, Task 5 held) wired `HotStuffSafety.equivocators` into real vote-ingress traffic: detection, ERROR logging, a `/node/status` metric, and a critical alert. Task 5 attempted to feed a detected equivocator directly into `conflictGenerators`, but a final whole-branch review found two Critical issues, both real:

1. **The exclusion silently evaporates.** `FinalizationState.init` rebuilds `conflictGenerators` at every key block exclusively from persisted, header-derived data (`Caches.scala`, sourced from `block.header.finalizationVoting.conflict`). Nothing ever wrote HotStuff's detection into that persisted store, so a local, in-memory exclusion lived for at most one liquid block, then vanished — permanently, since the tracker's once-per-lifetime dedup guarantees the same voter is never re-reported.
2. **Detection is node-local, so the exclusion is non-deterministic across nodes.** Which votes a given node happened to receive, and when its own pacemaker's view advanced far enough to prune the tracker, differ node to node. Two honest nodes can reach different `conflictGenerators` sets for the same block, perturbing `FinalizationState.isParentFinalized`'s stake denominator differently — a genuine cross-node finalization-divergence mechanism, the exact class of bug this codebase's own investigation history (`CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §9) has repeatedly chased down.

This spec replaces Task 5 with a deterministic design, mirroring the pattern T0's own conflict-detection already uses successfully: `BlockEndorsement`/`FinalizationVoting.conflict` — a locally-detected conflict is packaged as portable, independently-verifiable signed evidence, embedded in the block/microblock, and every node re-derives the identical `conflictGenerators` addition by verifying that evidence against the block's own signed bytes, not by trusting a peer's local observation.

**This is item T5 in `docs/hotstuff-audit-readiness.md`** ("Equivocation → slashing not wired to `conflictGenerators`") — already flagged as required work before HotStuff can move toward authoritative status.

## What Already Exists (verified, not assumed)

The wire format for this exact mechanism is **already designed, reviewed, and published** — it was never wired up on the node-scala side:

- `packages/sdk/protobuf-schemas` (the `DecentralChain` monorepo) commit `959331f76` (2026-08-22) added `HotStuffEquivocationProof` and `FinalizationVoting.hotstuff_conflicts` to `dcc/block.proto`, schema version **1.6.6**, already published to Maven and cached locally (`~/.m2/repository/io/decentralchain/protobuf-schemas/1.6.6/`).
- The schema's own comments state the exact design this spec re-derives independently: kept as a separate field from `EndorseBlock`/`conflict_endorsements` because a `HotStuffVote`'s signed message shape differs from a `BlockEndorsement`'s; proto3 default-empty semantics for backward compatibility (an old peer never sets the field, decodes as empty = "no equivocation evidence"); gated behind `dcc.hotstuff.slashing-enabled` (default false).
- `node-scala`'s build (`project/Dependencies.scala:43`) is still pinned to protobuf-schemas **1.6.5** — the dependency was simply never bumped.

This spec's job is the node-scala-side implementation against this already-designed, already-published schema — bounded, not open-ended protocol design.

## Design

### 1. Schema dependency bump

Bump `project/Dependencies.scala`'s `protobuf-schemas` version from `1.6.5` to `1.6.6`. Real Scala protobuf bindings for `HotStuffEquivocationProof` and `FinalizationVoting.hotstuffConflicts` (generated field name) become available automatically via the existing scalapb build step — no manual binding code needed for the wire types themselves.

### 2. `HotStuffEquivocationProof` Scala wrapper

New case class in `com.decentralchain.block` (alongside `BlockEndorsement`, matching its exact `toProtobuf`/`fromProtobuf` conversion pattern already used by `HotStuffVote`/`QuorumCertificate` in `network/messages.scala`):

```scala
case class HotStuffEquivocationProof(
    voterIndex: Int,
    view: Int,
    phase: HotStuffPhase,
    voteA: HotStuffVote,
    voteB: HotStuffVote
)
```

No signature of its own — verification re-derives from `voteA.signature`/`voteB.signature`, each independently checkable against the named voter's real BLS key via the existing `HotStuffQuorum.verifyVote`/`BlsUtils.verifyBasic` machinery. This is the same "the proof carries real signatures, not a claim" property `BlockEndorsement` already has.

### 3. `FinalizationVoting` gains `hotstuffConflicts`

```scala
case class FinalizationVoting(
    valid: Seq[GeneratorIndex],
    finalizedHeight: Height,
    aggregatedEndorsement: Option[BlsSignature],
    conflict: Seq[BlockEndorsement],
    hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty
)
```

Defaulted for backward compatibility with every existing call site (matches the schema's own proto3 default-empty semantics). `FinalizationVoting.combine` (used when accumulating across microblocks within one liquid block) is extended to concatenate `hotstuffConflicts` the same way it already concatenates `conflict`.

### 4. `EquivocationTracker` returns real proofs, not bare indexes

`EquivocationTracker.recordVote`'s return type changes from `Set[Int]` to `Set[HotStuffEquivocationProof]`. The two conflicting votes are already present in the tracker's internal buffer (`EquivocationTracker.blockIdsFor`'s existing scan, generalized) — package them into a real proof rather than discarding all but the voter index. `HotStuffCoordinator.recordAcceptedVote`/`HotStuffEffects.onEquivocation` thread the real `HotStuffEquivocationProof` through instead of a bare `Int` + `Set[BlockId]`.

### 5. Local queue → microblock, via T0's existing drain seam

`DetectedEquivocatorsRegistry` becomes typed on `HotStuffEquivocationProof` (not `GeneratorIndex`): `report(proof: HotStuffEquivocationProof): Unit`, `drain(): Set[HotStuffEquivocationProof]`.

Rather than inventing a new miner-side call site, this reuses T0's existing, proven drain point: `EndorsementStorage.tryCollectAndClear(endorsedId): Option[FinalizationVoting]` (called once per microblock by `MicroBlockMinerImpl.scala:163`) is extended to also drain `DetectedEquivocatorsRegistry` and merge the result's `hotstuffConflicts` into the `FinalizationVoting` it already returns. `MicroBlockMinerImpl` needs no new call site — it already calls `tryCollectAndClear` for T0's evidence, and now transparently picks up HotStuff's evidence too, riding the same microblock.

### 6. Deterministic verification — every node re-checks, nobody trusts a claim

`validateFinalizationVoting` (`appender/package.scala:348`) gains a new validation step, structurally parallel to the existing `fv.conflict.traverse(validateConflictingEndorsement(...))`:

```scala
_ <- fv.hotstuffConflicts.traverse(validateHotStuffEquivocationProof(blockchain, proof, /* committee at proof.view's referenced height/epoch */))
```

`validateHotStuffEquivocationProof` (new function, same file) re-verifies: both `voteA`/`voteB` carry the SAME `voterIndex`/`view`/`phase` and DIFFERENT `blockId` (the proof's own internal consistency); both signatures independently verify against that voter's real committee key at the committee epoch the votes claim (reusing `HotStuffQuorum.verifyVote`'s existing signature-checking logic, not reimplementing it). A proof failing this check is rejected the same way an invalid `BlockEndorsement` is rejected today — the whole `FinalizationVoting` fails validation, matching this file's existing all-or-nothing per-block validation pattern.

### 7. Verified proofs feed `conflictGenerators`

Only after `validateHotStuffEquivocationProof` succeeds does `fv.hotstuffConflicts.map(_.voterIndex)` union into `conflictGenerators`, the same way `fv.conflict.map(_.endorserIndex)` already does — one unconditional union in `FinalizationState.append`, feeding the same persisted store T0's own conflicts already reach (closing Critical #1: this now DOES persist past a key block, because it's block-header data like everything else `Caches.scala` derives `conflictGenerators` from).

Because every node derives this from the same signed block bytes, not from local vote-arrival timing, Critical #2 (cross-node divergence) is closed by construction — this is now exactly as deterministic as T0's own mechanism.

### 8. Feature gate: `dcc.hotstuff.slashingEnabled`

New field on `HotStuffSettings`, default `false`, doc-commented in the same style as `authoritative` (explicit "TESTNET-ONLY... do NOT enable on mainnet until externally audited" framing, cross-referencing `docs/hotstuff-audit-readiness.md`'s T5 item). When `false`:
- `EquivocationTracker` still detects, `NodeHotStuffEffects.onEquivocation` still logs ERROR and updates `HotStuffEquivocationObservation`'s metric (Tasks 1-4, entirely unaffected — this is why they were correctly kept even while Task 5 was reworked).
- `DetectedEquivocatorsRegistry.report` is a no-op (or the registry itself is never constructed/wired) — no proof ever reaches a microblock, `hotstuffConflicts` stays empty everywhere, `conflictGenerators` is byte-for-byte unaffected by this feature.

When `true` (testnet-only, matching `authoritative`'s existing risk posture): the full pipeline above runs.

## Files

- Modify: `project/Dependencies.scala` (schema version bump)
- Create: `HotStuffEquivocationProof` in `node/src/main/scala/com/decentralchain/block/` (new file or added to an existing block-evidence file, following existing organization)
- Modify: `node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala` (new field + `combine` update)
- Modify: `node/src/main/scala/com/decentralchain/state/EquivocationTracker.scala` (return type change)
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala` (thread real proof through `onEquivocation`)
- Modify: `node/src/main/scala/com/decentralchain/state/DetectedEquivocatorsRegistry.scala` (retype)
- Modify: `node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala` (drain HotStuff's registry alongside T0's own accumulator)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala` (`validateHotStuffEquivocationProof` + wiring into `validateFinalizationVoting`)
- Modify: `node/src/main/scala/com/decentralchain/state/FinalizationState.scala` (union verified proofs' voter indexes into `conflictGenerators`)
- Modify: `node/src/main/scala/com/decentralchain/settings/HotStuffSettings.scala` (new `slashingEnabled` field)
- Test files: unit tests for `HotStuffEquivocationProof` construction/round-trip, `validateHotStuffEquivocationProof` (accept a genuine proof, reject a forged/malformed one), an end-to-end test proving two independently-running coordinators (in the DST harness, or a real multi-node integration test) converge on the identical `conflictGenerators` after one detects and the evidence propagates via a block.

## What This Explicitly Does NOT Change

- `HotStuffSafety.equivocators`'s existing signature/logic (still the pure detector; unmodified).
- Tasks 1-4 of the prior F-3 plan (detection, logging, metric, alert) — all already merged, all unaffected, all remain the correct, safe, already-shipped foundation this spec builds on.
- No vote/QC acceptance rule changes — this affects `conflictGenerators` (a stake-exclusion input to finalization voting), never HotStuff's own vote/QC/commit logic.
- Not implementing an actual slashing/penalty mechanism beyond the existing `conflictGenerators` stake-exclusion — that's what T0's own mechanism already does, and this reuses it exactly, not inventing a new penalty.

## Self-Review

- **No re-derivation of an already-solved problem:** the wire format was already designed and published (schema 1.6.6); this spec is the node-scala-side implementation against it, not a new protocol design.
- **Mirrors a proven pattern exactly:** every design decision (proof shape, carriage via `FinalizationVoting`, drain-point reuse, verification-before-trust, feature gate) has a direct, working precedent in this codebase's own T0 mechanism — verified by reading the real code at each seam, not assumed by analogy.
- **Closes both Critical findings by construction:** persistence (Critical #1) because `conflictGenerators` now derives from block-header data like every other input to that store; determinism (Critical #2) because every node verifies the same signed bytes rather than trusting local observation.
- **Explicit backward-compatibility story**, matching this codebase's established pattern for prior schema-versioned fields (`committee_epoch`'s exact precedent, cited directly in the schema's own comments).
