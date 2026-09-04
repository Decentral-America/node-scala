# T2 HotStuff BFT — Adversarial Internal Audit (2026-08-31)

> **Scope:** the NEW surface area added since `docs/hotstuff-security-review.md` (2026-07-08/11) —
> `HotStuffWatchdog.scala`, the T10 cross-epoch-fork fix, `HotStuffCoordinator`/`HotStuffEngine`/
> `HotStuffPacemaker` (the "dangerous surface" that doc's residual-risk section explicitly left
> unaudited), and the `BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight` boundary
> (`BlockchainUpdaterImpl.scala:732-763`).
> **Reviewer:** internal adversarial code audit, deliberately skeptical, standing in for the external
> audit that was not commissioned. **Date:** 2026-08-31. **Branch:** `main`.
>
> ⚠️ **This is NOT an external third-party audit.** It is a fresh adversarial pass by a reviewer with
> full repo access but no ability to run a live multi-node network. Findings that require live
> multi-node or long-duration evidence are marked **UNVERIFIED — needs live testing** rather than
> being argued away. This document does not clear HotStuff for mainnet.
>
> **Cross-reference, not duplication:** the BLS proof-of-possession defense, the ≥2/3-by-stake quorum
> threshold, `verifyQC`'s signer/quorum/aggregate checks, the `safeToVote` rule and the monotonic lock
> rule were verified sound in `docs/hotstuff-security-review.md` and are **not** re-derived here. This
> audit assumes them and attacks what sits on top. §6 of
> `docs/consensus-divergences-from-upstream.md` (the committee-data live-cache sweep) is likewise
> taken as read; its conclusion is spot-checked in F-7 below but not re-litigated.

---

## Configuration posture at time of audit

| Setting | Default | Testnet | Notes |
|---|---|---|---|
| `dcc.hotstuff.enabled` | `false` | `true` | Gate for the whole engine. |
| `dcc.hotstuff.authoritative` | `false` | (see F-1) | Gate for `raiseHotStuffFinalizedHeight`. |
| `dcc.hotstuff.settled-depth` | `3` | `3` | `require(settledDepth >= 1)` when enabled. |
| `dcc.hotstuff.round-timeout` | `1200ms` | `1200ms` | Drives both `onRoundTimerTick` and `HotStuffWatchdog.check`. |
| `generation-period-length` | `1001` | `3000` / `1000` | Determines `GenerationPeriod.index` — the T10 committee epoch. |

Severity in this document is stated **twice** where it differs: once for the shipped default posture
(`authoritative = false`, observational only) and once for the testnet-opt-in posture
(`authoritative = true`). Several findings are Info-only in the former and High in the latter. That
distinction is the single most important thing to carry out of this document.

---

## Verified — sound (new surface only)

| Area | Finding |
|------|---------|
| **`raiseHotStuffFinalizedHeight` chain-agreement guard** | The core check is correct and correctly placed. `BlockchainUpdaterImpl.scala:745-747` requires `this.blockId(certifiedHeight) == certifiedBlockId` — the node's **own** canonical chain, looked up the same way every other consumer does, **not** re-derived from the QC. A QC certifying a block this node does not have at that height is refused before any state mutation. This is the right shape for the boundary and it does close the T10 cross-epoch-fork scenario *at this specific boundary* (a disjoint committee's block is not on this node's chain ⇒ refused). |
| **Defense-in-depth flag re-check** | `BlockchainUpdaterImpl.scala:737-744` re-reads `dccSettings.hotStuffSettings.authoritative` at the raise site itself, so the mainnet-safety boundary does not rest on the `Application.scala` wiring choosing an inert closure. A future caller reaching this method by a different path still cannot raise the floor. Genuinely good practice; I tried and failed to find a bypass. |
| **Monotonic persist** | `Caches.raiseHotStuffFinalizedHeight` (`Caches.scala:87-98`) raises only on `current.forall(_ < newFloor)`, under `synchronized`. Re-delivery of an applied or stale height is a pure no-op. The floor is stored **separately** from feature-25's own `currentFinalizedHeight` and combined as `max(...)` at read time, so an ordinary block append cannot silently overwrite it. Correct. |
| **No retroactive historical inflation** | `RocksDBWriter.finalizedHeightAt` (`RocksDBWriter.scala:197-208`) applies the HotStuff floor only when `floor <= at`. Historical queries strictly below the floor return the untouched feature-25 record. This is a real and non-obvious correctness detail and it is right. |
| **T10 wire binding is genuinely tamper-evident** | `committeeEpoch` is folded into the signed bytes (`HotStuffQuorum.voteMessage`, `HotStuffQuorum.scala:40-41`), so relabeling a QC's epoch invalidates the aggregate. `HotStuffCrossEpochForkSpecification`'s test 3 proves this directly, and test 6 proves engine-level rejection end-to-end. The design choice to reuse `GenerationPeriod.index` rather than invent a committee hash is sound and avoids a new consensus concept. |
| **T10 liveness root-cause fix is correct** | Deriving the *signed* epoch from the vote's **target height** (`committeeEpochOf`, `HotStuffCoordinator.scala:261-281`) rather than the signer's live tip is the right fix: `generationPeriodOf(h)` for fixed `h` is time-invariant, so honest replicas straddling a period boundary compute identical epochs. The separation from `committeeEpochProvider` (used only for inbound gating) is deliberate, documented, and correctly maintained at every call site I traced. |
| **Vote-pool all-snapshots quorum gate** | `HotStuffVotePool.onVote`'s "quorum under EVERY observed committee snapshot" rule (`HotStuffVotePool.scala:154`) is monotonically safe in both the shrink and grow directions, and the fail-closed `MaxSeenCommitteesPerTarget` cap correctly refuses to evict a checked snapshot. I attempted to construct a shrink-then-grow sequence that slips a sub-quorum signer set through and could not. |
| **Watchdog constructor capability narrowing** | The `committeeNonEmpty: () => Boolean` narrowing (vs. `() => GeneratorSet`) is a real, load-bearing improvement, not cosmetic: the production `committee` closure *does* transitively capture `blockchainUpdater`, and handing over only the `.nonEmpty` projection means that reference genuinely never enters the watchdog. `Application.scala:353-357` implements it as documented. See F-4 for what this does and does not prove. |
| **Watchdog backoff / hard cap** | The exponential backoff plus `MaxConsecutiveRecoveries` suspension (`HotStuffWatchdog.scala:207-232`) correctly bounds an unfixable-wedge reset loop, and correctly resets to a clean slate on genuine progress rather than permanently exhausting the budget. The 5-attempt sizing lands past the 30m alert window, which is the right handoff point. Arithmetic checked: 60+120+240+480+960 = 1920 ticks × 1200ms = 38.4 min. |
| **Coordinator self-verify-before-broadcast** | `applyQC(qc, effects.broadcast(qc))` (`HotStuffCoordinator.scala:333`) passes the broadcast as a by-name `onAccepted` that runs only after this node's own re-verification against a freshly-re-read committee. A node cannot broadcast a QC it then locally rejects. Correct, and a real race that a naive implementation would have. |

---

## Findings

Severity key: **Critical** (exploitable safety violation) · **High** (safety violation under a stated,
reachable precondition, or a broken security claim) · **Medium** (liveness failure that self-sustains,
or a materially misleading invariant) · **Low** · **Info**.

---

### F-1 — **[HIGH under `authoritative = true`; Info under the default]** The "authoritative finalized height" the HotStuff boundary raises **does not actually prevent a reorg below it**, and a rollback silently caps it back down.

This is the most important finding in this audit, and it is a finding about the boundary the task
brief called "the one place HotStuff is allowed to touch real state." The boundary's *own* logic is
correct (see Verified table). The problem is what the value it writes is worth.

I traced every rollback entry point. The **only** depth guard on rollback is `safeRollbackHeight`
(`= height - dbSettings.maxRollbackDepth`), which is computed in `RocksDBWriter.scala:538-542` and is
**completely independent of `finalizedHeight`**:

- `BlockchainUpdaterImpl.scala:527-531` — `removeAfter`'s entire depth validation is
  `Height(height) >= rocksdb.safeRollbackHeight`. `finalizedHeight` is never read in `removeAfter`.
- `Caches.scala:539-544` — `rollbackTo` repeats the same `safeRollbackHeight` check. No
  `finalizedHeight`.
- `ExtensionAppender.scala:35, 50-55` — fork choice is **pure score comparison**
  (`extension.remoteScore <= blockchainUpdater.score`), then
  `if (commonBlockHeight < initialHeight) blockchainUpdater.removeAfter(lastCommonBlockId)`. The
  common-ancestor height is **never** compared against `finalizedHeight`.
- Other `removeAfter` callers, none finality-guarded: `Application.scala:537` (`/debug/rollback`),
  `Importer.scala:133`, `BlockChallenger.scala:89`.

Worse than "does not prevent": rollback **actively lowers** the HotStuff floor.
`RocksDBWriter.scala:1230-1240` caps it down to the rollback target:

```scala
val currentFloor = rw.get(Keys.hotStuffAuthoritativeFloor)
if (currentFloor.exists(_.toInt > targetHeight.toInt)) {
  rw.put(Keys.hotStuffAuthoritativeFloor, Some(targetHeight))
}
```

That cap is *individually* correct — leaving the floor pointing at a block that no longer exists
would be worse. But taken together with the absence of any rollback refusal, it means the sequence
"HotStuff certifies H → floor raised to H → a higher-score extension arrives with a common ancestor
below H → reorg succeeds → floor silently capped down" is a fully supported, unlogged-as-a-violation
code path. **A block that HotStuff declared BFT-final can be reorged away, and the node will not
complain.**

The only behavioral consumers of the raised value are:
1. `Blockchain.lastBlockIds` (`Blockchain.scala:133-134`) — shortens the id list offered in
   `GetSignatures`, making a deep fork harder to *negotiate*. This is a probabilistic damper, not a
   guard.
2. `BlockEndorser.scala:99, 126-130` — suppresses feature-25 endorsement voting/rebroadcast below the
   floor.

Everything else is REST exposure (`CommonBlocksApi.scala:76-83`, `BlocksApiRoute.scala:67-79`) or
persistence bookkeeping.

Consumer (2) is the one that concerns me most, and it is a **second-order hazard specific to
`authoritative = true`**: raising the HotStuff floor causes feature-25's own endorser to stop voting
at heights below the floor. If HotStuff raised the floor on a branch that subsequently loses a reorg,
T2 has suppressed T0 endorsement activity for a range of heights on the winning branch. T0 is the
authoritative mechanism this whole design is supposed to leave untouched. I could not construct a
concrete finalization-divergence from this in static reading, but I also could not rule it out, and
the interaction is not documented or tested anywhere I found.

**Why this is only Info at the shipped default:** with `authoritative = false`,
`raiseHotStuffFinalizedHeight` is unreachable (both by wiring and by the in-method flag re-check),
`Keys.hotStuffAuthoritativeFloor` stays `None` forever, and every clause above is inert. The finding
is entirely about the testnet opt-in and about what would happen if that opt-in were ever promoted.

**Recommendation.** Do not treat `authoritative = true` as "T2 provides finality." It provides a
*reported* finality number plus an endorsement-suppression side effect, with no reorg enforcement
behind it. Either (a) add an explicit rollback refusal when the target is below the HotStuff floor —
which is a real consensus change needing its own design and audit, since it can wedge a node that
finalized a minority branch — or (b) document prominently, at `HotStuffSettings.authoritative` and in
`hotstuff-audit-readiness.md`, that the floor is advisory. Today the naming
(`authoritative`, `raiseHotStuffFinalizedHeight`) strongly implies enforcement that does not exist.

---

### F-2 — **[HIGH]** `resetLocalSafetyState()` clears `lastVotedView`, which is the *only* thing preventing this replica from voting twice in the same view. Nothing else in the watchdog's recovery path re-establishes that bound.

This is hunt item #2, and I do not believe the prior two reviews' conclusion covers it, because those
reviews asked "can the watchdog touch `finalizedHeight`?" (answer: no, correctly — see F-4) rather
than "can the watchdog's recovery action cause a safety-rule violation?"

`HotStuffCoordinator.resetLocalSafetyState()` (`HotStuffCoordinator.scala:418-423`) does:

```scala
engine = engine.copy(safety = SafetyState())
```

`SafetyState()` is `(prepareQC = None, lockedQC = None, lastVotedView = -1)`. All three protections
are dropped simultaneously. Now trace `safeToVote` (`HotStuffSafety.scala:35-66`):

```scala
proposal.view > state.lastVotedView && { state.lockedQC match { case None => true; ... } }
```

With `lastVotedView = -1` and `lockedQC = None`, **every** proposal for any view ≥ 0 is admitted.

The concrete interleaving — "the watchdog fires WHILE a real QC is in flight," exactly as the brief
asks:

1. Replica R is at view `v`. It receives proposal P₁ for block A and votes PREPARE. Now
   `lastVotedView = v`, and `voted` contains `(v, PREPARE, A)`.
2. R's PREPARE votes are in flight; no QC has come back yet, so `onAction` has not fired
   `recordProgress()`. If this has been the situation for `stallThreshold` ticks, the watchdog fires
   on this tick — **while R's own votes for view `v` are genuinely in flight**.
3. `resetLocalSafetyState()` → `lastVotedView = -1`, `lockedQC = None`.
4. A proposal P₂ for a **different** block B, still at view `v`, arrives (a Byzantine leader, or an
   honest re-proposal after a reorg changed what `blockSource` returns).
5. `safeToVote`: `v > -1` ✓, `lockedQC = None` → **true**. R votes PREPARE for B in view `v`.
6. The per-target `voted` guard does **not** stop this: `voted` is keyed
   `(view, phase, blockId)` (`HotStuffCoordinator.scala:213`), and `(v, PREPARE, B) ∉ voted`. It
   prevents re-voting the *same* target, never a *conflicting* one.

R has now signed two PREPARE votes for different blocks at the same `(view, phase)`. That is exactly
the condition `HotStuffSafety.equivocators` is written to detect — from an honest, non-Byzantine node,
caused by its own recovery mechanism.

**What limits this in production (and why it is High, not Critical):** the `proposalValid` guard
(`Application.scala:284-285`) requires the proposed blockId to be on this node's own canonical chain
at its own height. Two *conflicting* blocks at the same height cannot both satisfy that
simultaneously. So step 4 requires either (a) a reorg between steps 1 and 4 changing which block is
canonical at that height, or (b) P₂ naming a block at a **different** height that is also on R's
chain — which is entirely permitted, because `proposalValid` is deliberately view-agnostic
(documented at `HotStuffCoordinator.scala:118-131`) and `settledDepth` means several recent settled
heights are all legitimately chain-resident. Path (b) requires no reorg and no Byzantine block
fabrication — only a leader proposing a different (real, canonical) height under the same view number,
which the pacemaker's view/height decoupling makes ordinary.

Path (b) produces two votes at the same `(voter, view, phase)` for different blockIds — a genuine
equivocation signature — even though both blocks are real. Whether that can be *converted into a
fork* depends on whether two conflicting QCs can form; I could not construct that end-to-end
statically, because the two votes carry different `blockHeight` values and `formQC`'s `sameTarget`
check requires identical heights, so the two targets accumulate in separate buckets and neither
inherits the other's stake. **That is the reason I am rating this High rather than Critical.** But it
is a defense-by-accident: the fork is blocked by `formQC`'s height check, not by any rule that
intends to prevent double-voting, and the double-signature is real and on the wire regardless.

**Not covered by any existing test.** `HotStuffWatchdogDstReproductionSpecification` fires the
watchdog only while node 0 is **fully partitioned** from the rest of the committee
(`harness.partition(Set(0), Set(1,2,3))`, line ~144), so no QC and no competing proposal can possibly
arrive during or after the reset within the test window. The DST harness additionally uses
`extendsBranch = (_, _) => true` (`DstHarness.scala:82`) and the default `proposalValid = _ => true`,
so it is *structurally incapable* of exercising this class of bug. `SafetyInvariants.checkAll` only
checks committed-height fork/regression — it never inspects votes, so it would not detect
equivocation even if a scenario produced it.

**Recommendation.**
1. Make the reset **preserve `lastVotedView`**. Clearing `lockedQC`/`prepareQC` is what the manual
   `rm locked-qc.dat` recovery actually reproduces; `lastVotedView` is *not* persisted to disk, has no
   equivalent in the manual procedure, and dropping it buys nothing for recovery while removing the
   only anti-double-vote bound. This looks like an over-broad reset rather than a deliberate choice —
   `resetLocalSafetyState`'s own doc (`HotStuffCoordinator.scala:58-71`) describes the goal as
   clearing "`SafetyState.lockedQC`/`prepareQC`" and does not mention `lastVotedView` at all, yet the
   implementation clears all three via `SafetyState()`.
2. Add a DST scenario that fires the watchdog with traffic genuinely in flight (not partitioned), with
   a realistic `proposalValid`, and add an equivocation check to `SafetyInvariants` that records every
   vote and asserts `HotStuffSafety.equivocators` stays empty across the whole run.

---

### F-3 — **[MEDIUM]** `HotStuffSafety.equivocators` is dead code. Nothing in production ever calls it.

> **STATUS (2026-09-02): FIXED.** `HotStuffCoordinator.Enabled` now calls `equivocators` from its own
> vote pool and retains verified conflicts as `HotStuffEquivocationProof`s (`onEquivocation` fires
> unconditionally); the miner folds them into a block's `FinalizationVoting` when `slashing-enabled` is
> set, and every receiving node independently re-verifies and unions the offender into
> `conflictGenerators` — unconditionally from genesis (the on-chain activation gate this originally
> shipped behind, feature 29 `HotStuffEquivocationEvidence`, was deleted 2026-09-02 once it was
> confirmed no chain in this repo's history had ever activated it). Proved end-to-end, including the
> wire hop, by `HotStuffEquivocationEvidenceE2ESpecification`, `HotStuffEquivocationDetectionSpecification`,
> `HotStuffEquivocationProofSpecification`, and `HotStuffEquivocationValidationSpecification` (all now
> present in `node/tests/src/test/`) — see `docs/hotstuff-audit-readiness.md` §7 item 3 for the current
> production posture (`slashing-enabled` itself still defaults off pending live testnet exercise).

The finding below is preserved as originally written, since the reasoning that motivated wiring
`equivocators` in remains good context for why this mattered:

```
$ grep -rn "equivocators" node/src/main/scala/
node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffSafety.scala:96:  def equivocators(...)
```

One hit: the definition. No call site in `Application.scala`, `HotStuffCoordinator`,
`HotStuffEngine`, `HotStuffVotePool`, or anywhere else in main source. There is also **no**
`HotStuffEquivocationWiringSpecification` or `HotStuffEquivocationEvidenceSpecification` in
`node/tests/src/test/` — those names appear only as stale `target/test-reports/` XML from an earlier
tree, which is itself misleading: a casual check of the test-report directory suggests equivocation
wiring is tested when the specs no longer exist in source.

The 2026-07 review listed this as finding #5, `[INFO] Equivocation → slashing integration`, with the
note that wiring it into feature-25's `conflictGenerators` exclusion "is engine work (3c/4)". The
engine, coordinator, watchdog and T10 fix have all since landed. This did not. I am escalating it from
Info to **Medium** because the intervening work has made it materially more important, not less:

- F-2 shows the node's **own recovery mechanism** can now produce a double-vote. A detector that is
  never called cannot notice.
- `HotStuffCrossEpochForkSpecification`'s test 2 explicitly documents that `equivocators` is blind to
  the cross-epoch hazard — but that framing implicitly assumes it is at least catching the
  *single-voter* case it was written for. It is not catching anything, because it never runs.
- There is consequently **no Byzantine-signer detection of any kind** active in the T2 path. The
  security model rests entirely on the honest-≥2/3-stake assumption with zero detection or
  attribution if that assumption is violated. An external auditor would flag the absence of any
  accountability mechanism in a BFT protocol as a first-order gap, independent of whether a specific
  exploit is demonstrated.

**Recommendation.** Either wire `equivocators` into the vote-ingress path (log + metric at minimum;
`conflictGenerators` exclusion as the real goal) or delete it and state plainly in
`hotstuff-audit-readiness.md` that T2 ships with no equivocation detection. The current state — a
detector that exists, is documented as the codebase's Byzantine detector, and is never invoked —
is the worst of the three options, because it makes the security posture look better than it is.

---

### F-4 — **[INFO — re-derived independently; prior conclusion CONFIRMED, but its scope is narrower than it reads]** The watchdog cannot touch `finalizedHeight` directly. It can influence what commits, and therefore what gets raised.

I re-derived this from scratch rather than trusting the prior verifications, as instructed.
**The structural claim holds.**

`HotStuffWatchdog`'s constructor (`HotStuffWatchdog.scala:136-143`) accepts exactly:
`() => Boolean`, `Path`, `() => Unit`, `Path => Unit`, `Int`, `Int`. The entire capability surface is
`Boolean`, `Unit`, `Path`, `Int`. No `BlockchainUpdaterImpl` reference can be threaded through any of
them, and the production wiring (`Application.scala:353-357`) genuinely passes only
`() => committee().nonEmpty`, not the `committee` closure itself. The only actions `fireRecovery`
takes (`HotStuffWatchdog.scala:236-246`) are `clearLock(lockPath)` and `resetInMemoryState()`; the
latter is bound to `hsCoordinatorRef.resetLocalSafetyState()`, which mutates only `engine`,
`lastReproposedBlockId`, `reproposeAttempts` — all private `var`s of the coordinator.
`HotStuffWatchdogFinalizedHeightIsolationSpecification` proves this two ways (a never-invoked-mutator
canary, and a comment-stripped source grep for forbidden identifiers), and both are well-constructed —
in particular the comment-stripping is necessary and correctly done, and the CWD-independent source
resolution is a real robustness fix rather than a papered-over path bug.

**However, the claim is narrower than its framing suggests, and I want that on the record.** The
property proven is *"the watchdog holds no reference through which it could write `finalizedHeight`."*
That is genuine and worth having. The property a reader may take away — *"the watchdog cannot affect
finalized state"* — is **false**. The watchdog resets safety state; safety state governs voting;
voting governs which QCs form; a COMMIT QC calls `effects.onCommit`; and under
`authoritative = true`, `onCommit` calls `raiseFinalizedHeight` (`NodeHotStuffEffects.scala:63-80`).
The influence path is indirect but complete. F-2 is precisely an instance of it.

The isolation spec's own header calls itself "THE MOST IMPORTANT TEST IN TASK 4." It is a good test of
what it tests. It is not a safety proof for the watchdog, and the surrounding documentation
(`HotStuffWatchdog.scala:44-59`, "SAFETY BY CONSTRUCTION — the hard, non-negotiable constraint") reads
as though it were one. **Recommendation:** amend that scaladoc to say the constraint is *no direct
reference*, and cross-reference F-2 for the indirect path.

---

### F-5 — **[MEDIUM]** The watchdog's stall detector counts *any* accepted QC as progress, so the exact live incident it was built for — frozen `hotStuffFinalizedHeight` with views still advancing — may not trigger it.

The progress signal is (`Application.scala:365-368`):

```scala
val hsOnAction: HotStuffAction => Unit = {
  case _: HotStuffAction.Rejected => ()
  case _                          => hsWatchdog.recordProgress()
}
```

Excluding `Rejected` was a correctly-identified review fix. But the surviving cases are `Committed`
**and** `EnteredView`, and `applyQC` reports **every** action from any QC that passed verification
(`HotStuffCoordinator.scala:368-374`). A replica that keeps forming or receiving valid **PREPARE**
QCs — advancing the view every round — but never reaches a COMMIT QC will call `recordProgress()` on
essentially every tick. `consecutiveStalledTicks` never accumulates. **The watchdog never fires.**

That is a plausible description of the live incident in scope for this audit: T2
`hotStuffFinalizedHeight` frozen for 2h19m / 604+ blocks while T0 continued normally. "Frozen
finalized height" means no COMMIT QCs. It does **not** imply no PREPARE/PRE_COMMIT QCs — and the
phase-progression path (`HotStuffCoordinator.scala:376-383`) keeps casting the next phase's votes on
every verified QC, so partial rounds recurring indefinitely is exactly the shape of a stall that
leaves views advancing.

I want to be precise about what I can and cannot claim: **I have not confirmed which QC phases were
forming during that incident.** I do not have the node logs. What I can state from the code is that
the watchdog's detector is *insensitive to a commit-only stall*, and that a commit-only stall is
consistent with the reported symptom. Determining whether the actual incident had this shape requires
the `[HotStuff] onQC` DEBUG lines from the affected nodes for that window — **UNVERIFIED — needs the
live logs.**

The class doc claims the signature is "zero `Committed`/accepted-QC `EnteredView` action for N
consecutive ticks" (`HotStuffWatchdog.scala:20-21`), which is accurate to the implementation. My
finding is that this signature is the wrong one for the incident, not that the implementation
mismatches its doc.

**Recommendation.** Track a second, commit-specific staleness counter: ticks since the last
`Committed` action, independent of `EnteredView`. A stall in which views advance but nothing commits is
the *more* dangerous liveness failure (it looks healthy to every view-based metric) and is currently
invisible to the watchdog. Note this must be paired with F-2's fix — making the watchdog fire in
*more* situations without narrowing the reset is a net safety regression.

---

### F-6 — **[MEDIUM]** T10's one-step epoch acceptance window can become a self-sealing liveness trap during exactly the kind of long stall that motivated the watchdog, and the watchdog's recovery cannot fix it.

> **STATUS (2026-09-02): UNVERIFIED → REPRODUCED AND FIXED.** Filed here as unverified (see "What I
> could not verify" below: not reproducible in the DST harness as it then stood). The harness change
> this finding asked for was made (per-node simulated tip + epoch belief), the trap was reproduced
> against it, and a height-lag re-anchor fix landed on `feat/hotstuff-lag-reanchor`. Design:
> `docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md`. Tests:
> `DstStaleTargetSelfSealScenarioSpecification` (paired RED/GREEN — the RED arm commits the stale
> target at height 50, the GREEN arm re-anchors and commits at 92; mutation-verified, and restaged
> after a review caught the first version's fixed arm passing with the fix neutralized) and
> `HotStuffLagReanchorSpecification` (predicate boundaries, the `settledDepth + 1` floor, guard
> placement vs. `lastVotedView`). `acceptableCommitteeEpoch`'s one-step window is deliberately
> UNCHANGED — the fix is a filter on target selection only. Full write-up:
> `docs/hotstuff-audit-readiness.md` §7 item 7. Not yet observed live on testnet; the new
> `hotstuff.stale-target-abandoned` / `hotstuff.stale-target-skipped-proposal` counters exist so a real
> occurrence becomes graphable.


The two epoch functions are deliberately different, and correctly so:
- **Signed** epoch: `committeeEpochOf(targetHeight)` — a pure function of the target's height
  (`Application.scala:262-263`).
- **Accepted** epoch: `acceptableCommitteeEpoch(qcEpoch, currentEpoch)` where `currentEpoch` is
  `committeeEpochProvider()` = the replica's **live tip's** generation period
  (`Application.scala:245`), accepting only `currentEpoch` or `currentEpoch - 1`
  (`HotStuffQuorum.scala:58-59`).

In the happy path, `settledDepth = 3` keeps the target within 3 blocks of the tip, so the two agree
except within 3 blocks of a period boundary — comfortably inside the one-step window. The narrow
window is correct and its narrowness is well-argued in `HotStuffQuorum.scala:46-56`.

But the coupling is *lag-sensitive*, and HotStuff's lag is unbounded. If T2 falls behind by more than
one full generation period (`generationPeriodLength` = 1000 or 3000 on testnet), then a QC for the
stale target height is signed under epoch `e`, while the replica's live tip believes `e + 2` or more.
`acceptableCommitteeEpoch(e, e+2)` = `false`. The replica rejects **its own honest, correctly-formed
QCs**, permanently, and can never catch up — because catching up requires committing the stale
heights, which requires accepting those QCs.

The 604-block observed stall is inside one 1000-block period, so this specific trap was probably not
the cause of the incident under review. But the incident demonstrates that multi-hour stalls happen,
and a stall of ~1000+ blocks at testnet block rates is not exotic.

Two aggravating interactions:
- **The watchdog cannot fix it.** `resetLocalSafetyState()` clears `lockedQC`/`prepareQC`/
  `lastVotedView`. It does not touch the epoch mismatch, which is a function of chain height, not
  local safety state. So the watchdog *would* correctly fire (all the rejected QCs produce `Rejected`
  actions, which correctly do not count as progress — F-5's blind spot does not apply here), reset
  five times with exponential backoff, achieve nothing, and suspend itself after 38.4 minutes. That
  is the designed graceful degradation working as intended, but it means the trap is
  unrecoverable-without-restart, and a restart does not help either since the mismatch is recomputed
  from chain state.
- **It is a recovery-hostile failure mode.** Once trapped, the replica's T2 is permanently dead for
  that run with no automated exit.

**Recommendation.** Bound the lag rather than the epoch window: if `tip - targetHeight` exceeds some
fraction of a generation period, skip forward — abandon the stale target and re-anchor on a fresh one
near the tip — rather than continuing to sign QCs under an epoch the replica will reject. Do **not**
simply widen `acceptableCommitteeEpoch`; the one-step narrowness is the T10 fix's actual safety
content and widening it re-opens the fork hazard. This is a real design question, not a one-line
change.

**UNVERIFIED — needs live testing.** I could not construct this in the DST harness because
`DstHarness.epochBelief` is a single shared `var` advanced explicitly by scenarios
(`DstHarness.scala:59, 156`); no scenario models a per-replica belief that drifts *ahead of* the
target height it is still voting on. A test for this needs the harness to derive `epochBelief` from a
simulated tip that can outrun the committed height.

---

### F-7 — **[LOW]** T10's remaining open item is still open, and the committee-data sweep does not close it.

`docs/hotstuff-audit-readiness.md:180` and `hotstuff-integration-design.md` §8 both record the same
open item: **no live multi-node Docker evidence of an actual committee-epoch transition.** All
existing Docker evidence (`FourNodeHotStuffTestSuite` et al.) ran with `committeeEpoch = 0`
throughout, because the provider only returns non-zero once a real generation-period rotation occurs.
I confirmed this is still the case — the epoch-transition coverage is unit tests
(`HotStuffCrossEpochForkSpecification`, `HotStuffCrossEpochLivenessSpecification`) plus one DST
scenario (`DstCommitteeEpochRotationScenarioSpecification`), all in-process.

Regarding integration with what has landed since T10:

- **With the committee-data sweep (§6 of `consensus-divergences-from-upstream.md`):** compatible, and
  the sweep's fix is load-bearing for T10. The HotStuff committee provider now reads
  `currentCommittedGeneratorSet` (`Application.scala:235`), reconstructed from the persisted
  period-keyed checkpoint, rather than the live `currentGeneratorSet` cache. Since
  `committeeEpochProvider` reads `currentGenerationPeriod` — also period-derived — the committee and
  its epoch label now come from **consistent, period-keyed sources**. Under the old live-cache
  provider they could have disagreed (a committee snapshot from one period labeled with another
  period's index), which would have made the epoch binding actively misleading rather than merely
  incomplete. I checked the sweep's classification of the 5 remaining live-cache consumers and agree
  with its verdict; none is on a HotStuff path.
- **With the watchdog:** the interaction is F-6. The watchdog's reset is orthogonal to cross-epoch
  state (which is derived from chain height, not `SafetyState`), so the reset cannot *corrupt*
  cross-epoch state — but it also cannot *repair* it, which is the finding.

I found no case where the watchdog's reset interacts badly with cross-epoch state in the corruption
sense the brief asked about. The T10 gating decision is recomputed fresh from `committeeEpochProvider()`
on every `refreshCommittee()` (`HotStuffCoordinator.scala:236-237`), which runs at the top of every
entry point, so there is no stale-epoch state for a reset to leave behind.

---

### F-8 — **[LOW]** Pacemaker view arithmetic is unbounded and can overflow; a QC's `view` and `blockHeight` are unvalidated on ingress.

Hunt item #3. `PacemakerState.view: Int`, and both advance rules are unguarded:

```scala
def onQC(qcView: Int, state: PacemakerState): PacemakerState =
  if (qcView >= state.view) PacemakerState(qcView + 1) else state
def onTimeout(state: PacemakerState): PacemakerState =
  PacemakerState(state.view + 1)
```

`qcView` comes directly off the wire (`QuorumCertificate.fromProtobuf`, `messages.scala:210-211`) with
no range check anywhere on the ingress path (`Application.scala:429-433` gossips and forwards
directly to `onQC`). A QC with `view = Int.MaxValue` sets the pacemaker to `Int.MinValue`.
`HotStuffPacemaker.leaderFor` uses `Math.floorMod`, which is correctly well-defined for negative
views — that is a deliberate and good defensive choice. But `safeToVote`'s `proposal.view >
state.lastVotedView` and `update`'s `qc.view > _.view` monotonicity checks all become nonsense across
the wrap, and `onQC`'s `qcView >= state.view` means the pacemaker can no longer be advanced by any
normal QC until it climbs all the way back.

Similarly `blockHeight: Height` is `Height(x.blockHeight)` from a raw signed int32 with no validation.
`HotStuffEngine.onQC`'s commit guard is `qc.blockHeight.toInt > advanced.committedHeight`
(`HotStuffEngine.scala:72`); a QC with `blockHeight = Int.MaxValue` sets `committedHeight` to
`Int.MaxValue` and **permanently wedges all future commits on that replica**, since no real height can
ever exceed it.

**Why Low, not High:** both require passing `verifyQC` — a valid aggregate BLS signature over the
canonical bytes from ≥2/3 of committed stake. This is not remotely reachable by an external attacker;
it requires a colluding supermajority, which already breaks every guarantee in the protocol. The
finding is that these are *unbounded sinks with no sanity floor*, so a colluding quorum (or a bug in a
future signing path, or a malformed-but-signed message from a buggy peer version) gets a
permanent, restart-surviving denial of service rather than a rejected message. Defense in depth, not
an exploit.

Note also that under `authoritative = true`, a QC with an absurd `blockHeight` reaching `onCommit`
would call `raiseHotStuffFinalizedHeight(id, Int.MaxValue)` — which is correctly refused by the
chain-agreement check at `BlockchainUpdaterImpl.scala:745-747`, since no such block exists locally.
That boundary holds. Good.

**Recommendation.** Reject on ingress: `qc.view >= 0`, `qc.blockHeight > 0`, and
`qc.blockHeight <= blockchainUpdater.height + someSlack`. Cheap, and it converts a permanent wedge into
a logged rejection.

---

### F-9 — **[LOW]** The `voted` set grows without bound.

`private var voted = Set.empty[(Int, HotStuffPhase, BlockId)]` (`HotStuffCoordinator.scala:213`) is
added to in `castVotes` and **never** pruned. `prunePool()` reclaims `VotePool.pending` and
`seenCommittees` by view (`HotStuffCoordinator.scala:247-248`), and the bounded-growth work
(`HotStuffVotePoolBoundedGrowthSpecification`, `MaxSeenCommitteesPerTarget`) covered the pool
thoroughly — but `voted` was not included in that sweep.

Each entry is a boxed `Int`, an enum ref, and a 32-byte `ByteStr`. At one view per round and up to 3
phases per view, a 1200ms round timeout gives roughly 2.5 entries/sec ⇒ ~216k entries/day, growing
monotonically for the process lifetime. Not an urgent leak, and node processes restart, but it is the
same class of unbounded-growth issue the pool work deliberately went after, and it was missed.

**Recommendation.** Prune `voted` alongside `prunePool()`, using the same `view >= pacemaker.view - 1`
retention margin. Care is needed: `voted` is a real anti-vote-storm guard, and pruning it too
aggressively re-admits duplicate votes for a still-live target — which is precisely why the pool keeps
a one-view margin. It must not be pruned to a tighter bound than the pool.

---

### F-10 — **[INFO]** DST self-resumption is 49/100; the watchdog does not raise this, and the number is not what the threshold is sized against.

`DstEmptyCommitteeSourceScenarioSpecification` found that after a real committee is restored,
unaided self-resumption converges within 10 simulated rounds in only ~49/100 seeds. The watchdog's
class doc handles this carefully and, to its credit, contains a **self-correction** noting that the
original "6x margin" justification compared simulated rounds to wall-clock seconds and was invalid
(`HotStuffWatchdog.scala:69-88`). The surviving argument — that the empty-committee hard reset
guarantees self-resumption always gets a full, fresh 72-second window — is sound and directly tested.

Two observations an outside auditor would make:

1. The 49% figure describes the **empty-committee-restored** scenario, which the watchdog explicitly
   does **not** act on. So the watchdog does not improve that number at all; it deliberately stays
   silent throughout. The doc says this, but the framing across the codebase occasionally reads as
   though the watchdog addresses the 49% flakiness. It does not, by design.
2. **Nobody has characterized *why* it is 49%.** The scenario spec attributes the difference to
   "delivery-delay timing jitter" with "nothing structurally different between seeds." That is an
   observation, not a root cause. A coin-flip outcome in a deterministic protocol under benign
   conditions is exactly the kind of thing that turns out to be a real bug when someone finally
   bisects a failing seed. I did not do that bisection — it needs a focused DST investigation —
   but I would not accept "timing jitter" as a closed explanation, and neither should the external
   audit. **UNVERIFIED — needs a per-seed DST investigation.**

---

## Coverage assessment vs. the 2026-07 residual-risk statement

The prior doc stated: *"the dangerous surface moves to the engine (3c/4): phase progression, QC
verification at call sites, pacemaker/timeout, and live block-production integration — none of which
unit tests fully cover."* Verifying whether that is still true, as instructed:

| Surface | 2026-07 status | 2026-08-31 status |
|---|---|---|
| Phase progression | uncovered | **Covered** — `HotStuffEngineSpecification`, `HotStuffSimulationSpecification`, DST scenarios. |
| QC verification at call sites | uncovered | **Covered** — traced exhaustively in this audit (see below); `HotStuffCrossEpochForkSpecification` test 6 covers the engine gate end-to-end. |
| Pacemaker / view-change | uncovered | **Mostly covered** — `HotStuffPacemakerSpecification`, `HotStuffViewChangeSpecification` (489 lines, genuinely adversarial: replay-under-inflated-view, fabricated `initialLockedQC`, bounded re-propose). Gaps: no overflow test (F-8), no watchdog-during-view-change test (F-2). |
| Live block-production integration | uncovered | **Partially** — `node-it` `FourNodeHotStuffTestSuite` / `FourNodeHotStuffAuthoritativeTestSuite` / `DegradedLinkHotStuffTestSuite` exist. Still no committee-epoch-transition Docker evidence (F-7). |
| Watchdog (new) | n/a | **Well covered for what it tests** — 5 dedicated specs incl. backoff, rejected-stream, finalizedHeight isolation. Gap: no in-flight-traffic reset scenario (F-2), no commit-specific stall (F-5). |

Coverage has improved very substantially — from 15 unit tests on two pure modules to ~24 HotStuff
specs plus a DST harness plus 4 `node-it` suites. The residual-risk statement is **largely
discharged**. The remaining gaps are specific and named above rather than the blanket "none of which
unit tests fully cover."

### Hunt item #1 — every `raiseHotStuffFinalizedHeight` call site, traced backward to QC verification

Exhaustive. There is exactly **one** production path:

```
HotStuffEngine.onQC          — acceptableCommitteeEpoch gate, then verifyQC; Rejected returns state UNCHANGED
  → HotStuffAction.Committed — emitted only via HotStuffSafety.committedBlock (COMMIT phase only)
                               AND qc.blockHeight > committedHeight        (HotStuffEngine.scala:71-74)
  → HotStuffCoordinator.applyQC — dispatches Committed only from `actions`, which for a rejected QC
                               contains solely Rejected                     (HotStuffCoordinator.scala:368-374)
  → NodeHotStuffEffects.onCommit — gated on `authoritative`                 (NodeHotStuffEffects.scala:67)
  → BlockchainUpdaterImpl.raiseHotStuffFinalizedHeight
       - re-checks hotStuffSettings.authoritative                           (:737-744)
       - requires this.blockId(certifiedHeight) == certifiedBlockId         (:745-747)
  → Caches.raiseHotStuffFinalizedHeight — monotonic raise only              (Caches.scala:87-98)
```

Both QC entry points into `applyQC` are covered: `onQC` (wire-received) and `onVote` (self-formed,
which re-verifies via `applyQC` before broadcasting). `HotStuffEngine.onQC` returns
`(state, Seq(Rejected(...)))` with **state unchanged** on any rejection, so no rejected QC can leave
residue that a later call converts into a commit. **I found no path to `raiseHotStuffFinalizedHeight`
that bypasses `verifyQC`.** The `certifiedBlockId`/`certifiedHeight` pair reaching the boundary always
originates from a QC that passed both the epoch gate and full cryptographic verification.

The caveat is F-1: this correctly-verified, correctly-guarded value is then written to a floor that
does not enforce anything.

---

## Residual risk / gate

**Do not enable `dcc.hotstuff.authoritative = true` on mainnet.** That was already the stated
position; F-1 gives it a sharper reason than "pending audit" — the flag's name promises enforcement
the implementation does not provide, and its one real side effect (suppressing feature-25 endorsement
below the floor) touches the mechanism T2 is supposed to leave alone.

**Blocking for mainnet, in priority order:**
1. **F-2** — narrow `resetLocalSafetyState` to preserve `lastVotedView`, and add the in-flight-traffic
   DST scenario plus an equivocation invariant. This is a self-inflicted double-vote path in a BFT
   protocol; it should not survive to an external audit.
2. **F-1** — decide and document whether the HotStuff floor is advisory or enforcing, and align the
   naming with the answer. If enforcing is intended, that is a separate consensus change requiring its
   own design review.
3. **F-3** — wire or delete `equivocators`. Ship no Byzantine detector, or ship a real one; do not ship
   an uncalled one.
4. **F-6** — bound the HotStuff lag so the epoch window cannot self-seal. **DONE 2026-09-02** (see F-6's STATUS note).
5. **F-5** — add commit-specific stall detection (only after F-2 is fixed).

**Explicitly could NOT verify here** — stated plainly rather than papered over, per this project's
standard:

- **The live incident's actual QC-phase pattern (F-5).** I do not have the node logs for the 2h19m
  window. My claim is that the detector is insensitive to a commit-only stall and that this is
  *consistent* with the symptom — not that it *was* the cause. Getting the `[HotStuff] onQC` DEBUG
  lines for that window would settle it in minutes.
- **Whether F-2 can be driven to an actual fork.** I showed the double-vote; I could not construct
  two conflicting QCs, because `formQC`'s identical-`blockHeight` requirement separates the buckets.
  I do not consider that a safety *proof* — it is an accidental barrier, and I did not exhaustively
  search for a same-height variant. A dedicated adversarial DST scenario is needed.
- **F-6's self-sealing epoch trap.** Not reproducible in the DST harness as it stood at the time of
  this audit (single shared `epochBelief` var). Needs a harness change or a long-running testnet
  observation across a generation period boundary while T2 is lagging.
  **RESOLVED 2026-09-02 (harness half):** the harness change was made (per-node simulated tip + epoch
  belief), the trap reproduced, and the fix landed — see this finding's STATUS note above. The
  *testnet-observation* half remains open: the trap has never been seen on a live cluster, only in
  simulation.
- **F-10's 49% root cause.** Needs per-seed DST bisection, not static reading.
- **Anything requiring live multi-node behavior:** real network partitions, real committee rotation on
  a running cluster (F-7's open T10 item), real BLS key rotation, and real timing under load. Nothing
  in this audit substitutes for that.

**Standing recommendation.** This audit found one High-severity safety finding (F-2) and one
High-under-opt-in finding (F-1) in surface area that had already been through two rounds of internal
review. That is the expected yield of an adversarial pass, and it is also the argument for the
external audit that was skipped: this document was produced by a reviewer who cannot run the network,
cannot bisect the DST seeds, and cannot read the incident logs. Three of the five blocking items above
need exactly those capabilities to close.
