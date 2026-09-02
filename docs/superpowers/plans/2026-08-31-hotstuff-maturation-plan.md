# T2 HotStuff Maturation Plan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move T2 HotStuff from "not audited/soaked — testnet only" to a state where turning it fully authoritative (or shelving it) is a decision made with real evidence — a benchmark, closed bug classes, adversarial proof, and an external audit — rather than a guess. This plan intentionally does **not** duplicate the substantial work that already exists: node-scala's DST harness (`docs/superpowers/plans/2026-07-24-hotstuff-dst-harness.md`, Tier 1 of the [E2E testing strategy](../../../infra/docs/superpowers/specs/2026-07-24-e2e-testing-strategy-design.md)) is **done** — real `HotStuffCoordinator.Enabled` instances running under seeded, fault-injecting simulation, including a crash-recovery scenario, a partition scenario, and a committee-mid-round-change exploratory scenario that specifically targeted the class of bug this session found live (clean at 200 seeds, documented as not proof of absence). Tier 6 (canary monitoring, `infra/docs/superpowers/plans/2026-07-24-tier6-canary-monitoring.md`) is also **done** for general chain liveness. This plan covers what neither of those does: HotStuff-specific operational alerting, a real finality-latency benchmark, a systematic sweep for the exact bug class found today, automatic recovery, one new adversarial scenario the existing DST suite doesn't model, and the external-facing work (audit, cryptographic review) neither prior plan attempted.

**Architecture:** Extends existing, working infrastructure at every point rather than building parallel systems — the Prometheus exporter already emits `dcc_hotstuff_finalized_height`/`dcc_hotstuff_lag` (`infra/monitoring/exporter.py:79-132`), so Task 1 adds alert *rules* on top of an already-exported metric rather than building new scraping; the DST harness already has `DstHarness`/`SafetyInvariants`/`FaultProfile` (`node/tests/.../hotstuff/sim/`), so Task 5 adds one new scenario file to that existing package rather than a new harness; the alerting path is the same Alertmanager → `alert-webhook.py` → GitHub Issues route Tier 6 already established.

**Tech Stack:** Scala 3 / ScalaTest (DST scenario), Python stdlib (exporter, matching its existing zero-dependency design), Prometheus alerting rules (YAML), GitHub Actions.

## Global Constraints

- This plan spans two repos: `node-scala` (code + tests) and `infra` (monitoring, alerting). Work in each repo's own checkout; do not cross-commit.
- Do not modify `HotStuffCoordinator`'s public interface or the DST harness's existing `SimClock`/`SimNetwork`/`DstHarness`/`SafetyInvariants` contracts (Tasks 1-4 of the DST harness plan) — only add to the package.
- `infra/monitoring/exporter.py` has a deliberate zero-third-party-dependency design (stdlib only, confirmed by reading the file) — keep any exporter changes in that style; do not add `prometheus_client` or similar.
- Every alert added to `infra/monitoring/alerts.yml` must follow the file's existing convention exactly: `expr:` / `for:` / a `severity:` label matching the existing vocabulary (`critical`, `high`, `warning`) — read 3-4 existing rules in the file before writing new ones, don't guess the schema.
- Tasks 6-8 (external audit, cryptographic review, decision) are organizational/vendor-engagement tasks, not code. Track them here for sequencing, but they end in a decision record, not a commit with a diff.
- Build/verify after every code task: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/compile"` for Scala changes.
- No new blockchain features ship from this plan — everything here is observability, testing, and process, matching the scope discipline already established for this project's consensus work.

---

### Task 1: Alert rules on the already-exported HotStuff metrics

**Files:**
- Modify: `infra/monitoring/alerts.yml`

**Why now, why not before:** `dcc_hotstuff_finalized_height` and `dcc_hotstuff_lag` have been exported since before this session (`exporter.py:79-90`) but have zero alert rules on them today — confirmed via `grep -n hotstuff infra/monitoring/alerts.yml` returning nothing. This is exactly why 2026-08-30's multi-hour stall went unnoticed: the data existed, nobody was told to look at it.

**Two distinct failure modes to cover, both real, both observed today:**
1. **Present but stuck** — `dcc_hotstuff_lag` growing without bound (today's post-recovery-attempt stall: the metric was there, just never decreasing).
2. **Absent when it shouldn't be** — the exporter only emits `dcc_hotstuff_finalized_height` at all when `hotStuffFinalizedHeight` is present in `/node/status` (`exporter.py:126-128`); right after a restart with zero commits, it's silently missing, not zero — `absent()` needs a bootstrap grace window or it'll page on every routine restart.

- [ ] **Step 1:** Read `infra/monitoring/alerts.yml`'s existing rules in full (it's short) to confirm the exact YAML shape — group name, `expr`/`for`/`labels.severity` fields — before writing anything.
- [ ] **Step 2:** Add a `for: 30m` rule on `dcc_hotstuff_lag > 50` (half of `max-rollback`, the same "half the ceiling" logic the E2E suite's own `SAFE_LAG_CEILING` uses) at `severity: warning` — this is observational, not consensus-critical, so it should not page as `critical`.
- [ ] **Step 3:** Add a rule for the absence case: `absent(dcc_hotstuff_finalized_height) unless absent(dcc_hotstuff_finalized_height offset 1h)` (fires only when the metric was present an hour ago and is now gone — distinguishes "HotStuff was never on" from "HotStuff was running and disappeared") with `for: 15m`, `severity: warning`.
- [ ] **Step 4:** Deploy to the live Alertmanager config (per this repo's existing deploy path for `infra/monitoring/*` — check how `prometheus.yml`/`alerts.yml` changes normally reach the server, likely the same sync mechanism used for compose files) and confirm the rules parse (`promtool check rules infra/monitoring/alerts.yml` if `promtool` is available, otherwise verify via the running Prometheus's `/api/v1/rules` endpoint after deploy).
- [ ] **Step 5: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra
git add monitoring/alerts.yml
git commit -m "monitor(hotstuff): alert on stuck lag and unexpected metric absence

dcc_hotstuff_finalized_height/dcc_hotstuff_lag have been exported since
before this session with zero alert rules on them -- exactly why
2026-08-30's multi-hour stall went unnoticed. Two rules: lag growing past
half the max-rollback ceiling (stuck-but-present), and the metric going
absent after having been present (the restart-before-first-commit case
observed live)."
```

---

### Task 2: T0 vs T2 finality-latency benchmark

**Files:**
- Create: `infra/monitoring/hotstuff-latency-benchmark.py` (or wherever this repo's other one-off measurement scripts live — check `infra/scripts/` first)

**Why:** HotStuff's entire justification is faster finality than T0. Nobody has measured that on this deployment's real topology (Newark VPS ↔ Frankfurt LKE). This produces the one number every later decision in this plan depends on.

- [ ] **Step 1:** Poll `/node/status` on main at a short interval (1-2s) for a real observation window (at least a few hundred blocks, spanning at least 2 committee-period boundaries so the comparison isn't biased by one boundary's transition cost).
- [ ] **Step 2:** For each block height, record the wall-clock time it first appears as `blockchainHeight`, the wall-clock time it first appears at or below `finalizedHeight` (T0), and the wall-clock time it first appears at or below `hotStuffFinalizedHeight` (T2).
- [ ] **Step 3:** Compute per-block latency-to-finality for both mechanisms; report median, p95, and the distribution shape (not just an average — the 2026-08-30 incident shows T2's latency is not a stable single number, it can be zero for hours).
- [ ] **Step 4:** Write the finding as a short markdown report (`docs/hotstuff-latency-benchmark-2026-08-31.md` or similar in `infra/docs/`) — the honest number, whichever direction it points, not a curated one.
- [ ] **Step 5: Commit**

```bash
git add monitoring/hotstuff-latency-benchmark.py docs/hotstuff-latency-benchmark-*.md
git commit -m "research: measure real T0 vs T2 finality latency on the live testnet topology"
```

---

### Task 3: Systematic committee-data safety sweep

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/appender/BlockAppender.scala`, `.../ExtensionAppender.scala` (or wherever the sweep finds real instances — start from the grep below)
- Reference: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (`currentCommittedGeneratorSet`, added 2026-08-31, commit `eb1fce2ccd`) — the safe pattern to apply where needed.

**Why:** This session found and fixed one instance (HotStuff's committee provider in `Application.scala`) of a live-cache staleness bug, and audited — but did not exhaustively fix — two more real consumers (`MicroblockAppender.voteSelf`, `CommonGeneratorsApi`'s balance fallback), correctly leaving them as-is because a test proved the "safe" fix broke a legitimate balance-stability invariant there. This task is the *systematic* version of that spot-check: classify every remaining consumer, not just the ones this session happened to notice.

- [ ] **Step 1:** `git grep -n "currentGeneratorSet\b" node/src/main/scala/` — the full, current list (was 5 call sites as of 2026-08-31; confirm it's still that or note what changed).
- [ ] **Step 2:** For each, classify as either (a) synchronous use-once within the same block-processing call (safe, matches T0's `validateFinalizationVoting` pattern) or (b) cached/reused across later, unrelated events (unsafe, matches HotStuff's original bug) — document the classification with a one-line reason per call site, not just a verdict.
- [ ] **Step 3:** For anything newly classified unsafe, apply `Blockchain.currentCommittedGeneratorSet` (the existing safe accessor) — but first write a test proving the swap doesn't break a balance-stability invariant the way it did for `MicroblockAppender`/`CommonGeneratorsApi`, following that exact precedent (`GeneratorsApiRouteSpec`) rather than assuming it's safe.
- [ ] **Step 4:** Write up the full classification (all 5+ call sites, verdict, reasoning) as a permanent record — extend `docs/consensus-divergences-from-upstream.md` or create a sibling doc, matching this project's existing pattern of documenting deliberate divergences with reasoning, not just fixing and moving on.
- [ ] **Step 5:** `sbt "node/compile" "node-tests/testOnly com.decentralchain.http.GeneratorsApiRouteSpec com.decentralchain.consensus.hotstuff.*"` — confirm no regression.
- [ ] **Step 6: Commit**

```bash
git add <changed files> docs/<classification-doc>
git commit -m "fix(consensus): systematic sweep of committee-data live-cache consumers

Generalizes the fix from 7dfe0a8cd8/eb1fce2ccd beyond the one instance
that broke live -- every remaining consumer of currentGeneratorSet
classified as safe (synchronous use-once) or unsafe (cached/reused),
with fixes applied only where a test confirms no balance-stability
regression, following the GeneratorsApiRouteSpec precedent."
```

---

### Task 4: Automatic recovery from a wedged committee

**Files:**
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffWatchdog.scala` (or integrate into the existing `hotStuffScheduler`/`onRoundTimerTick` loop in `Application.scala` if that's the more natural seam — check both before deciding)
- Test: sibling spec

**Why:** Today's actual recovery was: notice via manual investigation, SSH in, `rm` a stale `locked-qc.dat`, coordinate a manual multi-node restart. That whole sequence is now well-understood and mechanical enough to automate.

**The exact condition to detect** (from today's real incident): `onRoundTimerTick` fires every `round-timeout` with zero resulting `Committed`/`Rejected`/`EnteredView` action for N consecutive ticks, despite a non-empty current committee. That combination — ticking, non-empty committee, zero progress — is the wedge signature; a genuinely empty committee (this plan's Task 1 already alerts on that separately) is a different, correctly-distinguished case that should *not* trigger a lock-file wipe.

- [ ] **Step 1:** Confirm the exact detection signal is available without new plumbing — check whether `HotStuffCoordinator`'s existing action stream (`HotStuffAction.Committed`/`Rejected`/`EnteredView`) is observable from outside the coordinator today, or whether this needs a small, additive hook (not a change to existing signatures).
- [ ] **Step 2:** Write the watchdog: after N consecutive stall-ticks (start with N tuned to a few multiples of `round-timeout`, not a guess — base it on Task 2's latency data once available), clear the persisted `locked-qc.dat` and force a fresh `refreshCommittee()` + re-entry at the current view — the exact manual steps from today, automated.
- [ ] **Step 3:** This must not be able to touch T0/`finalizedHeight` — the watchdog's blast radius is HotStuff's own local state only, never the authoritative finality path. Write a test asserting the watchdog's recovery action has zero effect on `finalizedHeight`.
- [ ] **Step 4:** Test the watchdog against a reproduction of today's exact scenario, ideally via the DST harness (Task 5 below may produce the right scenario to reuse here — sequence these two tasks together if that's cleaner than doing them independently).
- [ ] **Step 5:** `sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.*"`.
- [ ] **Step 6: Commit**

```bash
git add node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffWatchdog.scala <test file>
git commit -m "fix(consensus): automatic recovery from a wedged HotStuff committee

Automates the exact manual recovery from 2026-08-30/31 (clear stale
locked-qc.dat, force a fresh committee resample, re-enter the view) once
N consecutive round-timer ticks produce zero progress despite a
non-empty committee. Cannot touch finalizedHeight -- blast radius is
HotStuff's own local state only."
```

---

### Task 5: DST scenario for today's specific incident class

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstEmptyCommitteeSourceScenarioSpecification.scala`

**Why this isn't already covered:** the existing DST harness (Tier 1, done) models network faults — crashes, partitions, committee *composition* changing mid-round. It does not model today's actual root condition: the committee **data source itself** returning empty because no `CommitToGenerationTransaction` landed for the period, which is a data-availability problem, not a network-fault problem. `DstHarness.setCommittee` can already inject an empty `GeneratorSet` directly — this task is a new scenario using existing harness capability, not new harness infrastructure.

- [ ] **Step 1:** Write a scenario: committee starts non-empty, a period boundary is simulated via `setCommittee(Seq.empty)`, then a later `setCommittee(<real committee>)` models the automation eventually running (this session's actual fix). Assert `SafetyInvariants.checkAll` holds throughout, and — the part today's real incident got wrong — that the coordinator actually recovers and resumes committing once given a real committee again, not just that it stays safe while starved.
- [ ] **Step 2:** Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.DstEmptyCommitteeSourceScenarioSpecification"`. If this scenario reveals the coordinator does *not* self-resume cleanly once given a real committee (plausible — today's live incident needed a full restart, not just fresh data), that is a real, separate finding: document it, do not force the test to pass by changing what it asserts.
- [ ] **Step 3: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstEmptyCommitteeSourceScenarioSpecification.scala
git commit -m "test: add DST scenario for committee-source-empty (2026-08-30/31 incident class)

Existing DST scenarios model network faults; this models the data-
availability failure that actually happened -- committee source
returning empty because no commit transaction landed for the period,
using DstHarness.setCommittee's existing capability, not new harness
infrastructure."
```

---

### Task 6: External BFT protocol audit — engagement, not code

**Files:** None (organizational task, tracked here for sequencing).

**Gate: do not start this task until Tasks 1-5 have run clean for a real observation window.** Every bug an internal alert, sweep, or DST scenario finds first is one an hourly-rate external audit doesn't have to find.

- [ ] **Step 1:** Scope the engagement precisely: the 3-chain commit rule, pacemaker/view-change correctness, the T10 cross-epoch-fork fix, and — the one place HotStuff is allowed to touch real state — the `raiseHotStuffFinalizedHeight` safety-check boundary in `BlockchainUpdaterImpl`.
- [ ] **Step 2:** Identify 2-3 firms with real, published HotStuff/BFT audit experience (several exist given how many production chains run HotStuff variants) — this is a real vendor decision, not something to pick without the user's involvement.
- [ ] **Step 3:** Package the scope: point the auditor at `docs/hotstuff-security-review.md` (existing internal review, saves them re-deriving context), this plan's Tasks 1-5 results, and the DST harness's own documented findings (including Task 7's "clean at 200 seeds, not proof of absence" caveat — an honest audit brief, not a sanitized one).
- [ ] **Step 4:** Get the user's explicit sign-off before engaging anyone externally or sending code/docs outside the org — this is exactly the kind of externally-visible action that needs confirmation first, same standard as this project's other external-facing decisions.
- [ ] **Step 5:** Record the outcome — findings, severity, this project's response to each — as a permanent doc, same convention as `docs/upstream-reports/`.

---

### Task 7: Independent BLS aggregation review

**Files:** None (organizational task).

- [ ] **Step 1:** Scope narrower than Task 6: specifically the aggregate-signature scheme in `crypto/bls/BlsUtils.scala` — rogue-key attacks, domain-separation robustness (the `BlsDomainSeparationTag` usage), signature malleability. Task 20 (this session, point-at-infinity validation) closed one specific gap; this is the outside pass that catches the class of issue a general audit tends to skim past.
- [ ] **Step 2:** Can potentially be folded into Task 6's engagement if the same firm has cryptographic review capability — check before running a separate vendor process.
- [ ] **Step 3:** Same sign-off requirement as Task 6 before any external engagement.

---

### Task 8: Decide, with data

**Files:** `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` or a successor doc (final record).

**Why this is its own task, not a footnote:** every prior task produces exactly one input this decision needs — Task 2's real latency number, Tasks 3-5's hardening evidence, Task 6-7's audit findings. Making this call before all four exist is a guess dressed as a decision.

- [ ] **Step 1:** With all four inputs in hand, present three honest outcomes to the user (not a recommendation dressed as a foregone conclusion):
  - **Graduate** — audit clean, DST/sweep clean, latency win real: move toward authoritative status on a real network, on a real timeline, still gated on further soak.
  - **Stay observational** — fundamentals sound but findings remain, or the latency win didn't materialize on this topology: keep running as-is, re-evaluate after fixes land.
  - **Shelve it** — audit surfaces something structural, or T0 was already fast enough here: redirect the investment into hardening T0 instead. A legitimate outcome, not a failure of this process.
- [ ] **Step 2:** Record the decision and its full evidentiary basis in the permanent reference doc, matching this project's existing standard of citing real test runs and real data, not assertions.

---

## Self-Review

- **No duplication of existing work:** confirmed via direct inspection that the DST harness (Tier 1) and canary monitoring (Tier 6) are both already done — this plan builds on them (Task 5 extends the DST package; Task 1 reuses Tier 6's alerting path) rather than re-proposing them.
- **Every task traces to a real, specific gap** named in this session's own findings — no task exists to fill out a template.
- **Ordering encodes real dependencies:** Task 6 is explicitly gated on Tasks 1-5 (don't pay audit rates for internally-findable bugs); Task 8 is gated on everything (a decision needs all its inputs).
- **External-facing actions (Tasks 6-7) require explicit user sign-off** before anything leaves the org, matching this project's established standard for that class of action.
