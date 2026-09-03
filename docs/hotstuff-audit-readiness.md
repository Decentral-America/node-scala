# T2 HotStuff — External Audit Readiness Package

> **Purpose.** Single entry point for an external consensus auditor. It states scope, the trust model,
> the threat scenarios to probe, the code surface, and the evidence already produced — so the audit can
> start from a known baseline instead of rediscovering it.
>
> **Status of the thing being audited (updated 2026-08-04):** ✅ **Ready for audit against `main` @
> `9c49632398`** (bumped 2026-08-04 from the prior baseline `304bd0e408` — scope has grown since: SC-695
> (an unrelated RIDE feature, feature id 30, dormant) and the T10 cross-committee-epoch-fork fix + its
> 2026-08-04 liveness follow-up fix are now also on `main` and are part of the HotStuff-relevant surface,
> so the frozen baseline commit is updated to match). The pacemaker/single-active-view rework (Task 8) that
> this banner previously marked as pending — fixing the `view=block-height` shell model's conflation of
> view and block height, plus vote-pool bounding, `lockedQC` persistence across restarts, and the
> re-propose-locked-branch leader-timeout optimization — is complete and merged, as is T10 (see §4/§7 T10
> entry). The code described in this package (scope, threat model, evidence index below) is frozen at
> `9c49632398` for the external auditor to review; if HotStuff-area commits land on `main` after this date,
> this banner's commit reference needs bumping again before audit kickoff. See
> [`hotstuff-step5-findings-and-rework.md`](./hotstuff-step5-findings-and-rework.md) for the history of what
> was found and fixed to get here.
>
> **Separately — not covered by this audit-readiness status:** by explicit human decision, ahead of this
> external audit, a new `dcc.hotstuff.authoritative` opt-in flag was deployed live on testnet only
> (`../infra/node-config/testnet/dcc.conf`, `../infra/clusters/testnet/apps/nodes.yaml`:
> `hotstuff.authoritative = true`, image `sha-9c49632`). `GET /blocks/height/finalized` is confirmed
> genuinely advancing on live testnet via this mechanism (verified 2026-08-04: chain height `107779`,
> finalized height `107697`). That is an already-made operational decision for testnet, not something
> this audit retroactively covers — the audit is exactly what is required before `authoritative` could ever
> be considered for mainnet (§1, §7 item 4, §8 checklist still gate that). Gated behind
> `dcc.hotstuff.enabled`/`dcc.hotstuff.authoritative` (both default `false` outside testnet) — no behaviour
> change on mainnet today.
>
> **F-1, ADVISORY NOT ENFORCING (2026-08-31 adversarial audit, `docs/hotstuff-bft-audit-2026-08-31.md`
> §F-1) — read before treating `authoritative = true` as "T2 provides finality":** the "authoritative"
> finalized height this flag lets HotStuff raise does **not** actually prevent a reorg below it. No
> rollback path anywhere in the codebase (`removeAfter`/`rollbackTo`/`ExtensionAppender`'s fork choice)
> checks `finalizedHeight` before rolling back; a rollback below the HotStuff floor silently *caps the
> floor back down* rather than being refused. The floor's only real behavioural effects are shortening
> `GetSignatures`' offered id list (a probabilistic damper on negotiating a deep fork, not a guard) and
> suppressing feature-25's own `BlockEndorser` voting/rebroadcast below the floor — itself a second-order
> hazard, since a HotStuff-raised floor on a branch that later loses a reorg means feature-25 endorsement
> was suppressed on the range of heights on the branch that actually won. Per the audit's recommendation
> option (b): this is being documented plainly rather than fixed with an enforcing rollback refusal,
> which would be its own consensus change requiring its own design and audit. Do not read "testnet has
> `authoritative = true` live and finality is advancing" (above) as "T2 enforces finality on testnet" —
> it reports a number and suppresses one downstream mechanism; it does not stop a reorg.

## 1. What it is, and the one property that bounds the whole audit
Basic 3-phase HotStuff (prepare → pre-commit → commit) over the **committed-generator committee**
(generators who submitted a `CommitToGenerationTransaction` with a PoP-verified BLS key). Quorum =
**≥2/3 of committed stake**.

**The bounding property: HotStuff commit is _observational_ at the shipped default.**
`NodeHotStuffEffects.onCommit` records a `hotStuffFinalizedHeight` (surfaced on `/node/status`) but, when
`dcc.hotstuff.authoritative = false` (mainnet default), **does not mutate** the authoritative finalized
height — feature-25 Deterministic Finality remains the sole source of finality in `BlockchainUpdaterImpl`.
Therefore, in that default posture, **a HotStuff bug cannot fork, halt, or roll back the chain**; the worst
case is a wrong/lagging observational number.
**Where `authoritative = true` is opted into (testnet), this bounding property is narrower than it reads:**
`onCommit` DOES then raise `finalizedHeight` — but per audit F-1 (see the banner above), what gets raised
is still advisory rather than enforcing (no rollback is refused on account of it), so the "cannot fork,
halt, or roll back the chain" claim continues to hold for a different reason on that path too — not
because HotStuff is inert, but because nothing downstream of the raise actually blocks a rollback. The
audit's severity ceiling is set by this distinction — until a future
change makes HotStuff *authoritative in the enforcing sense* (refuse a rollback below the floor, not
merely report a number), which is explicitly out of
scope here and must be re-audited when proposed.

## 2. Audit scope
**In scope (the dangerous surface is runtime, not the pure modules):**
- Phase progression / 3-chain commit rule in `HotStuffEngine` (see finding #2 — primary target).
- QC formation & verification, BLS aggregate verification, rogue-key/PoP defense.
- Safety (lock/commit) + liveness (pacemaker/view-change) under crash, partition, and equivocation.
- The shell: message routing, signing, broadcast, and the view=height / leader=FairPoS-forger mapping.
- Cross-generation-period committee rotation and replay resistance.

**Out of scope:** feature-25 Deterministic Finality itself (already live, separately reviewed); making
HotStuff authoritative (not built); non-consensus node subsystems.

## 3. Trust model & assumptions
- Committee = committed generators; each BLS key has an **on-chain proof-of-possession** verified at
  `CommitToGenerationTransactionDiff.scala:22` (binds key to holder + period; duplicate keys rejected).
- ≥2/3 of committed **stake** is honest and live (standard BFT assumption).
- The pure core is deterministic, clock-free, and I/O-free (injected `extendsBranch`, injected effects).
- Network is partially synchronous; the pacemaker drives liveness across views on timeout.

## 4. Threat scenarios to probe (map each to a test / code path)
| # | Attack | Where it must be stopped | Current handling |
|---|--------|--------------------------|------------------|
| T1 | Rogue-key forgery of the fast-aggregate | on-chain PoP | verified `…Diff.scala:22`; ✅. **BLS domain separation & bound PoP (2026-09-02, unconditional — was gated feature 30 `BlsCryptoV2`, deleted 2026-09-02 under the "registry mirrors Waves" plan since no chain in this repo's history has ever activated it):** the PoP message binds `chainId ‖ sender` too (`CommitToGenerationTransaction.popMessage`'s layout), not just `endorserPk ‖ generationPeriodStart`, and is signed/verified under its own `_POP_` domain-separation tag (`BlsUtils.BlsPopDomainSeparationTagV2`) instead of a shared tag — closing audit M2 alongside H2. Every node runs this from genesis; there is no activation boundary. See `docs/hotstuff-bls-crypto-audit-2026-08-31.md` H2/M2 STATUS notes. |
| T2 | Forged / below-quorum QC | `HotStuffQuorum.verifyQC` | all-signers-∈-committee + 2/3-stake + agg verify; unit-tested |
| T3 | Unverified QC influencing safety/commit | `HotStuffEngine.onQC/onProposal` | `verifyQC` gate before `update` (`:44`,`:70`); finding #1 ✅ |
| T4 | Conflicting / out-of-chain COMMIT | `verifyQC` + honest-quorum voting/lock rules | verified COMMIT QC trusted by design (finding #2); forged/below-quorum rejected, same-height re-commit blocked (tested) — audit the safety proof under the honest-≥2/3 assumption |
| T5 | Equivocation (double-sign) | `HotStuffSafety.equivocators` | proof-carried, block-validated exclusion wired unconditionally (was gated feature 29 `HotStuffEquivocationEvidence`, deleted 2026-09-02 — every node validates/unions from genesis, no activation-height check); still gated by `slashing-enabled` (default off) on the miner-side fold only; live testnet exercise pending |
| T6 | Byzantine voter stalls QC formation | `HotStuffVotePool.onVote` | invalid votes dropped on ingress; finding #3 ✅ |
| T7 | Stale-justify / conflicting-branch vote | `HotStuffSafety.safeToVote` | canonical rule; adversarially unit-tested |
| T8 | Cross-period replay of votes/PoP | PoP binds period; canonical vote message | binds `generationPeriodStart`; framing note = finding #4 (**superseded**, see below). **BLS domain separation (2026-09-02, unconditional — was gated feature 30, since deleted):** cross-CHAIN and cross-SENDER replay are now closed too (PoP binds `chainId ‖ sender`, per T1 above), and "framing note = finding #4" is superseded by per-context DSTs — `_POP_`/`_ENDORSE_`/`_HSVOTE_` (`BlsUtils.Bls{Pop,Endorse,HsVote}DomainSeparationTagV2`) are the ONLY tags used in production, so a PoP/endorsement/vote signature from one context can no longer be transplanted into another even by encoding coincidence. Proved by the 3x3 cross-DST matrix in `BlsUtilsTest`. |
| T9 | Crashed leader / partition (liveness + safety) | pacemaker + safety | unit + `FourNodeHotStuffTestSuite`; **needs live soak** |
| T10 | Cross-committee-epoch fork — two disjoint committees (e.g. a full validator-set rotation between committed-generators periods) each independently form a valid, honestly-signed 2/3-stake QC for a *different* block at the identical (view, height), with zero shared signers | `HotStuffQuorum`/`HotStuffVotePool` — committee identity is now bound into the signed vote/QC bytes | **Narrowed, not fully closed (fork hazard fixed 2026-08-03; a related liveness gap in the same fix found and closed 2026-08-04).** A `committeeEpoch: Int = 0` field (schema 1.6.5, `dcc/block.proto` field 7 `committee_epoch`, reusing the existing `state.GenerationPeriod.index` rotation identifier — no new committee-hash concept) is folded into the canonical signed bytes in `HotStuffQuorum.voteMessage`, carried on the wire on `HotStuffVote`/`QuorumCertificate`, and gated by a new `HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch, currentEpoch)` transition rule (accepts the current epoch or the immediately-preceding one; rejects everything else) applied in `HotStuffEngine.onQC`/`onProposal` *before* cryptographic verification. `HotStuffCrossEpochForkSpecification` proves the fork-hazard fix: labeled votes from disjoint epochs can no longer be relabeled/merged into a cross-epoch QC, and `HotStuffEngine.onQC` demonstrably rejects a QC from an epoch outside the transition window end-to-end. Default `committeeEpoch = 0` is fully backward compatible — a pre-1.6.5 peer's messages simply omit the proto3 field and decode as `0`, matching every existing call site, so this closes cleanly with zero behaviour change for unlabeled traffic. **Adversarial review (2026-08-04) found the fork-hazard fix's OWN wiring introduced a distinct, previously-uncharacterized LIVENESS gap:** `committeeEpoch` was derived from the SIGNING replica's own live chain tip (`blockchainUpdater.currentGenerationPeriod`) rather than the vote's TARGET height, so two fully honest, synced replicas voting the identical `(view, phase, blockId, blockHeight)` target could sign DIFFERENT epochs if their local tip crossed a generation-period boundary at slightly different moments — ordinary propagation skew, not an attack — and `formQC`'s (correctly epoch-sensitive) `sameTarget` check then permanently stalled that target. Fixed by deriving `committeeEpoch` as a PURE function of the target height (`HotStuffCoordinator.Enabled`'s new `committeeEpochOf: Int => Int` parameter, `blockchain.generationPeriodOf(targetHeight).index` in `Application.scala`) at every vote-signing call site, so every honest replica now computes the identical epoch for the identical target regardless of its own local tip; `HotStuffVotePool.onVote`'s per-voter dedup was additionally made epoch-aware (`(voterIndex, committeeEpoch)`, not `voterIndex` alone) as defense-in-depth. See `HotStuffCrossEpochLivenessSpecification` for the reproduction and fix proof. All HotStuff-area unit/DST tests green (`node-tests/testOnly com.decentralchain.consensus.hotstuff.* …`). **Real Docker evidence obtained (2026-08-03, pre-dates the liveness fix above):** built the actual `node-it` Docker image off this
branch (`sbt node-it/docker`, `.m2`-resolved 1.6.5 schema) and ran `FourNodeHotStuffTestSuite`
against a real 4-node cluster — all 3 cases (plain finalization, crashed-generator recovery, network
partition) pass with the `committeeEpoch`-carrying wire format live end-to-end
(`node-tests`/`node-it` output archived at
`t10_docker_build.log`/`t10_nodeit_run.log` in the session scratchpad). This proves the wire change is
non-regressing on a real multi-node cluster (default `committeeEpoch = 0` throughout, since
`Application.scala`'s `committeeEpoch` provider only returns non-zero once a real generation-period
rotation occurs) — it does **not** yet prove an actual cross-epoch *transition* live (no scenario in
this run drove a real committed-generators rotation mid-test), and pre-dates the 2026-08-04 liveness
fix, so it does not by itself cover the boundary-skew scenario that fix closes. **Still open:** (1) no
Docker evidence of an actual committee-epoch *transition* (two different committees, live rotation
mid-cluster) exists yet — only unit/DST-level simulation (`DstCommitteeChangeScenarioSpecification`)
plus the non-regression 4-node run above; a dedicated `node-it` rotation scenario is still future work,
best done on CI/testnet given local `node-it` Docker's documented memory/flakiness constraints on this
laptop (see
`docs/hotstuff-HANDOFF.md`) for anything longer/heavier than the single run just completed. |
| T11 | Post-restart replay under a blank safety lock — a freshly-constructed `SafetyState` (`lockedQC = None`) admits ANY view-ordering-valid proposal, incl. a Byzantine leader's replay of an old-but-real block under an inflated view, until the replica re-accumulates its own lock | `HotStuffSafety.safeToVote`'s `None` branch / `HotStuffCoordinator.Enabled`'s `initialLockedQC` | **Narrowed, not fully closed** — `HotStuffLockedQCStore` persists a replica's real `lockedQC` to disk on every genuine advance and reloads it via `initialLockedQC` at coordinator construction, so a restart resumes from the replica's actual last lock instead of blank state. This closes the window on every restart from a replica's SECOND boot onward, adversarially tested against a fabricated-but-well-formed persisted QC (`HotStuffViewChangeSpecification`, "a fabricated-but-well-formed QC loaded as initialLockedQC" — proven to at worst self-DoS that one replica's voting, never corrupt consensus, since `HotStuffEngine.onQC` independently re-verifies BLS/quorum regardless of `lockedQC`). It does **not** close the window on a replica's very first-ever boot, when by definition nothing has been locked/persisted yet — that one-time gap still exists, bounded the same way as before (`HotStuffEngine.onQC`'s monotonic commit-height guard; see T4) |

## 5. Code surface (package `com.decentralchain.consensus.hotstuff` unless noted)
| File | Role | Audit priority |
|------|------|----------------|
| `HotStuffEngine.scala` | reducer: onQC/onProposal/onTimeout, commit rule | **highest** (T4) |
| `HotStuffSafety.scala` | lock/commit/equivocation rules | high (T5,T7) |
| `HotStuffQuorum.scala` | QC form/verify, BLS agg, quorum | high (T2) |
| `HotStuffVotePool.scala` | vote accumulation, invalid-drop | med (T6) |
| `HotStuffPacemaker.scala` | leader rotation, timeout | med (T9) |
| `HotStuffCoordinator.scala`, `NodeHotStuffEffects.scala` | shell: orchestration, sign/broadcast, observational commit | med |
| `Application.scala` (gated block) | wiring: subscriptions, timer, propose hook, committee provider | med |
| `network/messages.scala`, `network/BasicMessagesRepo.scala` | wire format + msg codes 39/40/41 | low-med (T8) |
| `CommitToGenerationTransactionDiff.scala:22` (`transaction/…`) | on-chain BLS PoP | high (T1) |
| `crypto/bls/BlsUtils.scala` | domain-separation tag SSOT — `_POP_`/`_ENDORSE_`/`_HSVOTE_`, the only tags used anywhere in production (the legacy shared `_NUL_` tag was deleted 2026-09-02, see the historical note in §7 item 9) | **highest** (T1, T8) — first file a crypto auditor should read |
| `CommitToGenerationTransaction.popMessage` (`transaction/…`) | PoP message SSOT — `chainId ‖ sender ‖ endorserPk ‖ generationPeriodStart` layout, constructed in exactly this one function, unconditional since genesis | high (T1) |

## 6. Evidence index
- **Design SSOT:** `docs/hotstuff-integration-design.md` (protocol ↔ code map, step-4c seams).
- **Internal security review:** `docs/hotstuff-security-review.md` (core review + findings 1–5 w/ current status).
- **Consensus plan:** `docs/consensus-upgrade-plan.md` (T0/T1/T2 context, enable checklist).
- **Unit tests (45):** `node/tests/…/consensus/hotstuff/*` + `…/network/HotStuffMessagesSpecification` +
  `…/settings/HotStuffSettingsSpecification` — incl. adversarial: forged aggregate, below-quorum,
  stale-justify no-vote, monotonic commit, equivocation, invalid-vote drop, plus a deterministic
  in-process 4-node coordinator simulation (happy path + crashed node).
- **Multi-node IT:** `node-it/…/sync/finalization/FourNodeHotStuffTestSuite.scala` — finality advances on
  all 4 nodes with HotStuff enabled; **green on CI** (ubuntu-latest).

## 7. Residual risk / known-open before mainnet
1. **Finding #2 (commit trust model)** — a *verified* COMMIT QC is trusted by design (standard HotStuff);
   safety rests on the honest-≥2/3 assumption + the voting/lock rules, which the auditor should prove.
   No receiver-side chain-replay gap; covered by `verifyQC` + monotonic-commit + `safeToVote` tests.
2. **Live behaviour unproven** — view=height/forger mapping, cross-period state, and proposing have only
   unit + single-CI-run coverage; **testnet soak (infra PR #49) required.**
3. **Equivocation → slashing (T5) — wired unconditionally, gated off by default only at the miner-fold step.**
   `HotStuffCoordinator.Enabled` detects verified conflicting votes (same voter/view/phase/committee-epoch,
   different block) and retains them as `HotStuffEquivocationProof`s independent of any settings
   (`onEquivocation` fires unconditionally); the miner folds retained proofs into a key block's
   `FinalizationVoting` only when `slashing-enabled` is set (default **off**, `Miner.foldHotStuffConflicts`).
   On receipt, `validateFinalizationVoting`'s proof checks (signature, same-epoch, non-duplicate,
   not-already-excluded) run unconditionally from genesis — the on-chain activation gate this used to
   require (feature 29 `HotStuffEquivocationEvidence`) was deleted 2026-09-02 under the "registry mirrors
   Waves" plan, since no chain in this repo's history had ever activated it and every node runs the same
   binary from genesis. Once a proof passes, the voter is unioned into `conflictGenerators`
   (`FinalizationVoting.allConflictGeneratorIndexes`) the same way a T0 conflicting endorsement does —
   proven end-to-end, including the wire hop (serialize/deserialize through real protobuf bytes) by
   `HotStuffEquivocationEvidenceE2ESpecification`. **Live testnet exercise pending** before this is claimed
   production-proven; `slashing-enabled` stays off on both testnet and mainnet until then.
4. **hotStuffFinalizedHeight is observational-only at the shipped default** (`authoritative = false`) —
   no path raises the "authoritative" finalized height in that posture. Where `authoritative = true` is
   explicitly opted into (testnet only, see the banner above), a genuine `commitQC` DOES raise it — but
   per audit **F-1** (see the banner's F-1 callout and `HotStuffSettings.authoritative`'s scaladoc), that
   raised value is **advisory, not enforcing**: no rollback refusal is keyed on it, so it does not
   actually prevent a reorg below the height it reports. Do not conflate "the number advances" with "T2
   provides finality" — see F-1 for the full trace of what the value's raise boundary DOES protect
   (correctly — see the audit's "Verified — sound" table) versus what happens to it afterward (nothing
   enforces it).
5. **Cross-committee-epoch fork (T10) — narrowed, not fully closed.** Committee identity
   (`committeeEpoch`, schema 1.6.5 `committee_epoch` field 7, = `GenerationPeriod.index`) is bound
   into the signed vote/QC bytes in `HotStuffQuorum.voteMessage`, and a new transition-gating rule
   (`HotStuffQuorum.acceptableCommitteeEpoch`) — applied in `HotStuffEngine.onQC`/`onProposal` before
   BLS verification — rejects any QC/justify whose epoch is not the current one or the immediately
   preceding one. `HotStuffCrossEpochForkSpecification` proves both the original fork hazard (unlabeled
   votes, unchanged/still-passing) and that fix (labeled votes from disjoint epochs can no longer be
   merged or relabeled into a valid cross-epoch QC, and `HotStuffEngine.onQC` rejects an out-of-window
   epoch end-to-end). Default `committeeEpoch = 0` keeps every pre-existing call site and wire peer
   byte-for-byte backward compatible. **A follow-up adversarial review (2026-08-04) then found that fix's
   OWN wiring introduced a distinct, previously-uncharacterized LIVENESS gap**, closed the same day:
   `committeeEpoch` was derived from the signing replica's own live chain tip
   (`blockchainUpdater.currentGenerationPeriod`), not the vote's target height, so two honest, synced
   replicas voting the identical target could sign different epochs from ordinary propagation skew at a
   rotation boundary and permanently stall that target's QC formation. Fixed by deriving `committeeEpoch`
   as a pure function of the target height (`HotStuffCoordinator.Enabled`'s `committeeEpochOf` parameter,
   `blockchain.generationPeriodOf(targetHeight).index` in `Application.scala`) at every vote-signing call
   site, plus an epoch-aware `HotStuffVotePool.onVote` dedup as defense-in-depth — see
   `HotStuffCrossEpochLivenessSpecification`. All HotStuff-area tests green as of 2026-08-04. **One item
   remains before this can be considered fully closed for mainnet:** no live multi-node Docker evidence
   of an actual committee-epoch transition exists yet (only unit/DST-level simulation) — needs a
   `node-it` scenario exercising a real generation-period rotation across a live cluster, validated on
   CI/testnet given local `node-it` Docker's documented memory/flakiness constraints on this laptop. (The
   `protobuf-schemas` 1.6.5 proto change is now published to Maven Central — verified live at
   `repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/1.6.5/` — so this is no longer a CI-build
   blocker.)
6. **Post-restart `lockedQC` replay window (T11) — narrowed, not fully closed.** `HotStuffLockedQCStore`
   persists each replica's real `lockedQC` to disk on every genuine advance and reloads it as
   `initialLockedQC` at coordinator construction, so a restart resumes from the replica's last real lock
   instead of `SafetyState()`'s blank slate. This closes the window for every restart from a replica's
   SECOND boot onward. It does **not** close it for a replica's very first-ever boot: at that point, by
   definition, nothing has ever been locked or persisted yet, so there is nothing for `load` to return —
   the original blank-slate replay risk documented at `HotStuffSafety.safeToVote` still technically exists
   for that one-time event. It remains bounded exactly as before (`HotStuffEngine.onQC`'s monotonic
   commit-height guard applies regardless of how the vote/QC was formed) — a wasted round at worst, never
   a safety break. Do not overclaim this mitigation as "fully closed."
7. **Self-sealing epoch trap (audit F-6, 2026-08-31) — REPRODUCED AND FIXED 2026-09-02.** The audit
   filed this MEDIUM as *unverified*, explicitly noting it was "not reproducible in the current DST
   harness (single shared `epochBelief` var)". It has since been reproduced in that harness (after the
   per-node tip/epoch change the audit itself called for) and fixed.
   **The trap.** The signed epoch (`committeeEpochOf(targetHeight)`, a pure function of the target's
   height) and the accepted epoch (`acceptableCommitteeEpoch` against `committeeEpochProvider()`, the
   replica's own live tip) are independent by design and correctly so. `settledDepth` keeps them close
   in the happy path, but nothing bounded the gap: once a T2 target fell more than one generation period
   behind a replica's own tip, that replica's own honest QCs for it were rejected before `verifyQC` ran,
   and a rejected QC never advances `EngineState` — so catching up required committing exactly the
   heights the rejection blocked. Self-sealing, from fully honest behaviour. The watchdog cannot fix it
   (`resetLocalSafetyState` touches only `engine.safety`; the mismatch is derived from chain height).
   **The fix** (`docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md`) is a height-lag
   filter on target selection, nothing more — no change to `acceptableCommitteeEpoch` (the one-step
   window is T10's safety content and stays untouched), nothing on the wire, no block-validation change,
   no feature gate. `HotStuffSettings.maxTargetLagFraction` (default 0.25) plus
   `maxTargetLag = max(settledDepth + 1, generationPeriodLength * fraction)` and a `tooStale(height) =
   tipHeight() - height > maxTargetLag()` predicate at three sites: `inFlightBranch` (a stale in-flight
   branch is abandoned rather than re-proposed, falling through to `blockSource`'s fresh tip),
   `onProposal` (a stale-target proposal is declined BEFORE `HotStuffEngine.onProposal` so a skip does
   not burn the monotonic, M1-persisted `lastVotedView`), and `castVotes` (defensive backstop for the
   `applyQC` phase-progression path). Observability: `hotstuff.stale-target-abandoned` and
   `hotstuff.stale-target-skipped-proposal` Kamon counters + distinct WARNs.
   **How it is tested.** `DstStaleTargetSelfSealScenarioSpecification` runs the realistic F-6 shape — the
   whole cluster's tips advancing past a stuck T2 target, which is what production does since feature-25
   finality keeps the chain moving underneath a stalled T2 round on every replica at once — as a paired
   RED/GREEN scenario: with the fix neutralized the cluster commits the STALE target (height 50) forever;
   with it active the stale branch is abandoned, the cluster re-anchors and commits at height 92,
   strictly above the stale target. Companions in the same file: `noEquivocation` across a
   demonstrated-real re-anchor, and a watchdog comparison where the RED arm reaches recovery exhaustion
   and the GREEN arm does not. `HotStuffLagReanchorSpecification` covers `tooStale` boundaries, the
   `settledDepth + 1` floor, the guard's placement (a declined stale proposal leaves `lastVotedView`
   unadvanced and that view still votable), and a regression case proving the guard does NOT fire on
   ordinary cross-epoch-liveness skew; `HotStuffSettingsSpecification` covers the setting's `require`.
   The whole fixed arm is **mutation-verified**: neutralizing the bound turns every fixed-arm assertion
   RED (the scenario's first version was vacuous under exactly this test and was restaged because of it —
   see that file's class doc for the recorded evidence and the reason the naive single-node staging
   cannot exercise the fix).
   **Still open for this item:** no live multi-node/testnet observation of the trap or of the re-anchor
   firing in production. The fix is DST- and unit-proven only, and the counters above exist precisely so
   a real occurrence becomes graphable — same caveat as the T10 item above.
8. **HISTORICAL NOTE (2026-09-02) — BlsCryptoV2 activation-boundary coupling, preserved for institutional
   memory; feature 30 no longer exists.** This item originally documented a real activation-boundary
   liveness window and a real correctness-gap-then-fix, both tied to feature 30 (`BlsCryptoV2`) being an
   on-chain activation gate. Feature 30 was deleted entirely on 2026-09-02 (`registry mirrors Waves;
   DCC improvements unconditional` plan) once it was confirmed that no chain in this repo's history —
   testnet included — had ever activated it, meaning there was no real (non-disposable) legacy-DST
   history anywhere that a gate needed to protect. The BLS domain-separation and bound-PoP behavior this
   item describes is therefore unconditional today: every node runs the same v2-only crypto from genesis,
   with no activation height, no boundary window, and no possibility of "mixed-era" votes in one QC. The
   coupling rationale below is kept because it explains *why* the equivocation-proof DST fix and the
   crypto version had to move together — that reasoning remains correct engineering history even though
   the mechanism it was reasoning about (a feature gate) is gone:
   > *(as originally written, 2026-09-02, when feature 30 was still an activation gate)* `cryptoV2` was a
   > monotone per-node read of that node's own live tip, so replicas crossed the activation height at
   > slightly different moments (ordinary propagation skew). `formQC`/`verifyQC` required a uniform DST
   > across every vote in one QC; mixed-era votes were dropped rather than merged, so a view straddling
   > the boundary could see no QC form for one or more rounds — liveness-only, self-healing via the
   > pacemaker's normal view advance, and harmless since T2 is observational-only (item 4). Separately,
   > `HotStuffEquivocationProof.signaturesValid` initially hardcoded the legacy DST instead of deriving it
   > from the CONTAINING block's height, which would have gone silently inert at activation; this was
   > fixed by deriving the vote DST from the containing block's height
   > (`HotStuffQuorum.voteDst(blockchain.supportsBlsCryptoV2(containingBlockHeight))`) and refusing outright
   > any proof carried by a block in the activation-straddling generation period, rather than risking a
   > false-positive/false-negative slash — proved by `BlsCryptoV2EquivocationProofBoundarySpec`. **Both the
   > activation-boundary window and its proof-DST fix are now moot**: with the gate deleted, every node
   > signs and verifies under the v2 DSTs (`_POP_`/`_ENDORSE_`/`_HSVOTE_`) from genesis, so there is no
   > boundary to straddle and no per-containing-block DST derivation needed.
   **The rule that replaces this item going forward: every node runs the same binary from genesis.**
   Deleting the two on-chain gates (feature 29 `HotStuffEquivocationEvidence`, feature 30 `BlsCryptoV2`)
   removes the loud UNIMPLEMENTED safety net `implemented = dict.keySet` provided for exactly these two
   behaviors (block-carried equivocation evidence, BLS crypto v2) — a node that had not upgraded used to
   be forced to halt (unknown-feature-active) rather than silently compute a different state hash. With
   both now unconditional, any FUTURE rule change to THESE SPECIFIC behaviors on a live chain would fork
   silently if not every node upgrades together before the change lands; there is no longer a feature-gate
   safety net for either. Every other future feature still gets normal feature-gate protection — this
   trade-off applies only to these two behaviors, which were deliberately made unconditional because no
   real (non-disposable) history anywhere had ever activated their gates.
9. **HISTORICAL NOTE (2026-09-02) — what feature 30 (`BlsCryptoV2`) did NOT close, preserved for
   institutional memory; feature 30 no longer exists.** (a) *(as originally written)* The legacy DST
   (`BlsUtils.BlsDomainSeparationTag`, `_NUL_`) was kept reachable forever, by design, because every
   pre-activation block's PoP/endorsement/vote signatures were produced under it and history had to stay
   verifiable. (b) *(as originally written)* The off-chain HotStuff vote/QC signing and endorsement paths
   switched DST on a live-tip read rather than a block height, safe only because those artifacts were not
   consensus-replayed. **Both points are now moot.** The 2026-09-02 "registry mirrors Waves" plan deleted
   the legacy `_NUL_` tag from production code entirely — it is not reachable at all any more, forever or
   otherwise — because no chain in this repo's history (testnet included) had ever activated feature 30,
   so there was no real legacy-DST history anywhere that needed to stay verifiable. The v2 DSTs
   (`_POP_`/`_ENDORSE_`/`_HSVOTE_`) are the only crypto in production today, used unconditionally by every
   code path from genesis; there is no live-tip-vs-block-height distinction left to draw.

## 8. Enable-gate checklist (all required before `dcc.hotstuff.authoritative = true` on **mainnet** — testnet already has both flags on, see banner)
- [x] Pure core + engine + shell implemented, gated OFF by default (mainnet)
- [x] 45+ unit tests + adversarial cases green (114/114 HotStuff-area tests as of the T10 fix, 2026-08-04)
- [x] Multi-node finality IT green on CI
- [x] `protobuf-schemas` 1.6.4 published (wire types)
- [x] `protobuf-schemas` 1.6.5 published (T10 `committee_epoch` field) — verified live on Maven Central 2026-08-04.
      **UPDATE 2026-09-02:** `Dependencies.scala` now pins `1.6.6` (schema jar for `FinalizationVoting.hotstuffConflicts`);
      Central publish of 1.6.6 is a separate, still-open item tracked elsewhere in this plan — not yet done, do not
      treat this line's "1.6.5 published" as covering it.
- [x] Testnet deploy + `dcc.hotstuff.authoritative = true` live on all 4 testnet nodes (2026-08-03/04, by
      explicit human decision, testnet-only, ahead of this audit); `GET /blocks/height/finalized` confirmed
      advancing via this mechanism (2026-08-04)
- [ ] Multi-day soak with crash/partition/equivocation scenarios formally recorded for the
      reworked/authoritative model — not yet documented despite the live deployment above
- [x] Equivocation → `conflictGenerators` slashing wired (T5) — unconditional from genesis (was gated
      feature 29, deleted 2026-09-02) + `slashing-enabled` (default off, miner-fold only); proof-carried,
      block-validated exclusion proven end-to-end incl. the wire hop
      (`HotStuffEquivocationEvidenceE2ESpecification`); **live testnet exercise pending** before
      `slashing-enabled` is turned on anywhere
- [ ] **External third-party consensus audit sign-off** (this package) — required before mainnet
      `authoritative`, not yet engaged
- [ ] Live multi-node Docker evidence of an actual committee-epoch (T10) transition — unit/DST-simulation
      only so far
- [ ] Decision + re-audit if/when HotStuff `authoritative` is proposed for mainnet
- [x] **BLS domain separation & bound PoP active on every network** — unconditional from genesis since
      2026-09-02 (was gated feature 30 `BlsCryptoV2`; deleted the same day the gate was removed, see §9
      below). HotStuff vote/QC signatures and block-carried equivocation proofs all sign/verify under the
      v2 DSTs (see §5's `BlsUtils.scala` entry and §7 items 8-9) with no activation step required or
      possible any more.
- [ ] **F-1 (audit 2026-08-31): decide advisory vs. enforcing for `authoritative`, and align the naming
      with the answer.** Today the floor is advisory only (no rollback refusal — see the banner's F-1
      callout); the flag's name (`authoritative`) and its raise method's name
      (`raiseHotStuffFinalizedHeight`) both imply enforcement that does not exist. Blocking for mainnet
      per the audit's residual-risk section regardless of which way this is decided — if enforcing is
      chosen, that is a separate consensus change (rollback refusal below the floor) needing its own
      design review and audit, not a documentation fix.

## 9. HISTORICAL NOTE (2026-09-02) — feature 30 (`BlsCryptoV2`) activation order requirement; feature 30 no longer exists

This section originally specified an activation-height ordering requirement for feature 30
(`BlsCryptoV2`) relative to features 25 and 29, and a different posture for fresh chains
(pre-activate at genesis) versus live testnet (activate by vote, tolerating a documented
activation-boundary window). It is preserved verbatim below for institutional memory, since the
reasoning about *why* fresh chains and a live chain needed different treatment remains valid
engineering history — but **none of it describes the current codebase**: feature 30 (and feature
29) were deleted entirely on 2026-09-02 once it was confirmed that no chain in this repo's
history, testnet included, had ever activated either one. There is no more activation order to
get right, because there is no more activation — every node runs the v2-only BLS crypto and
unconditional equivocation-evidence validation from genesis, on every network, with no
`preActivatedFeatures` entry and no by-vote path for either behavior.

> *(as originally written, 2026-09-02, when feature 30 was still an activation gate)*
>
> **Fresh chains.** Feature 30 must be **pre-activated at genesis, alongside features 25
> (`DeterministicFinality`) and 29 (`HotStuffEquivocationEvidence`)**, so that no legacy-DST signature
> ever exists on the chain and the equivocation-proof activation-boundary rule (§7 item 8's UPDATE, and
> `docs/hotstuff-bls-crypto-audit-2026-08-31.md`'s H2 STATUS note) costs nothing — there is no
> generation period straddling "unactivated" and "activated" for a chain that is activated from block
> one. `FunctionalitySettings.MAINNET` and `FunctionalitySettings.STAGENET` do exactly this
> (`preActivatedFeatures` includes `BlockchainFeatures.BlsCryptoV2.id -> 1`); a fresh private/dev chain
> built from `docker/private/decentralchain.custom.conf` or the `devnet` block in
> `node/src/main/resources/network-defaults.conf` gets the same treatment. Pinned by
> `BlsCryptoV2PreActivationSpec`.
>
> **Live testnet.** Feature 30 activates **by vote**, exactly like any other feature — `TESTNET`'s
> `preActivatedFeatures` is deliberately left unchanged so the chain's existing legacy-DST history stays
> verifiable rather than being retroactively rewritten. Operators should expect the documented
> one-to-few-view no-QC window at the activation boundary (§7 item 8: replicas cross the activation
> height at slightly different moments because `cryptoV2` is a live-tip read, so a view straddling the
> boundary can see no QC form for one or more rounds before the pacemaker's normal view advance
> self-heals it) and should expect that **equivocation proofs carried by a block in the generation period
> containing the activation height are rejected by design**, not merely "sometimes miss a real
> equivocator" — this is the intended boundary-refusal rule (`BlsCryptoV2EquivocationProofBoundarySpec`),
> not a gap to be worked around.
