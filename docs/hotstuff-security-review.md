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

## Findings — must be enforced by the engine (step 3c) and covered by the external audit

1. **[HIGH — usage contract] Safety assumes QCs are already cryptographically verified.**
   `HotStuffSafety.update`/`safeToVote` operate on `QuorumCertificate` values **without** re-verifying signatures — they trust the caller. The engine (3c) **MUST** run `HotStuffQuorum.verifyQC` on every QC before feeding it to the safety layer, or the safety guarantees are void. Enforce at the call site + add a test that unverified QCs never reach `update`.

2. **[MED-HIGH — engine invariant] Commit trusts the phase, not the chain.**
   `committedBlock` finalizes on `phase == COMMIT` alone. The BFT commit guarantee depends on the engine enforcing full phase progression (prepare → pre-commit → commit for the **same** node across consecutive views). That 3-chain invariant lives in the engine (3c), not this pure module — it must be implemented and adversarially tested (attempt to present a COMMIT QC without the preceding chain).

3. **[MED — liveness, not safety] `formQC` is all-or-nothing.**
   A single invalid or mismatched vote makes `formQC` reject the entire set. A Byzantine voter could thus stall QC formation. The engine should filter to valid, same-target votes and form the QC from those if the quorum is met among them. (Does not affect safety; affects liveness.)

4. **[LOW] Canonical vote-message framing.**
   `voteMessage = view(4) ++ phase(1) ++ blockId ++ height(4)`. Unambiguous because `blockId` is the only variable-width field and is bracketed by fixed-width fields — but this assumes `blockId` length is consistent within a context. If variable-length block ids are ever possible, length-prefix the field.

5. **[INFO] Equivocation → slashing integration.**
   `equivocators` detects same-`(voter,view,phase)` double-signing. Wiring detected equivocators into the feature-25 `conflictGenerators` exclusion (so they lose stake weight) is engine work (3c/4).

## Residual risk / gate
The pure core is small, deterministic, and unit-tested (15 tests across `HotStuffQuorumSpecification` + `HotStuffSafetySpecification`). The **dangerous surface moves to the engine** (3c/4): phase progression, QC verification at call sites, pacemaker/timeout, and live block-production integration — none of which unit tests fully cover. Those require multi-node integration + Byzantine/partition testing (step 5) and an **external audit** before any mainnet enablement.
