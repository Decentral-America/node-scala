# Testnet Final Implementation Plan

> **SUPERSEDED 2026-09-02.** Superseded by `docs/superpowers/plans/2026-09-02-testnet-final-source.md`,
> which deletes features 29 (`HotStuffEquivocationEvidence`) and 30 (`BlsCryptoV2`) entirely rather than
> keeping them as activation gates, registers upstream feature 26 (`AdjustedBlockRewardDistribution`)
> with its reward-economics logic ported faithfully, and fixes the real state-hash non-determinism
> defect this plan's Task F wipe-and-relaunch premise did not address. Kept here for history/citation
> only — do not execute this file.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring node-scala's testnet to its final, long-term state: every real upstream-sync gap closed, every real DCC-authored bug fixed, both dangerous/unjustified features deleted, zero new features added, then wipe testnet's chain and relaunch clean. This is the frozen baseline that stagenet and eventually mainnet build from.

**Architecture:** Same file-by-file port approach as the superseded plan for the real upstream-sync gaps (Tasks 2-14, 17-22, 24 below — content carried forward unchanged from `2026-08-23-upstream-sync-port.md`, cite that file for full step detail where noted, it remains accurate). New work: delete features 28 and 30 entirely (code + feature-list entry), fix the finalization-state rollback bug (new, most significant new task), fix the two independent halves of the `BlockchainContext` cache-key bug, then wipe and relaunch.

**Tech Stack:** Scala 3, sbt, ScalaTest, RocksDB, `io.decentralchain.protobuf`.

## Global Constraints

- Do all work on a new branch off `dev`, inside an isolated git worktree (Task 1).
- **No new blockchain features ship from this plan.** Confirmed via exhaustive investigation (see `/Users/jourlez/Documents/Code/Blockchain/CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` for the full reasoning): the height-3325 bug's fix is "wipe testnet's disposable chain," not a feature gate — neither Waves' nor DCC's real mainnet has ever run `CommitToGenerationTransaction`, so there is no real history anywhere that a gate would protect.
- **Files confirmed DCC-ahead-of-upstream — do NOT regress:** `account/Recipient.scala` (correct chainId checksum, upstream has the bug), `state/InvokeScriptResult.scala` (`returnValue` field), `lang/.../estimator/ScriptEstimatorV1.scala` (real exhaustive handling vs upstream's `???`), `network/LegacyFrameCodec.scala` (`length >= 0` guard).
- Build/verify after every task: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile"`.
- **Before Task 1**, resolve the pre-existing compile break in the shared checkout (`node/src/main/scala/io/decentralchain/protobuf/block/PBFinalizationVotings.scala:30`, missing the `hotstuffConflicts` 5th argument added by protobuf-schemas 1.6.5) — confirm with the user whether this is their own in-progress work before touching it; two independent investigation agents hit this same break on 2026-08-29/30.

---

### Task 1: Isolated worktree + branch setup

Same as superseded plan Task 1 (`2026-08-23-upstream-sync-port.md`) — unchanged, still correct. Create worktree, confirm clean baseline build before any changes.

---

### Tasks 2–14, 17–22, 24: Real missed-upstream-sync ports and bug fixes — carried forward unchanged

Every one of these was independently confirmed real via a two-pass, commit-list-closed audit (sampled 214 files by hand, closed against the exact file lists of the 3 source commits, 123 files, zero gaps left unaccounted for). None of them relate to the height-3325 root cause or to features 28/29/30/31 — they stand on their own merits. **Full step-by-step detail is in `docs/superpowers/plans/2026-08-23-upstream-sync-port.md`, Tasks 2–14, 17–22, and 24 — execute those tasks exactly as written there.** Summary for tracking:

- **Task 2** — Port `NgState.scala` (clean replace, zero DCC-specific logic in this file).
- **Task 3** — Port `FinalizationState.scala` + `EndorsementStorage.scala`.
- **Task 4** — Port `BlockEndorser.scala` (merge, preserve DCC HotStuff extensions).
- **Task 5** — Port `CommonGeneratorsApi.scala` + `GeneratorsApiRoute.scala`.
- **Task 6** — `CommitToGenerationRequest.scala` — decision point on DCC's auto-sign path, don't blind-port.
- **Task 7** — Port `Miner.scala` (merge, heaviest task — `referencedBlockchain` refactor, preserve `committedGeneratorsHash`/`blockEndorser`).
- **Task 8** — Fix `appender/package.scala` (`referencedBlockchain` validation gap — real fork/rollback edge case, confirmed NOT the cause of height 3325, still worth fixing for its own sake).
- **Task 9** — Port `BlockchainUpdaterImpl.scala` (rename + `conflictGenerators` receiver fix).
- **Task 10** — Port `RocksDBWriter.scala` rollback fix (`forall`/`exists` bug) + `Keys.scala` refactor.
- **Task 11** — Port `Application.scala` fixes (`BlockEndorser.Disabled` on challenge path, `blacklistOnScoreMismatch`).
- **Task 12** — Port `TransactionFactory.scala` (full rewrite, low consensus risk).
- **Task 13** — Port `api/http/package.scala` + `UtilApp.scala` (lazy `idOrHash`, BLS smoke test).
- **Task 14** — Port `Importer.scala` + `CommonValidation.scala`.
- **Task 17** — Fix `BlockAppender.scala` early-out (same class as Task 8).
- **Task 18** — Fix `FinalizationVoting.scala` real batch aggregation.
- **Task 19** — Fix `EndorsementFilter.scala` miner-double-counting-own-balance bug.
- **Task 20** — Port BLS crypto hardening (`BlsUtils`/`BlsPublicKey`/`BlsSignature`) — **security-relevant, TDD with adversarial tests first, see original task for the exact point-at-infinity test cases.**
- **Task 21** — Fix `PBTransactions.scala` deserialization issues (throw-on-malformed-pubkey, dropped proofs, wrong type).
- **Task 22** — Port missing feature-gated validity rules (`ExchangeTransactionDiff`, `EthereumTransactionDiff`).
- **Task 24** — Should-fix batch: `RxExtensionLoader` peer score-mismatch detection, default-timestamp bug, `LeaseApiRoute` fallback, `RootActorSystem` exit code, `BlockEndorsement` failure reason.

Also carry forward from the superseded plan: **Task 25** (decision-record docs for deliberate DCC divergences — `checkWeakPk` default, `MassTransferTxSerializer` bound, committed-generators state-hash exclusion cross-reference), **Task 25.5** (port `de4a93025b`, confirmed non-consensus Time/NTP refactor, for genuine upstream-latest status), and **Task 25.8** (fix `CancelLeasesToDisabledAliases`'s network-filter inversion, `95fc1cd4f8` — confirmed HIGH severity, will throw an exception the first time any DCC network crosses the SynchronousCalls activation height; do not skip this one, it was nearly dropped from this plan by mistake).

**Explicitly NOT carried forward:** the old plan's Task 25.6 (feature 29 gate), Task 25.9 (feature 31 gate) — both superseded by this plan's Task A below (wipe testnet instead). Task 25.7's investigation is complete and needs no code — folded into Task 25 as a documentation note (state-hash exclusion proven safe by continuous replay evidence; `prevStateHash` claim falsified by a real executed test — both write up as "investigated, no fix needed" in the decision-record doc, cite `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §2–3).

---

### Task A: Delete feature 28 (ModernGroth16Verifier) entirely

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (remove the `ModernGroth16Verifier` entry and its `dict` inclusion)
- Modify: `lang/jvm/src/main/scala/com/decentralchain/lang/Global.scala` (remove `groth16VerifyV2`/`Groth16V2` wiring)
- Modify: `lang/shared/src/main/scala/com/decentralchain/lang/v1/evaluator/FunctionIds.scala` (remove `BLS12_GROTH16_VERIFY_V2 = 802`)
- Modify: `lang/shared/src/main/scala/com/decentralchain/lang/v1/evaluator/ctx/impl/CryptoContext.scala` (remove the `groth16Verify_v2` native registration and its `fixGroth16`-gated branch)
- Modify: `node/src/main/scala/com/decentralchain/transaction/smart/BlockchainContext.scala` (remove `fixGroth16` threading — see Task C, this is where the two tasks intersect)
- Modify: `lang/shared/src/main/scala/com/decentralchain/lang/utils/package.scala`, `node/src/main/scala/com/decentralchain/api/http/utils/UtilsEvaluator.scala` (remove `fixGroth16` references)
- Modify: `infra/node-config/testnet/dcc.conf` (remove `28` from `pre-activated-features`) — separate repo, flag as a follow-up PR there, don't edit from this worktree
- Delete or update: `node/src/main/scala/com/decentralchain/state/diffs/invoke/CachedDAppCTX.scala`'s `fixGroth16`/`ModernGroth16Verifier` cache-key entry

**Why:** confirmed via exhaustive research — real technical wire-format difference from Waves' existing Groth16 verifier (not redundant math), but zero documented use case anywhere in the codebase, no spec doc (the only DCC feature without one), no test coverage on the new function itself, and two of its own justifying code comments are factually false when checked against the real crypto (arkworks-conversion claim, snarkjs/circom claim — both disproven). Currently live (pre-activated height 0) with an open cache-key bug. No real reason found for it to exist. See `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §6.

- [ ] **Step 1:** `git grep -rn "groth16\|Groth16" node/ lang/ --include='*.scala'` — get the complete list of every reference before touching anything, so nothing gets missed.
- [ ] **Step 2:** Remove each reference found, following the file list above as a starting point, not an exhaustive one — trust the grep from Step 1.
- [ ] **Step 3:** Compile: `sbt "node/compile" "lang/compile"`. Expect failures at any remaining call sites — fix each.
- [ ] **Step 4:** Run the full lang + node test suites, confirm nothing depended on this: `sbt "lang/test" "node/testOnly com.decentralchain.transaction.smart.*"`.
- [ ] **Step 5:** Commit: `git commit -m "remove: delete feature 28 (ModernGroth16Verifier) — no documented use case, factually-incorrect justification, open cache-key bug, confirmed dead"`.

---

### Task B: Delete feature 30 (InvokeVersionGating / SC-695) entirely

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (remove `InvokeVersionGating` entry)
- Delete: `node/src/main/scala/com/decentralchain/state/diffs/invoke/InvokeVersionGating.scala`
- Modify: wherever it's wired into validation/fee — `state/diffs/CommonValidation.scala` (activation barrier check), `state/diffs/FeeValidation.scala` (`InvokeExtraFeePerStep`) — remove the gating calls
- Delete: `docs/features/feature-30-sc695-spec.md`
- Modify: `node/tests/src/test/scala/...InvokeScriptTransactionRideV5Suite`-equivalent and `InvokeVersionGatingTest` — remove or leave as historical record per team preference; if removed, confirm no other test depends on `DomainPresets.InvokeVersionGating`.

**Why:** confirmed dangerous via a real executed test, not a code read. This feature requires `InvokeScriptTransaction` V3, which does not exist in either Waves or DCC (`Versioned.maxVersion` caps both at V2 — confirmed directly). Waves designed V3 for a "continuations" feature in 2021, then deleted continuations entirely (`4aa366e5cc`), leaving 5-years-dead disabled test stubs behind. DCC found those stubs and built real enforcement around a transaction version that cannot be constructed. **If activated, this would make every RIDE V5+ smart contract permanently uncallable** — proven by running an actual V1/V2 invocation against a real V5 dApp and confirming it succeeds cleanly today (the sanctioned, only-ever-used production path). See `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §6 and §9.

- [ ] **Step 1:** `git grep -rn "InvokeVersionGating\|SC-695\|InvokeExtraFeePerStep" node/ --include='*.scala'` — full reference list first.
- [ ] **Step 2:** Remove each reference.
- [ ] **Step 3:** Compile: `sbt "node/compile"`.
- [ ] **Step 4:** Run the invoke-script test suite to confirm nothing regresses: `sbt "node/testOnly com.decentralchain.state.diffs.invoke.*"`.
- [ ] **Step 5:** Commit: `git commit -m "remove: delete feature 30 (InvokeVersionGating) — requires a transaction version that does not exist in Waves or DCC; would break every RIDE V5+ dApp if ever activated, confirmed via real invocation test"`.

---

### Task C: Fix the `BlockchainContext` script-cache key — two independent gaps, fix both

**Files:** Modify: `node/src/main/scala/com/decentralchain/transaction/smart/BlockchainContext.scala:60-67` (the cache key construction)

**Background:** the verifier-script cache key is `(ds.stdLibVersion, fixUnicodeFunctions, useNewPowPrecision, fixBigScriptField, ds)` — it omits **both** `fixEcrecover` and `fixGroth16`. Whichever activation-flag combination populates a cache entry first gets served to every later block regardless of the real feature state at that height — a genuine cross-node divergence source, since a node that warmed its cache before an activation height keeps serving stale behavior after it.

- `fixGroth16` — moot after Task A deletes feature 28 entirely; the parameter disappears along with it. No separate fix needed for this half once Task A is done.
- `fixEcrecover` — **independent of Task A, still real, still needs fixing.** This is inherited from Waves (feature 24, `EcrecoverFix`) — Waves has this exact same cache-key omission in their own code (confirmed), but that doesn't make it safe for DCC to leave unfixed; it's a real, live gap regardless of upstream's status.

- [ ] **Step 1:** Confirm Task A is complete first (removes `fixGroth16` from the equation) — do this task after Task A, not before or in parallel.
- [ ] **Step 2:** Add `fixEcrecover` to the cache key tuple in `BlockchainContext.scala`.
- [ ] **Step 3:** Compile and run: `sbt "node/compile" "node/testOnly com.decentralchain.transaction.smart.BlockchainContext*"`.
- [ ] **Step 4 (optional but recommended):** Since this is inherited from Waves and Waves has the same gap, consider whether to report it to them alongside the two bugs in Task D's write-up — same category of finding.
- [ ] **Step 5:** Commit: `git commit -m "fix: add fixEcrecover to BlockchainContext script-cache key — stale-cache cross-node divergence, inherited from upstream Waves"`.

---

### Task D: Fix the finalization-state rollback bug — the most significant new fix in this plan

**MUST run after Tasks 2 and 3, not before or in parallel.** Tasks 2/3 fully replace `NgState.scala` and `FinalizationState.scala` with a fresh port from upstream Waves. The bug this task fixes was proven against Waves' current codebase — the same version being ported in — so it is still present in the freshly-ported files. If this task runs first, Tasks 2/3 will silently overwrite the fix.

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/FinalizationState.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/NgState.scala` (specifically `forgeBlock`, ~lines 206-250)
- Modify: `node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala` (~lines 380-397, the microblock-discard and persist path)
- Test: new spec, `node/tests/src/test/scala/com/decentralchain/finalization/DiscardedMicroBlockFinalizedHeightSpec.scala` (a working draft already exists from the investigation, in a throwaway scratch worktree — port its structure, don't reinvent)

**Background — this is real, confirmed with an executed test, and DCC has zero protection for it (unlike the sibling Waves bug in Task E):**

Waves-authored code, inherited unmodified by DCC (verified via edit history — only cosmetic rebrand/formatting commits touch these files, one unrelated HotStuff-flag addition that doesn't touch this logic). Two intertwined defects:

1. **`FinalizationState.append` advances `finalizedHeight`** (how far back the chain is considered permanently locked in) whenever a microblock's cumulative endorsement voting first satisfies `isParentFinalized`. If that specific microblock later gets discarded (a small fork at the microblock level, the next key block references an earlier microblock instead), **the advanced `finalizedHeight` value never gets rolled back.** `BlockchainUpdaterImpl.scala:395` persists whatever `ng.finalizationState.finalizedHeight` happens to be — the latest across all appended microblocks, including discarded ones — not the value as of the block actually being persisted.
2. **`NgState.forgeBlock` keeps folding `FinalizationVoting.combine(voting, mb.finalizationVoting)` over microblocks that come *after* the one actually being referenced** — i.e., it accumulates voting data from microblocks that are about to be discarded into the forged block's header. This corrupts that block's own signature (proven: `signatureValid()` returns `false` when this happens), which currently causes the node to reject the block outright and stall — **masking** defect (1)'s silent-hash-mismatch potential with a different, also-real stall/liveness bug.

**Proven, real, executed test (`verify = false` path, both arms produce the identical canonical chain, only differ in whether a since-discarded microblock's vote was briefly seen):**
```
WITH discarded finalizing microblock: finalizedHeightAt(3)=Some(2)
WITHOUT it (same canonical chain):    finalizedHeightAt(3)=Some(1)
```
2 vs 1, same chain. Confirmed real cross-node disagreement.

**Fix both together — fixing only one reopens the other, worse:**

- [ ] **Step 1: Write the failing regression tests first**, based on the existing draft (`DiscardedMicroBlockFinalizedHeightSpec.scala`, currently in a scratch worktree — retrieve its structure: `DomainPresets.DeterministicFinality`, 3 committed generators, two arms — one where a finalizing microblock gets discarded by the next key block referencing an earlier microblock, one where that microblock never existed — asserting `finalizedHeightAt` matches between arms). Also add a second assertion covering the `verify = true` path: the forged block's `signatureValid()` must be `true` even when a discarded microblock carried voting data.
- [ ] **Step 2: Run them, confirm both fail** against current code (proves the regression tests actually exercise the bug).
- [ ] **Step 3: Fix `NgState.scala`'s `forgeBlock`** — stop accumulating `finalizationVoting` from microblocks after the referenced one; the branch handling a found/discarded split should carry `voting` through unchanged from the referenced microblock, not keep folding in the discarded ones.
- [ ] **Step 4: Fix the rollback of `finalizedHeight` (and `generatorSet`, `conflictGenerators` — they advance together and must roll back together).** This requires `NgState` to keep a per-`totalBlockId` snapshot of `FinalizationState` (a map alongside the existing `microSnapshots`, populated in `NgState.append`), so that referencing an earlier microblock can look up the finalization state *as of that point*, not just the latest. Update `BlockchainUpdaterImpl.scala:395-397` to read from that per-block snapshot instead of `ng.finalizationState` directly.
- [ ] **Step 5: Run the Step-1 tests again, confirm both now pass.**
- [ ] **Step 6: Run the full finalization test suite** to confirm no regression: `sbt "node/testOnly com.decentralchain.finalization.*"`.
- [ ] **Step 7:** Commit: `git commit -m "fix(consensus): roll back finalizedHeight/generatorSet/conflictGenerators together on discarded microblock; stop forgeBlock accumulating voting past the referenced microblock — real cross-node finalization divergence, confirmed via executed test"`.

---

### Task E: Report the sibling Waves bug (committee state-hash timing) — documentation only, no DCC code change

**Files:** New: `docs/upstream-reports/waves-committee-statehash-timing-bug.md` (or wherever the team tracks external reports)

**Background:** the sibling bug to Task D, but in Waves' own `Caches.scala`/`TxStateSnapshotHashBuilder.scala` — proven via a real executed test built and run **locally against Waves' own codebase**, in isolation, no live network. Two identical final chains produced two different block-state-hashes, differing only in whether a since-discarded microblock's committee-list edit was briefly visible. DCC is not exposed (a separate, earlier, unrelated fix excludes this exact data from DCC's own hash — see `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §9 Bug 1 for the full mechanism). This is Waves' bug to fix, not DCC's — this task is just writing it up properly for external report.

- [ ] **Step 1:** Write up the bug clearly: mechanism, the real test result (two different hashes for the same chain), the exact files/lines in Waves' current codebase, and the fix shape (roll back the committee snapshot the same way everything else already rolls back on microblock discard).
- [ ] **Step 2:** Confirm with the user how they want this reported (GitHub issue on `wavesplatform/Waves`, direct contact, or internal-only record) before actually submitting anything externally — this is a visible, external action, needs explicit sign-off.
- [ ] **Step 3:** Commit the write-up to the repo regardless of external-reporting decision, so it's on record.

---

### Task F: Wipe testnet, relaunch fresh — this is the actual fix for height 3325

**Files:** none (operational task)

**Why this is correct, not a shortcut:** height 3325's root cause is DCC's own PR #53 (`c903a9b9b7`) changing the CommitToGeneration fee-carry rule with no activation gate, applied retroactively to a June-mined block. A gate (feature 29) was the originally-planned fix — **superseded** once it was confirmed that neither Waves' nor DCC's real mainnet has ever activated this feature, meaning there is no real (non-disposable) history anywhere that needs reconciling. Testnet's specific chain is the only place this rule has ever run against real history, and testnet has been wiped before as normal practice. A fresh chain has no "before the fix" history to conflict with — the bug cannot recur.

- [ ] **Step 1:** Confirm all of Tasks 1–E above are merged and the codebase builds clean, full test suite green, before wiping anything.
- [ ] **Step 2:** Back up testnet's current chain data/config (even though it's being discarded, don't destroy without a recoverable copy first, per standard practice for any destructive operation).
- [ ] **Step 3:** Get explicit sign-off before the actual wipe — this is a destructive, externally-visible action (testnet's current state, addresses, ongoing integrations). Confirm timing with the team.
- [ ] **Step 4:** Wipe testnet's RocksDB data, relaunch from a fresh genesis with the final codebase from this plan.
- [ ] **Step 5:** Let it run, confirm blocks are actually being produced, confirm no `InvalidStateHash` or other consensus errors over a real observation window (at least a few hours, ideally past the equivalent of several committee periods so `CommitToGenerationTransaction`s get exercised for real).

---

### Task G: Full verification

**Files:** none (verification only)

- [ ] **Step 1:** Full scoped test suite: `sbt "node/testOnly com.decentralchain.state.* com.decentralchain.database.* com.decentralchain.mining.* com.decentralchain.finalization.* *HotStuff* *History* com.decentralchain.api.http.DebugApiRouteStateHashSpec"`.
- [ ] **Step 2:** Fresh-genesis replay against the newly-relaunched testnet (not the old chain) — confirm no divergence from genesis through current tip, and specifically past the height where the first `CommitToGenerationTransaction` lands post-relaunch.
- [ ] **Step 3:** Specifically re-run Task D's regression tests one more time against the final merged state, to catch any interaction with the other tasks' changes.
- [ ] **Step 4:** If anything fails — per `systematic-debugging`'s Phase 4 rule, do not add ad-hoc fixes on top. Return to Phase 1 evidence-gathering for the new failure before attempting anything.

---

### Task H: Update docs

**Files:**
- Modify: `docs/UPSTREAM.md` §19 (node-scala row) — correct the false "fully synced" claim, reflect real post-port sync status.
- Modify: `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` (workspace root) — mark Tasks A–G's items as done once complete, keep §8 "Open items" and §9 "Waves bug ledger" current.

- [ ] **Step 1:** Update `UPSTREAM.md` per the superseded plan's original Task 27 content.
- [ ] **Step 2:** Update the reference doc's status markers.
- [ ] **Step 3:** Commit both.

---

## Self-Review

- **Spec coverage:** every item from `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md`'s §7 Phase 1 checklist and §9 bug ledger has a task here. Real upstream-sync gaps (Tasks 2–14, 17–22, 24) carried forward from the prior plan, unchanged, still valid. New: feature 28 deletion (Task A), feature 30 deletion (Task B), cache-key fix (Task C), the finalization-rollback bug (Task D, the most significant new item), the Waves bug report (Task E), the testnet wipe (Task F, the actual fix for height 3325), verification (Task G), docs (Task H).
- **Nothing here builds a new feature.** Confirmed against the global constraint — Tasks A and B *remove* features, Task C/D/E/F are code fixes or operational steps, none add a `BlockchainFeature` entry.
- **Ordering matters and is captured:** Task C depends on Task A completing first (fixGroth16 removal). Task D depends on Tasks 2/3 completing first (they replace the exact files Task D patches — found and fixed during this review pass, was missing). Task F depends on everything else merging and passing first. Task G depends on Task F.
- **Placeholder scan:** no TBD/TODO left in any step; every step names exact files and exact mechanism, not "handle appropriately."
- **Correction made during this review:** Task 25.8 (lease-patch network-filter inversion, confirmed HIGH severity) was dropped from the carried-forward summary by mistake — added back explicitly, flagged so it doesn't get skipped during execution.
