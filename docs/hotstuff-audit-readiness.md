# T2 HotStuff — External Audit Readiness Package

> **Purpose.** Single entry point for an external consensus auditor. It states scope, the trust model,
> the threat scenarios to probe, the code surface, and the evidence already produced — so the audit can
> start from a known baseline instead of rediscovering it.
>
> **Status of the thing being audited:** ⚠️ **REWORK PENDING — audit the reworked model, not today's shell.**
> The pure core + tests are sound, but the first live multi-node run (step 5) showed the `view=block-height`
> shell model does not work on an NG chain and needs redesign. **Read
> [`hotstuff-step5-findings-and-rework.md`](./hotstuff-step5-findings-and-rework.md) first** — it records the
> four bugs fixed live, the still-open QC-formation issue, and the pacemaker-vs-feature-25 decision the audit
> should weigh in on. The scope/threat-model/evidence below remain valid for the *core*; the shell view/leader
> mapping is being reworked. Gated behind `dcc.hotstuff.enabled` (default `false`) — no behaviour change today.

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
| T10 | Cross-committee-epoch fork — two disjoint committees (e.g. a full validator-set rotation between committed-generators periods) each independently form a valid, honestly-signed 2/3-stake QC for a *different* block at the identical (view, height), with zero shared signers | `HotStuffQuorum`/`HotStuffVotePool` — committee identity is not bound into the signed vote/QC bytes | **NOT stopped today** — proven reachable by `HotStuffCrossEpochForkSpecification` (unit-level, no coordinator/network needed); invisible to `HotStuffSafety.equivocators` (single-voter double-sign detector only, blind to disjoint-signer forks); full write-up in `docs/hotstuff-integration-design.md` §6 and §8 Open Gates item 2. **Bounded**: T2 commit is observational only (see §1) — this cannot fork, halt, or roll back the chain today. Closing it for real is a genuine protocol-design decision (bind committee identity into signed vote/QC bytes + a coordinator-level transition-gating rule, or a full joint-consensus two-phase membership-change protocol), comparable in scope to the pacemaker rework — deliberately left open for this audit to weigh in on rather than rushed |

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
5. **Cross-committee-epoch fork (T10)** — committee identity is not bound into the signed vote/QC bytes
   in `HotStuffQuorum`, so two disjoint committees (e.g. a full validator-set rotation between committed-
   generators periods) can each independently certify a valid, honest 2/3-stake QC for a *different* block
   at the same (view, height) — zero shared signers, invisible to `HotStuffSafety.equivocators`. Proven
   reachable by `HotStuffCrossEpochForkSpecification`; full write-up in `docs/hotstuff-integration-design.md`
   §6 and §8 Open Gates item 2. This is a real gap in the production QC/committee-binding logic, but it is
   bounded today: T2 stays strictly observational (§1) — it cannot fork, halt, or roll back the chain;
   feature-25 remains sole authoritative finality. Closing it for real requires a genuine protocol-design
   decision (binding committee identity into signed vote/QC bytes plus a coordinator-level transition-
   gating rule, or a full joint-consensus membership-change protocol) — comparable in scope to the
   pacemaker rework, and deliberately left for this audit to weigh in on rather than rushed.

## 8. Enable-gate checklist (all required before `dcc.hotstuff.enabled = true` on mainnet)
- [x] Pure core + engine + shell implemented, gated OFF by default
- [x] 45 unit tests + adversarial cases green
- [x] Multi-node finality IT green on CI
- [x] `protobuf-schemas` 1.6.4 published (wire types)
- [ ] Testnet deploy (infra PR #49) + multi-day soak with crash/partition/equivocation scenarios recorded
- [ ] Equivocation → `conflictGenerators` slashing wired (T5) before HotStuff is made authoritative
- [ ] **External third-party consensus audit sign-off** (this package)
- [ ] Decision + re-audit if/when HotStuff is made authoritative (raise finalized height on commitQC)
