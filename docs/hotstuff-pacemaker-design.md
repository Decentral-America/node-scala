# HotStuff Pacemaker / View-Change Design (C1)

**Status:** design proposal for external consensus audit. **Not implemented.** Prepared so the audit is a *review* of a concrete design rather than open-ended research.

**Audience:** external consensus auditors + DCC consensus maintainers.

**Scope:** closes audit finding **C1** — the T2 HotStuff overlay has no BFT safety rule (no persisted `lastVotedHeight`/`lockedQC`, view == height), so on a fork it can vote for conflicting blocks at the same height and two Commit QCs can form. Today this is bounded to the *advisory* API by **C2** (canonical-only reporting + `hotStuffFinalityIsAdvisory`) and the rollback reset (`HotStuffRollbackTrigger`). Making HotStuff a *trustworthy* finality gadget requires the pacemaker below.

---

## 1. Why the current model can't just take a lock

`HotStuffEngine.onBlockApplied` starts a fresh 3-phase round (Prepare→PreCommit→Commit) for **whatever block was just applied**, keyed on **block height as the view number**. There is no independent view counter and no view-change protocol.

The standard HotStuff safety rule is: *once a replica votes in a view, it is "locked" and will not vote for a conflicting block until it observes a QC from a **higher view**.* With `view == height`, there is no higher view to advance to at the same height — so a naive lock would refuse to vote for the legitimately-reorged-to canonical block at height *h*, and **finality would stall after every reorg** (verified against the current control flow). Hence C1 is all-or-nothing: it needs a real pacemaker that decouples *view* from *height*.

---

## 2. Target model (Chained/HotStuff-2 style)

### 2.1 State (persisted per node — survives restart)
- `curView: Long` — monotonically increasing, **independent of height**.
- `lastVotedView: Long` — highest view in which this node cast a Prepare vote.
- `lockedQC: QC` — highest PreCommit QC seen (the "lock").
- `prepareQC: QC` — highest Prepare QC seen (the "high QC").

All four MUST be persisted (RocksDB column family) and restored on startup; otherwise a restart re-enables equivocation.

### 2.2 Leader election per view
- `leader(view) = committee.sortBy(addr)( view mod |committee| )` over the **committed-generator set for the generation period enclosing the current tip** (same set feature-25 already uses, so T0 and T2 agree on membership).
- Deterministic, no external randomness.

### 2.3 Normal path (per view)
1. **Propose** — `leader(curView)` proposes `(view, block)` where `block` extends `prepareQC.block` (the highest known QC), carrying `prepareQC` as justification.
2. **Prepare vote** — a replica votes iff `view > lastVotedView` **and** the proposed block extends `lockedQC.block` (safety rule) **or** the proposal's justification QC has a view `> lockedQC.view` (liveness rule). On voting, set `lastVotedView = view`.
3. **PreCommit** — on a Prepare QC, update `prepareQC`; leader broadcasts PreCommit; replicas vote.
4. **Commit** — on a PreCommit QC, set `lockedQC`; leader broadcasts Commit; replicas vote.
5. **Decide** — on a Commit QC, the block (and its ancestors) is finalized; advance `curView`.

### 2.4 View change (liveness)
- Each view has a timeout (`round-timeout`, already in `HotStuffSettings`). On timeout without a Commit QC, a replica sends `NewView(curView+1, prepareQC)` to the next leader and increments `curView`.
- The new leader waits for `NewView` from ≥2/3 stake, picks the highest `prepareQC` among them as its proposal justification, and proposes in the new view.
- **Height mapping:** a view finalizes the block at the tip it builds on; multiple views may target the same height during contention, but the lock rule guarantees at most one block is committed per height.

### 2.5 Reorg / fork-choice interaction
- HotStuff decides *which* block at a height is final; the node's existing fork-choice (score-based) still selects the canonical chain for application. When a Commit QC decides block *B* at height *h*, the node must treat *h* as irreversible (feed it into `finalizedHeight` gating, same as T0). Reconciling T2-decided finality with T0's `finalizedHeightOrFallback` is a **key audit question** (they must never disagree; recommend T2 only ever *ratifies* what T0 could also finalize, never contradicts it).

---

## 3. Safety & liveness invariants (audit checklist)
1. **No two conflicting Commit QCs at the same height** — from the lock rule + `lastVotedView` monotonicity + ≥2/3 honest stake. *Prove.*
2. **A committed block is never reverted** — once a Commit QC exists, `finalizedHeight` must not drop below it (tie into `lastBlockIds` sync cap).
3. **Liveness under partial synchrony** — after GST, a correct leader in a view with ≥2/3 honest stake produces a Commit QC. *Show view-change makes progress.*
4. **Persistence correctness** — a restarted node never votes in a way that violates (1) using its restored `lastVotedView`/`lockedQC`.
5. **Committee-change safety** — behavior across generation-period boundaries when the committed-generator set changes mid-view.
6. **T0/T2 agreement** — T2 finality never contradicts T0 DeterministicFinality.

---

## 4. Migration & rollout
- Ship behind the existing `hotstuff.enabled` flag; keep it **advisory** (does not gate application) until (a) this design is audited and (b) it has soaked on testnet + stagenet.
- Add a second flag `hotstuff.authoritative` (default false) that, once audited, lets T2 Commit QCs tighten `finalizedHeight`. Flip only post-audit.
- Existing artifacts to keep: `HotStuffRound`, `HotStuffQC` (extend with `view`), `HotStuffVote`, the BLS aggregation, `HotStuffFinalityTracker` (now fed by decided QCs), and `HotStuffRollbackTrigger`.

## 5. Test plan
- Unit: lock-rule truth table; view-change quorum; persistence restore; conflicting-proposal rejection.
- node-it: 4-node happy path (exists); + partition/heal, leader crash mid-view, rapid reorg storm, restart-during-vote. Assert invariants (1)–(4) hold, not just liveness.
- Adversarial (fault-injection node build): equivocating leader, withheld votes, stale-QC replay.

## 6. Effort & sequencing
Roughly: (1) add `view` to QC/vote + persisted safety state; (2) leader election + propose/vote path; (3) view-change/NewView; (4) T0/T2 reconciliation; (5) fault-injection test harness; (6) external audit; (7) staged enablement. Steps 1–5 are implementation+test; step 6 is the external dependency that currently blocks trusting T2 as finality.
