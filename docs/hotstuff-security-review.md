# T2 HotStuff — Internal Security Review (step 3a/3b core)

> **Scope:** `consensus/hotstuff/HotStuffQuorum.scala` (QC formation/verification) and
> `consensus/hotstuff/HotStuffSafety.scala` (vote/lock/commit rules) on branch `feature/hotstuff-t2`.
> **Reviewer:** internal (automated) adversarial code review. **Date:** 2026-07-08.
>
> ⚠️ **This is an internal review, NOT an external third-party audit.** It reduces risk and documents
> the invariants and residual gaps, but a real external consensus audit remains **required** before
> `dcc.hotstuff.enabled` is ever set true on mainnet. Everything below is gated OFF by default and
> changes no behaviour today.

## Verified — sound

| Area | Finding |
|------|---------|
| **BLS rogue-key defense** | Proof-of-possession **is verified on-chain**: `CommitToGenerationTransactionDiff.scala:22` requires `commitmentSignature` to be a valid BLS sig over `endorserPublicKey ++ generationPeriodStart` under `endorserPublicKey`. This binds each registered BLS key to a holder who proves possession, defeating rogue-key forgery of the fast-aggregate `verifyAgg`. PoP message also binds the period (prevents cross-period replay). Duplicate BLS keys are rejected (line 28). |
| **Quorum threshold** | `HotStuffQuorum.hasQuorum` reuses the proven feature-25 rule `endorsedBalance*3 ≥ totalBalance*2` (≥2/3 by **stake**, not vote count), over `BigInt` (no overflow, no fractional edge). |
| **QC verification** | `verifyQC` requires all signers ∈ committee, re-checks the 2/3 stake quorum, and verifies the aggregate against the signers' keys over the canonical message. Forged aggregate + below-quorum QCs are rejected (unit-tested). |
| **Safety rule** | `safeToVote` implements the canonical HotStuff rule: vote iff `view > lastVotedView` AND (node extends `lockedQC` branch [safety] OR justify-QC newer than `lockedQC` [liveness]). Adversarially tested: **refuses** a conflicting branch with a stale justify; refuses double/regressive votes. |
| **Lock rule** | `update` locks only on a higher-view `PRE_COMMIT` QC and never regresses (monotonic in view). `prepareQC` tracks the highest-view QC. |

## Findings — engine status (step 3c now implemented) + external-audit focus

> **Update 2026-07-11:** the engine (`HotStuffEngine`), vote pool (`HotStuffVotePool`), coordinator and
> shell (`HotStuffCoordinator`/`NodeHotStuffEffects`) now exist and were built to close findings 1–3.
> Status below reflects the current `dev` code. The external audit must still independently confirm
> them on a live multi-node network — the dangerous surface is runtime, not the pure modules.

1. **[HIGH — usage contract] Safety assumes QCs are already cryptographically verified.**
   ✅ **Addressed.** `HotStuffEngine.onQC` runs `HotStuffQuorum.verifyQC(qc, committee)` and only a
   `true` result reaches `HotStuffSafety.update` (`HotStuffEngine.scala:44`); `onProposal` likewise
   `verifyQC`-checks `proposal.justify` before it can advance safety state (`:70`). **Audit focus:**
   confirm there is no path to `update`/`committedBlock` that bypasses `verifyQC`.

2. **[MED-HIGH — design decision, verify the assumption] Commit trusts the verified COMMIT QC, not a
   receiver-side chain replay.**
   ✅ **Addressed by design (standard HotStuff).** `onQC` commits only on a cryptographically-verified
   `COMMIT`-phase QC for a strictly higher block (`HotStuffEngine.scala:43–54`, guard
   `qc.blockHeight > committedHeight`). A valid COMMIT QC already proves ≥2/3 stake voted COMMIT, which
   the locking rule only permits after the pre-commit/prepare chain — so re-deriving the chain at the
   receiver is unnecessary and would break catch-up. Safety therefore rests on two things the auditor
   should confirm rather than on receiver chain-replay: (a) the **honest-≥2/3-stake** assumption, and
   (b) the **voting/lock rules** (`safeToVote` + monotonic lock) that stop an honest node contributing
   COMMIT votes to conflicting branches. Covered today by: `verifyQC` (forged/below-quorum rejected),
   the height-monotonic commit guard (test *"not re-commit a lower/equal height"*), and the adversarial
   `safeToVote`/lock tests. **Audit focus:** the safety proof under those two assumptions, not a missing
   chain-replay check.

3. **[MED — liveness, not safety] `formQC` is all-or-nothing.**
   ✅ **Addressed.** `HotStuffVotePool.onVote` drops invalid votes on ingress (`verifyVote` gate,
   `HotStuffVotePool.scala:26`) and per-voter-dedupes, so `formQC` only ever sees valid same-target
   votes and forms a QC once the 2/3-stake quorum is met among them — a Byzantine voter can no longer
   stall formation.

4. **[LOW] Canonical vote-message framing.**
   `voteMessage = view(4) ++ phase(1) ++ blockId ++ height(4)`. Unambiguous because `blockId` is the only variable-width field and is bracketed by fixed-width fields — but this assumes `blockId` length is consistent within a context. If variable-length block ids are ever possible, length-prefix the field.

5. **[INFO] Equivocation → slashing integration.**
   `equivocators` detects same-`(voter,view,phase)` double-signing. Wiring detected equivocators into the feature-25 `conflictGenerators` exclusion (so they lose stake weight) is engine work (3c/4).

## Residual risk / gate
The pure core is small, deterministic, and unit-tested (15 tests across `HotStuffQuorumSpecification` + `HotStuffSafetySpecification`). The **dangerous surface moves to the engine** (3c/4): phase progression, QC verification at call sites, pacemaker/timeout, and live block-production integration — none of which unit tests fully cover. Those require multi-node integration + Byzantine/partition testing (step 5) and an **external audit** before any mainnet enablement.
