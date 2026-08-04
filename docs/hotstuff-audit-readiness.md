# T2 HotStuff — External Audit Readiness Package

> **Purpose.** Single entry point for an external consensus auditor. It states scope, the trust model,
> the threat scenarios to probe, the code surface, and the evidence already produced — so the audit can
> start from a known baseline instead of rediscovering it.
>
> **Status of the thing being audited:** ✅ **Ready for audit against `main` @ `304bd0e408`** (2026-08-03).
> The pacemaker/single-active-view rework (Task 8) that this banner previously marked as pending — fixing
> the `view=block-height` shell model's conflation of view and block height, plus vote-pool bounding,
> `lockedQC` persistence across restarts, and the re-propose-locked-branch leader-timeout optimization — is
> complete and merged. The code described in this package (scope, threat model, evidence index below) is
> now frozen at that commit for the external auditor to review; it is not a moving target. See
> [`hotstuff-step5-findings-and-rework.md`](./hotstuff-step5-findings-and-rework.md) for the history of what
> was found and fixed to get here.
>
> **Separately — not covered by this audit-readiness status:** by explicit human decision, ahead of this
> external audit, a new `dcc.hotstuff.authoritative` opt-in flag was deployed live on testnet only
> (`../infra/node-config/testnet/dcc.conf`, `../infra/clusters/testnet/apps/nodes.yaml`:
> `hotstuff.authoritative = true`). That is an already-made operational decision for testnet, not something
> this audit retroactively covers — the audit is exactly what is required before `authoritative` could ever
> be considered for mainnet (§1, §7 item 4, §8 checklist still gate that). Gated behind
> `dcc.hotstuff.enabled` (default `false` outside testnet) — no behaviour change on mainnet today.

## 1. What it is, and the one property that bounds the whole audit
Basic 3-phase HotStuff (prepare → pre-commit → commit) over the **committed-generator committee**
(generators who submitted a `CommitToGenerationTransaction` with a PoP-verified BLS key). Quorum =
**≥2/3 of committed stake**.

**The bounding property: HotStuff commit is _observational_.** `NodeHotStuffEffects.onCommit` records a
`hotStuffFinalizedHeight` (surfaced on `/node/status`) but **does not mutate** the authoritative finalized
height — feature-25 Deterministic Finality remains the sole source of finality in `BlockchainUpdaterImpl`.
Therefore, in its current form, **a HotStuff bug cannot fork, halt, or roll back the chain**; the worst
case is a wrong/lagging observational number. The audit's severity ceiling is set by this — until a future
change makes HotStuff *authoritative* (raise finalized height on `commitQC`), which is explicitly out of
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
| T1 | Rogue-key forgery of the fast-aggregate | on-chain PoP | verified `…Diff.scala:22`; ✅ |
| T2 | Forged / below-quorum QC | `HotStuffQuorum.verifyQC` | all-signers-∈-committee + 2/3-stake + agg verify; unit-tested |
| T3 | Unverified QC influencing safety/commit | `HotStuffEngine.onQC/onProposal` | `verifyQC` gate before `update` (`:44`,`:70`); finding #1 ✅ |
| T4 | Conflicting / out-of-chain COMMIT | `verifyQC` + honest-quorum voting/lock rules | verified COMMIT QC trusted by design (finding #2); forged/below-quorum rejected, same-height re-commit blocked (tested) — audit the safety proof under the honest-≥2/3 assumption |
| T5 | Equivocation (double-sign) | `HotStuffSafety.equivocators` | detected; slashing/`conflictGenerators` wiring is future work |
| T6 | Byzantine voter stalls QC formation | `HotStuffVotePool.onVote` | invalid votes dropped on ingress; finding #3 ✅ |
| T7 | Stale-justify / conflicting-branch vote | `HotStuffSafety.safeToVote` | canonical rule; adversarially unit-tested |
| T8 | Cross-period replay of votes/PoP | PoP binds period; canonical vote message | binds `generationPeriodStart`; framing note = finding #4 |
| T9 | Crashed leader / partition (liveness + safety) | pacemaker + safety | unit + `FourNodeHotStuffTestSuite`; **needs live soak** |
| T10 | Cross-committee-epoch fork — two disjoint committees (e.g. a full validator-set rotation between committed-generators periods) each independently form a valid, honestly-signed 2/3-stake QC for a *different* block at the identical (view, height), with zero shared signers | `HotStuffQuorum`/`HotStuffVotePool` — committee identity is now bound into the signed vote/QC bytes | **CLOSED at the unit layer (2026-08-03).** A `committeeEpoch: Int = 0` field (schema 1.6.5, `dcc/block.proto` field 7 `committee_epoch`, reusing the existing `state.GenerationPeriod.index` rotation identifier — no new committee-hash concept) is now folded into the canonical signed bytes in `HotStuffQuorum.voteMessage`, carried on the wire on `HotStuffVote`/`QuorumCertificate`, and gated by a new `HotStuffQuorum.acceptableCommitteeEpoch(qcEpoch, currentEpoch)` transition rule (accepts the current epoch or the immediately-preceding one; rejects everything else) applied in `HotStuffEngine.onQC`/`onProposal` *before* cryptographic verification. `HotStuffCrossEpochForkSpecification` now additionally proves the fix: labeled votes from disjoint epochs can no longer be relabeled/merged into a cross-epoch QC, and `HotStuffEngine.onQC` demonstrably rejects a QC from an epoch outside the transition window end-to-end. Default `committeeEpoch = 0` is fully backward compatible — a pre-1.6.5 peer's messages simply omit the proto3 field and decode as `0`, matching every existing call site, so this closes cleanly with zero behaviour change for unlabeled traffic. All 114 HotStuff-area unit/DST tests green (`node-tests/testOnly com.decentralchain.consensus.hotstuff.* …`, 2026-08-03). **Real Docker evidence obtained (2026-08-03):** built the actual `node-it` Docker image off this
branch (`sbt node-it/docker`, local-`.m2`-resolved 1.6.5 schema) and ran `FourNodeHotStuffTestSuite`
against a real 4-node cluster — all 3 cases (plain finalization, crashed-generator recovery, network
partition) pass with the `committeeEpoch`-carrying wire format live end-to-end
(`node-tests`/`node-it` output archived at
`t10_docker_build.log`/`t10_nodeit_run.log` in the session scratchpad). This proves the wire change is
non-regressing on a real multi-node cluster (default `committeeEpoch = 0` throughout, since
`Application.scala`'s `committeeEpoch` provider only returns non-zero once a real generation-period
rotation occurs) — it does **not** yet prove an actual cross-epoch *transition* live (no scenario in
this run drove a real committed-generators rotation mid-test). **Still open:** (1) `protobuf-schemas`
1.6.5 is only installed to local Maven (`~/.m2`), built from `DecentralChain` repo branch
`consensus/committee-epoch-wire-field` (commits `a5ea11594`, `50376fcef`) — **not yet published to
Maven Central or merged**, so `node-scala`'s `consensus/fix-cross-epoch-fork` branch cannot build in CI
until that publish happens (mirrors Open Gates item 1's 1.6.4 precedent); (2) no Docker evidence of an
actual committee-epoch *transition* (two different committees, live rotation mid-cluster) exists yet —
only unit/DST-level simulation (`DstCommitteeChangeScenarioSpecification`) plus the non-regression
4-node run above; a dedicated `node-it` rotation scenario is still future work, best done on CI/testnet
given local `node-it` Docker's documented memory/flakiness constraints on this laptop (see
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
3. **Equivocation → slashing** not wired to `conflictGenerators` (T5) — acceptable while observational;
   required before authoritative.
4. **hotStuffFinalizedHeight is observational only** — no path raises the authoritative finalized height.
5. **Cross-committee-epoch fork (T10) — closed at the unit layer (2026-08-03).** Committee identity
   (`committeeEpoch`, schema 1.6.5 `committee_epoch` field 7, = `GenerationPeriod.index`) is now bound
   into the signed vote/QC bytes in `HotStuffQuorum.voteMessage`, and a new transition-gating rule
   (`HotStuffQuorum.acceptableCommitteeEpoch`) — applied in `HotStuffEngine.onQC`/`onProposal` before
   BLS verification — rejects any QC/justify whose epoch is not the current one or the immediately
   preceding one. `HotStuffCrossEpochForkSpecification` proves both the original hazard (unlabeled
   votes, unchanged/still-passing) and the fix (labeled votes from disjoint epochs can no longer be
   merged or relabeled into a valid cross-epoch QC, and `HotStuffEngine.onQC` rejects an out-of-window
   epoch end-to-end). Default `committeeEpoch = 0` keeps every pre-existing call site and wire peer
   byte-for-byte backward compatible. 114/114 HotStuff-area tests green as of 2026-08-03. **Two items
   remain before this can be considered fully closed for mainnet:** (a) the underlying `protobuf-schemas`
   1.6.5 proto change (`DecentralChain` repo, branch `consensus/committee-epoch-wire-field`) is only
   installed to local Maven today — it still needs a credentialed publish to Maven Central (same step
   Open Gates item 1 required for 1.6.4) before `node-scala` CI can build against it; (b) no live
   multi-node Docker evidence of an actual committee-epoch transition exists yet (only unit/DST-level
   simulation) — needs a `node-it` scenario exercising a real generation-period rotation across a live
   cluster, validated on CI/testnet given local `node-it` Docker's documented memory/flakiness
   constraints on this laptop.
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

## 8. Enable-gate checklist (all required before `dcc.hotstuff.enabled = true` on mainnet)
- [x] Pure core + engine + shell implemented, gated OFF by default
- [x] 45 unit tests + adversarial cases green
- [x] Multi-node finality IT green on CI
- [x] `protobuf-schemas` 1.6.4 published (wire types)
- [ ] Testnet deploy (infra PR #49) + multi-day soak with crash/partition/equivocation scenarios recorded
- [ ] Equivocation → `conflictGenerators` slashing wired (T5) before HotStuff is made authoritative
- [ ] **External third-party consensus audit sign-off** (this package)
- [ ] Decision + re-audit if/when HotStuff is made authoritative (raise finalized height on commitQC)
