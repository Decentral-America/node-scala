# F-3: Wire Up HotStuff Equivocation Detection — Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement the plan derived from this spec.

## Context

This is step 1 of a larger, sequenced effort to make T2 HotStuff genuinely authoritative-safe before testnet is declared source-final for stagenet (full context: `docs/hotstuff-maturation-decision-2026-08-31.md`, `docs/hotstuff-bft-audit-2026-08-31.md` finding F-3, and the research summarized in this session's design conversation on HotStuff's actual safety model). The sequence, in order, is:

1. **This spec** — wire up equivocation detection (small, safe, a real prerequisite for later enforcement work).
2. Fix T2's finalization depth so it can't wedge on ordinary FairPoS reorgs (the structural fix; separate spec).
3. Fix F-6's committee-rotation self-sealing trap (separate spec).
4. Turn on real hard-refuse enforcement with operator alerting (separate spec, gated on 1-3).

Enforcing a safety guarantee whose only violation-detector is dead code is the wrong order of operations — this spec closes that gap first.

## Problem

`HotStuffSafety.equivocators(votes: Iterable[HotStuffVote]): Set[Int]` (`HotStuffSafety.scala:96`) correctly detects when a voter has signed conflicting votes at the same `(view, phase)` — the textbook definition of BFT equivocation. It is proven correct: the DST harness's own `SafetyInvariants.noEquivocation` (`sim/SafetyInvariants.scala:51-52`) calls it directly and is exercised across all 7 DST scenarios plus the dedicated `HotStuffResetDoubleVoteSpecification`/`HotStuffWatchdogInFlightResetScenarioSpecification` regression tests added when F-2 was fixed.

**It has zero production call sites.** `grep -rn "equivocators" node/src/main/scala/` returns exactly one hit: the definition. T2 ships with no Byzantine-behavior detection running against real network traffic today.

## Design

### 1. Call `equivocators` on the real vote-ingress path

`HotStuffVotePool` is where votes actually accumulate before quorum formation (the production analogue of the DST harness's vote tracking). Add a call to `HotStuffSafety.equivocators` on the votes accumulated for each target, at the same point `onVote` already does per-vote validation (`verifyVote`/quorum-check machinery). This does not change vote acceptance — a detected equivocation does not block quorum formation or vote processing; it is a parallel detection path, not a new consensus rule. (Changing consensus acceptance rules is out of scope for this spec — that's the harder step-4 enforcement work.)

### 2. On detection: log + metric

- Log at `ERROR` with the offending voter index/committee identity and the conflicting `(view, phase, blockId)` pairs — matching this project's established pattern of loud, operator-visible logging for consensus anomalies (see the watchdog's own logging for precedent).
- Emit a new Prometheus counter, `dcc_hotstuff_equivocations_total`, labeled by voter/generator identity, following `infra/monitoring/exporter.py`'s existing stdlib-only, zero-dependency style. Add a corresponding alert rule to `infra/monitoring/alerts.yml` (any occurrence should page — this is unambiguous evidence of either a Byzantine actor or a serious bug, unlike T2's existing observational-only lag alerts).

### 3. Feed into the existing `conflictGenerators` exclusion mechanism

T0 already has `Blockchain.conflictGenerators(period): ConflictGenerators`, keyed by `GeneratorIndex` (`Blockchain.scala:97`, consumed by `FinalizationState.scala`'s finalization voting logic to exclude conflicting generators from being counted toward quorum). `HotStuffSafety.equivocators` already returns `Set[Int]` in the same committee-index space HotStuff's `voteMessage`/committee use. The design plumbs a detected equivocator's index into this existing exclusion set — reusing established machinery rather than building a new punishment/slashing mechanism.

**Explicitly out of scope for this spec:** any new punishment or slashing logic beyond what `conflictGenerators` already does; any change to vote/QC acceptance rules; any change to consensus safety behavior. This is detection + logging + metric + plugging into an existing exclusion list only.

## Files

- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffVotePool.scala` — call `equivocators` on vote accumulation, surface detected offenders.
- Modify: wherever `conflictGenerators` is currently populated for T0 (likely `FinalizationState.scala` or its caller) — add the HotStuff-detected path as an additional source, not a replacement.
- Modify: `infra/monitoring/exporter.py` — new `dcc_hotstuff_equivocations_total` counter.
- Modify: `infra/monitoring/alerts.yml` — new `severity: critical` alert (any occurrence pages).
- Test: new spec proving equivocation on the real (non-DST) `HotStuffVotePool` ingress path is detected, logged, and reaches the exclusion set — following this session's established standard of testing real behavior, not mocks.

## Self-Review

- **No duplication of existing work:** `equivocators`, `noEquivocation`, and `conflictGenerators` all already exist and are individually proven — this spec's entire job is wiring them together, not building new detection logic.
- **Scope discipline:** explicitly excludes any new consensus rule change or punishment mechanism — matches this project's established "nothing ships without a real reason" discipline from the prior consensus-bug investigation reference.
- **Real precondition for later work:** this is a named, deliberate prerequisite for the harder T2-authoritative-enforcement work that follows, not an isolated nice-to-have.
