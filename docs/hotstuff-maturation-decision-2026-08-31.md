# T2 HotStuff Maturation — Decision Record

Captured 2026-08-31. Closes Task 8 of `docs/superpowers/plans/2026-08-31-hotstuff-maturation-plan.md`. Every input below traces to a real test run, a real live-network observation, or a real code review finding — not an estimate.

---

## 1. What this decision is

Whether T2 HotStuff moves toward authoritative status on a real timeline, stays observational, or gets shelved in favor of hardening T0. Per the plan, this is made with four real inputs: a latency benchmark, hardening evidence (Tasks 3-5), audit findings (Tasks 6-7, done in-house), and this record.

---

## 2. Input 1 — Real finality-latency benchmark (Task 2)

2h19m31s / 271-block live observation window on the actual testnet topology (Newark VPS ↔ Frankfurt LKE), spanning 2+ committee periods (`generationPeriodLength=100`).

| Mechanism | Result |
|---|---|
| T0 (feature-25, authoritative) | Finalized normally throughout. Stable ~100-block steady-state lag (~52min). Median 3113.7s, p95 3381.3s, n=168. |
| T2 (HotStuff, observational) | **Finalized ZERO new blocks the entire window.** Frozen at height 197 from first sample to last. No valid latency statistic exists — reported as unbounded/infinite rather than curated into a misleading average. |

Full report: `../../infra/docs/hotstuff-latency-benchmark-2026-08-31.md`.

**This was not a stale finding when measured.** The same stall was confirmed still live and ongoing at the time of this decision's first draft: chain height 1521, `hotStuffFinalizedHeight` still 197 — over 6 hours and 1300+ blocks stalled, unrecovered, on the build that predates this session's fixes. It has since been deployed away — see §8 for the confirmed recovery.

## 3. Input 2 — Hardening evidence (Tasks 3-5)

- **Task 3 (committee-data safety sweep):** exhaustive reclassification of all 5 real consumers of the live `currentGeneratorSet` cache. Verdict: no code change needed — every consumer already correctly classified as safe (synchronous use-once) or deliberately left unsafe with a passing regression test proving why (`GeneratorsApiRouteSpec` balance-stability invariant). Full record: `docs/consensus-divergences-from-upstream.md` §6.
- **Task 4 (automatic recovery watchdog):** `HotStuffWatchdog` detects a wedged-but-non-empty committee (N=60 ticks / 72s, backoff to a 5-attempt cap at ~38.4min, handing off to Task 1's alerts) and automates the previously-manual `rm locked-qc.dat` + restart recovery. Went through 2 review rounds; both a Critical logic gap (rejected QCs silently counted as progress, masking a real wedge) and an unbounded-refiring risk were found and fixed.
- **Task 5 (DST scenario for the exact incident class):** models the committee data source itself going empty (not a network fault). Real, significant finding: **self-resumption after a real committee is restored succeeds in only ~49/100 simulated trials** — genuinely flaky, not reliable. The test asserts the measured band, not forced to either clean extreme.

## 4. Input 3 — In-house security audit (Tasks 6-7)

Done in-house rather than via an external firm (user decision, 2026-08-31), by independent adversarial subagent review with multiple rounds of skeptical re-review — not a single pass.

### BFT protocol audit (`docs/hotstuff-bft-audit-2026-08-31.md`)
10 findings, 2 High:
- **F-1 (High under `authoritative=true`):** the "authoritative finalized height" HotStuff raises does not actually prevent a reorg below it — no rollback path checks `finalizedHeight`, and a rollback silently caps the floor back down. The flag's name promises enforcement the implementation doesn't provide. **Not yet fixed** — this is a design/naming decision, not a bug fix, and is explicitly the kind of finding that should gate `authoritative=true` from ever reaching mainnet until resolved.
- **F-2 (High):** `HotStuffWatchdog`'s recovery could clear `lastVotedView`, the sole guard against double-voting in the same view — a genuine self-inflicted equivocation risk if the watchdog fired with votes in flight. **Fixed and merged** (1 fix round + 1 test-quality follow-up, both independently re-reviewed).
- F-3 (Medium): `HotStuffSafety.equivocators` — the codebase's only Byzantine-detection mechanism — is dead code, never called in production. **Not fixed.** No equivocation detection ships today.
- F-6 (Medium): T10's one-step epoch-acceptance window can become a self-sealing liveness trap if T2 falls behind by more than one full generation period — the watchdog cannot fix it (it's a function of chain height, not local safety state). **Not fixed**, and directly relevant given the live stall in §2 already exceeds one generation period.
- Full findings, including F-4/F-5/F-7 through F-10, in the audit doc.

### BLS cryptographic audit (`docs/hotstuff-bls-crypto-audit-2026-08-31.md`)
1 Critical, 2 High, several Medium/Low:
- **C1 (CRITICAL):** light-node mode (`enable-light-mode=true`) skipped ALL BLS proof-of-possession and curve validation for `CommitToGenerationTransaction` on the snapshot-application path — a malicious serving peer could seat a rogue key or the point at infinity as a committee generator, fully breaking the rogue-key defense for light clients. **Fixed and merged** — took 4 fix rounds + 4 review rounds + 1 additional cross-cutting fix, because 3 successive fix attempts each gated the validation skip on something peer-influenceable (a declared transaction status, then a block header field) before landing on the correct shape: unconditional validation, no skip of any kind. A final whole-branch review then caught one more variant (a phantom generator entry via an unreconciled snapshot field) — also fixed, independently re-verified.
- H1/H2 (High): no subgroup check in `verifyAgg` (contract-dependent, not defensive), and a single domain-separation tag shared across 3 message types (currently safe by encoding-length coincidence only, not by design). **Not fixed** — defense-in-depth items, not immediately exploitable, but latent traps for future protocol changes.
- Full findings in the audit doc.

**What this audit replaced:** an external third-party engagement (Tasks 6-7 as originally scoped). It found a real Critical vulnerability and a real High-severity safety bug — the same class of yield a paid external audit would be expected to produce, at the cost of session time instead of audit fees. It is explicitly **not equivalent** to an external audit for provenance/liability purposes, and several items (constant-time crypto behavior, the `blst` build artifact's provenance vs. upstream, live multi-node Byzantine behavior) remain things only a real external audit or live-network testing can settle.

## 5. What's fixed vs. what's still open

**Fixed, merged to `dev`, independently re-verified:** F-2 (watchdog equivocation risk), C1 (light-node BLS bypass) + its phantom-entry variant, Tasks 1-5's own findings (Critical watchdog progress-detection bug).

**Deployed live:** Task 1's alert rules (`HotStuffLagGrowing`, `HotStuffMetricMissing`) — deployed 2026-08-31, confirmed the exact live stall in §2 would have paged had it existed sooner. The full fix set (Tasks 1-5 + C1 + F-2) is now deployed to all 4 testnet nodes as of 2026-09-01, with the live stall confirmed recovered — see §8.

**Not fixed, explicitly still open:**
- F-1 — `authoritative` flag doesn't enforce what its name claims.
- F-3 — no equivocation detection ships in production.
- F-6 — self-sealing epoch trap once HotStuff lags more than one generation period (directly relevant to the ongoing live stall).
- H1/H2 (BLS) — defense-in-depth crypto hardening.
- The ~49% self-resumption flakiness (Task 5) — root cause not bisected, only observed.
- `BlockDiffer.scala:70`'s `snapshot.isEmpty` gate — light nodes accept any well-formed challenge declaration unverified, for all transaction types (found during C1's fix cycle, correctly scoped out as broader than C1).

## 6. The three outcomes

**Graduate** — move toward authoritative status on a real network, on a real timeline, still gated on further soak.
Not supported by current evidence. F-1 alone means `authoritative=true` does not deliver what it claims; F-3 means zero Byzantine detection; the live stall in §2 is unresolved on the currently-running build.

**Stay observational** — keep running as-is, re-evaluate after fixes land.
Consistent with current evidence. T2 continues to provide real signal (Task 1's alerts, the latency data) without touching T0's authoritative path. This is the status quo and requires no action to continue.

**Shelve it** — redirect investment into hardening T0 instead.
Not supported as a "T0 was already fast enough" conclusion — T0's ~52min steady-state lag is a real number, but no comparison was made against what T0 would cost to make faster, and T2's problems are watchdog/scope gaps, not evidence T2's basic approach is unsound.

## 7. Recommendation

**Stay observational**, with a named, concrete follow-up list rather than an open-ended "later":
1. ~~Deploy the merged fixes (F-2, C1) to the live testnet fleet~~ — done 2026-09-01, confirmed recovering the live incident this whole plan was built around (see §8).
2. Resolve F-1 — decide whether `authoritative` should mean real reorg enforcement (a real consensus change, its own design+review) or should be renamed/documented as advisory. Do this before `authoritative=true` is ever considered for anything beyond testnet opt-in.
3. Wire or delete `HotStuffSafety.equivocators` (F-3) — ship a real Byzantine detector or state plainly that none exists.
4. Bound HotStuff's lag (F-6) so it cannot self-seal past one generation period — directly relevant given the live incident already exceeds this.
5. Bisect the 49% self-resumption flakiness (Task 5) to a root cause, not just a measured rate.
6. A genuine external audit remains valuable before any authoritative promotion, specifically for the items the in-house audit explicitly could not settle (constant-time crypto, `blst` artifact provenance, live Byzantine behavior).

## 8. Deploy status of the fixes in this record — CONFIRMED LIVE, T2 RECOVERED

Deployed 2026-08-31/2026-09-01 via `infra`'s `deploy-testnet-release.yml` (the path `TOPOLOGY.md` mandates for all 4 nodes together, not the single-service path):

1. Built `node-scala` image at commit `1bd671f8e6` (the merge containing all Task 1-5 fixes + C1 + F-2), tag `testnet-1bd671f8e6`, digest `sha256:e2e84774ddd5cffa374aa7d88254feec2bfc2ddeec18c42d1c822d1b602bd527`.
2. Deployed to the VPS main node directly (SSH, immediate).
3. k8s fleet (gen-0/gen-1/val-0): the deploy workflow opened PR #151 pinning `clusters/testnet/apps/nodes.yaml` to the same digest. Branch protection required 1 approval + a status check that hadn't run — merged via admin override (explicit user decision, given the digest was independently confirmed to match the intended build).
4. Confirmed all 3 k8s StatefulSets now reference the correct digest (`sha256:e2e84774...`) in `nodes.yaml` on `main`.

**Real outcome — the live incident from §2 recovered:**

| Time | Chain height | `hotStuffFinalizedHeight` | Lag |
|---|---|---|---|
| Before deploy | 1521 | 197 (stuck) | 1324 blocks, 6+ hours |
| 2026-09-01 00:56:34 | 1685 | 1682 | 3 |
| 2026-09-01 00:57:37 | 1685 | 1682 | 3 |
| 2026-09-01 00:57:53 | 1688 | 1685 | 3 |
| 2026-09-01 00:58:08 | 1688 | 1685 | 3 |

T2 jumped from a 1324-block, 6+ hour stall to a steady 3-block lag — matching `settled-depth=3` by design — confirmed sustained across multiple independent polls, not a one-off blip. This is the watchdog (Task 4) automating exactly the manual recovery this whole plan was built around, working on the live incident it was designed for, on the first real deploy.

**Not independently confirmed from this session:** direct k8s pod/container verification (no `kubectl` cluster access from this environment) and Prometheus's own recorded time series (external query endpoint not reachable from here). The VPS's own public status endpoint and the confirmed `nodes.yaml` digest are the evidence this record relies on; a operator with cluster access should independently confirm gen-0/gen-1/val-0 pod images and `dcc_hotstuff_finalized_height`/`dcc_hotstuff_lag` in Grafana as a final check.
