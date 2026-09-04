> **SUPERSEDED 2026-08-30.** Replaced in full by `docs/superpowers/plans/2026-08-30-testnet-final.md`, which carries forward every still-valid task from this plan (the real upstream-sync ports and bug fixes), removes everything based on the since-falsified "feature 29/31 gate" premise, and adds the newly-confirmed items (delete features 28/30, fix the finalization-rollback bug). Do not execute this file — read the new one. Kept here for history/citation only.

# Upstream Waves Sync Port (PRs #4034, #4037, #4043) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port every genuinely missing upstream `wavesplatform/Waves` commit (PR #4034, #4037, #4043 — everything up to and including tag `v1.6.3` / `c1fcc5e0b58cba6743e2d636da81574291c8068c`, confirmed to be the actual current relevant upstream content, nothing newer on `origin/version-1.6.x` touches these files) into `node-scala`, without losing any DCC-specific feature (HotStuff/T2 finality, CommitToGenerationTransaction, BLS aggregation, DCC crypto hardening, T0 finality plumbing) — AND separately fix the actual, confirmed root cause of the height-3325 InvalidStateHash bug, which is a DCC-only issue (Task 25.6), not an upstream-sync gap. Then verify via full test suite + live fresh-genesis testnet replay past block height 3325, and correct `docs/UPSTREAM.md` §19 to state the real, post-port sync status.

**Correction after initial investigation:** this plan originally assumed (Task 8) that a missed upstream fix (`appendKeyBlock` validating against the live chain tip instead of the block's own referenced state) was "the top suspect" for height 3325. Four separate, real, code-execution experiments falsified that and three other hypotheses. The actual root cause, found afterward and confirmed with a real bidirectional test matching the live chain's balance discrepancy to the exact integer: DCC's own PR #53 (2026-08-07) shipped a consensus rule change (excluding CommitToGenerationTransaction fees from NG carry) with no activation-height gate, so replaying a block mined before that fix existed (2026-06-28) produces a different hash than what's permanently on-chain. This is fixed in Task 25.6, added after the original 25 tasks. Task 8's appender fix is still real and worth doing (a genuine fork/rollback edge case from missed upstream PR #4034) — it's just not connected to height 3325 anymore.

**Audit completeness note (added after a second, corrected audit pass):** the original 16-task version of this plan was based on an audit that only scanned `node/src/main`, had a protobuf-namespace mapping bug, and a directory-rename mapping bug that hid an entire RIDE-language-binding cluster. All three were found and fixed; the fix was verified by pulling the exact file lists of `693484d4ec` (#4034, 41 non-test files), `52acd9d237` (#4037, 79 files), `fa54286c97` (#4043, 15 files) directly from upstream and cross-checking every one against DCC — full closure, not sampling. Tasks 17–25 below are the additional real gaps that second pass found. Tasks 2–14 (the original port tasks) are unchanged and still required.

**Architecture:** File-by-file port, each task pulls the target upstream commit's version of one file via `git show`, re-applies it against DCC's current file, explicitly re-inserts every DCC-specific block called out below (verified present via prior audit — do not delete these), rebrands (`com.wavesplatform`→`com.decentralchain`, `Waves`/`WAVES`/`waves`→`Dcc`/`DCC`/`dcc` where it's genuinely a rebrand token, not a DCC-specific identifier), and compiles before moving to the next file. Order matters: `NgState.scala` and `FinalizationState.scala` must land before `BlockchainUpdaterImpl.scala` and `Miner.scala` (which consume their new APIs); `TransactionFactory.scala` must land before `api/http/package.scala` (which references its `parseRequest`).

**Tech Stack:** Scala 3 (node-scala / Waves fork), sbt, ScalaTest, RocksDB, gRPC/protobuf (`io.decentralchain.protobuf` — note: NOT `com.decentralchain.protobuf`, that namespace doesn't exist in this repo).

## Global Constraints

- Never delete or regress a DCC-original feature while porting. Each task below has an explicit "DCC-specific — MUST preserve" list drawn from the completed audit. If a step would remove something not on that list but that turns out to be load-bearing, stop and flag it — don't guess.
- Reference upstream commit for all "port from upstream" steps, unless stated otherwise: work at `c1fcc5e0b58cba6743e2d636da81574291c8068c` in `/Users/jourlez/Documents/Code/Blockchain/Legacy/Waves/Waves` (this already contains #4034/#4037/#4043's content — no need to check out three separate commits).
- DCC repo: `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala`. Do all work on a new branch off `dev`, inside an isolated git worktree (Task 1) — this is core consensus code for a live chain, isolate it.
- Package/name mapping used throughout: `com.wavesplatform` → `com.decentralchain`; `WavesSettings`/`WavesEnvironment` → `DCCSettings`/`DCCEnvironment`; `Asset.Waves` → `Asset.Dcc`; `wavesFee`/`wavesBalance` → `dccFee`/`dccBalance`; `DepositInWavelets` → `DepositInDcclets`; protobuf classes live under `io.decentralchain.protobuf`, not `com.decentralchain.protobuf`.
- Build/verify command after every task: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile"` — must succeed before moving to the next task.
- Full scoped test suite (run before the final replay, per the precedent already established in PR #53): `sbt "node/testOnly com.decentralchain.state.* com.decentralchain.database.* com.decentralchain.mining.* *HotStuff* *Finaliz* *History* com.decentralchain.api.http.DebugApiRouteStateHashSpec"`.
- **Files confirmed DCC-ahead-of-upstream — do NOT regress these anywhere in this plan:** `account/Recipient.scala` (DCC computes the address checksum over the passed `chainId`; upstream hardcodes `AddressScheme.current.chainId`, a real upstream bug for foreign-chain addresses — keep DCC's version), `state/InvokeScriptResult.scala` (DCC's `returnValue: Option[EVALUATED]` field; upstream still has `// XXX need return value processing`), `lang/.../estimator/ScriptEstimatorV1.scala` (DCC replaced upstream's `case _ => ???` with real exhaustive handling), `network/LegacyFrameCodec.scala` (DCC adds a `length >= 0` guard upstream lacks).

---

### Task 1: Isolated worktree + branch setup

**Files:** none (git operations only)

- [ ] **Step 1: Create the worktree**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git fetch origin dev
git worktree add /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala-upstream-port -b fix/upstream-sync-port origin/dev
```

- [ ] **Step 2: Confirm the worktree builds clean before any changes**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala-upstream-port
sbt "node/compile"
```
Expected: BUILD SUCCESS. If it fails, stop — this is a pre-existing problem unrelated to this plan, investigate separately before proceeding.

- [ ] **Step 3: Do all remaining tasks inside this worktree**, i.e. `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala-upstream-port` for every subsequent step in this plan.

---

### Task 2: Port NgState.scala (full replace — confirmed zero DCC-specific logic in this file)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/NgState.scala`
- Test: existing `node/src/test/scala/com/decentralchain/state/NgStateTest.scala` (or wherever the current suite lives — `git grep -l NgState node/src/test` to confirm the exact path first)

**Interfaces:**
- Produces: `NgState.BlockData` case class (replaces DCC's `CachedMicroDiff`/`MicroBlockInfo`), `NgState.LiquidBlock`, `liquidBlockOf(id): Option[LiquidBlock]`, `createTotalBlockId` (replaces DCC's `createBlockId`), `internalCaches.liquidBlocks/forgedBlocks/bestBlock` (replaces DCC's `blockSnapshotCache/forgedBlockCache/bestBlockCache`), `mergeToLiquid`. Every downstream file in this plan that calls the old names must be updated to the new ones (Task 4 handles `BlockchainUpdaterImpl.scala`, the only other consumer per the audit).
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Confirm current test baseline passes**

```bash
sbt "node/testOnly com.decentralchain.state.NgStateTest"
```
Record pass count.

- [ ] **Step 2: Pull upstream's file verbatim as a reference**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/NgState.scala > /tmp/upstream_ngstate.scala
```

- [ ] **Step 3: Replace DCC's file with the rebranded upstream version**

Apply this rebrand to `/tmp/upstream_ngstate.scala` and write the result to `node/src/main/scala/com/decentralchain/state/NgState.scala`:
```bash
sed -e 's/com\.wavesplatform/com.decentralchain/g' \
    -e 's/package com\.decentralchain\.state/package com.decentralchain.state/' \
    /tmp/upstream_ngstate.scala > node/src/main/scala/com/decentralchain/state/NgState.scala
```
Then open the file and hand-check for any remaining bare `Waves`/`waves` tokens the sed missed (there should be none — this file has no DCC-specific business logic per the audit, it's a pure rebrand target).

- [ ] **Step 4: Compile**

```bash
sbt "node/compile"
```
Expected: fails here, listing every call site in the repo still using the old API (`snapshotOf`, `createBlockId`, `blockSnapshotCache`, etc.) — this is expected and is the exact list Task 4 fixes. Do not fix those call sites in this task; just confirm the failure list matches `BlockchainUpdaterImpl.scala` (and nothing unexpected).

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/state/NgState.scala
git commit -m "port: NgState.scala from upstream PR #4034 (693484d4ec)"
```

---

### Task 3: Port FinalizationState.scala and EndorsementStorage.scala (DeterministicFinality cluster, part 1)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/FinalizationState.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala`
- Test: `git grep -l "FinalizationState\|EndorsementStorage" node/src/test -- '*.scala'` to find existing suites.

**Interfaces:**
- Produces: `FinalizationState.accFinalizationVoting: Option[FinalizationVoting]` (replaces DCC's `finalizationVoting: Map[BlockId, FinalizationVoting]`), `FinalizationState.notActivated(...)`, `append(...): (FinalizationState, Option[FinalizationVoting], Height)` (new return shape — was `FinalizationState` alone). `EndorsementStorage.createVoting(...): Either[String, FinalizationVoting]` (was unconditional, no error path).
- Consumes: nothing new from Task 2 directly, but is part of the same upstream PR — land together to keep the feature internally consistent before Task 5 (BlockEndorser.scala) and Task 8 (appender) start consuming the new `append` signature.

**DCC-specific — MUST preserve (confirmed present, not upstream):** none identified in these two files by the audit beyond the pre-#4034 baseline itself — both files' current DCC content is upstream-derived. No DCC-only additions to protect here. Full replace + rebrand is safe, same as Task 2.

- [ ] **Step 1: Pull upstream references**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/FinalizationState.scala > /tmp/upstream_finalizationstate.scala
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/EndorsementStorage.scala > /tmp/upstream_endorsementstorage.scala
```

- [ ] **Step 2: Rebrand and write**

```bash
sed 's/com\.wavesplatform/com.decentralchain/g' /tmp/upstream_finalizationstate.scala > node/src/main/scala/com/decentralchain/state/FinalizationState.scala
sed 's/com\.wavesplatform/com.decentralchain/g' /tmp/upstream_endorsementstorage.scala > node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala
```

- [ ] **Step 3: Hand-verify the two specific fixes the audit called out are present in the new EndorsementStorage.scala:**
  - `Either.raiseWhen(msg.endorserIndex == filter.miner.toInt)("Miner can't sent endorsements")` — rejects an endorsement from the miner's own index.
  - `createVoting` returns `Either[String, FinalizationVoting]` and builds the aggregate via `withValid(chosenValid, sigs)`, not a one-index-at-a-time fold with no error path.
  - Uses `pk.verify(...)`, not `sig.verifyBasic(...)`.

  If any of these three are absent from what you just wrote, the upstream file at `c1fcc5e0` differs from what the audit reported — stop and re-run `git show c1fcc5e0...:...EndorsementStorage.scala` to double check before continuing.

- [ ] **Step 4: Compile** (expect failures in `BlockEndorser.scala`, `appender/package.scala` — addressed in Tasks 5/8)

```bash
sbt "node/compile"
```

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/state/FinalizationState.scala node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala
git commit -m "port: FinalizationState.scala + EndorsementStorage.scala from upstream PR #4034"
```

---

### Task 4: Port BlockEndorser.scala (merge — heavy DCC extension on top of shared base)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/BlockEndorser.scala`

**Interfaces:**
- Consumes: `FinalizationState`/`EndorsementStorage` new APIs from Task 3.
- Produces: unchanged public surface used by `Application.scala` (Task 6) and `appender/package.scala` (Task 8) — DCC's `voteSelf`/`rebroadcast`/`tryCollectSelf`/`enabled` methods keep their existing signatures.

**DCC-specific — MUST preserve (confirmed present, real DCC code, not upstream):**
- `voteSelf`, `rebroadcast`, `tryCollectSelf`, `enabled` methods.
- The second `selfEndorsementStorage` field/instance.
- The `AtomicReference` pending-endorsement state.

This file needs a real merge, not a full replace. Do it manually:

- [ ] **Step 1: Get both versions side by side**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/BlockEndorser.scala > /tmp/upstream_blockendorser.scala
cat node/src/main/scala/com/decentralchain/state/BlockEndorser.scala > /tmp/dcc_blockendorser_before.scala
```

- [ ] **Step 2: Apply upstream's 3 base-layer fixes into DCC's file, keeping every DCC-specific method/field listed above untouched:**
  1. Replace `finalizedHeightAt(endorsedHeight)` + `Blockchain.finalizedHeightOrFallback(votingHeight, …)` usage with upstream's `if endorsedHeight > finalizedHeight` guard — read upstream's exact conditional in `/tmp/upstream_blockendorser.scala` and reproduce it, "Always endorse with the latest finalized block."
  2. Add the `if minerIndex >= 0` guard upstream added before using `minerIndex`.
  3. Replace `GeneratorIndex.checked(minerIndex)` (Option-based) with `GeneratorIndex(minerIndex)` + an explicit `isMiner` boolean flag threaded through the same way upstream does it — check every call site of the old `GeneratorIndex.checked` in this file and update them to use the new flag.

- [ ] **Step 3: Compile**

```bash
sbt "node/compile"
```

- [ ] **Step 4: Run the existing BlockEndorser test suite**

```bash
git grep -l BlockEndorser node/src/test -- '*.scala'
sbt "node/testOnly <suite found above>"
```
Expected: all pass. If DCC-specific tests (voteSelf/rebroadcast) fail, you likely broke the DCC extension while merging — revert and redo Step 2 more carefully, diffing against `/tmp/dcc_blockendorser_before.scala` line by line.

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/state/BlockEndorser.scala
git commit -m "port: BlockEndorser.scala base-layer fixes from upstream PR #4034, preserve DCC HotStuff extensions"
```

---

### Task 5: Port CommonGeneratorsApi.scala and GeneratorsApiRoute.scala (API surface, low risk)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/api/common/CommonGeneratorsApi.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/GeneratorsApiRoute.scala`

**DCC-specific — MUST preserve:** none identified by audit; DCC's copies were confirmed byte-identical (0 diff) to the pre-#4034 upstream baseline. Safe full replace.

- [ ] **Step 1: Pull and rebrand both files**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/api/common/CommonGeneratorsApi.scala | sed 's/com\.wavesplatform/com.decentralchain/g' > node/src/main/scala/com/decentralchain/api/common/CommonGeneratorsApi.scala
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/api/http/GeneratorsApiRoute.scala | sed 's/com\.wavesplatform/com.decentralchain/g' > node/src/main/scala/com/decentralchain/api/http/GeneratorsApiRoute.scala
```

- [ ] **Step 2: Compile and run the routes' existing test suites**

```bash
sbt "node/compile"
git grep -l "GeneratorsApiRoute\|CommonGeneratorsApi" node/src/test -- '*.scala'
sbt "node/testOnly <suites found above>"
```

- [ ] **Step 3: Commit**

```bash
git add node/src/main/scala/com/decentralchain/api/common/CommonGeneratorsApi.scala node/src/main/scala/com/decentralchain/api/http/GeneratorsApiRoute.scala
git commit -m "port: CommonGeneratorsApi.scala + GeneratorsApiRoute.scala from upstream PR #4034"
```

---

### Task 6: CommitToGenerationRequest.scala — decision point, do NOT blind-port

**Files:**
- Modify (maybe): `node/src/main/scala/com/decentralchain/api/http/requests/CommitToGenerationRequest.scala`

Upstream PR #4037 collapsed this into one `TxBroadcastRequest[CommitToGenerationTransaction]` and **removed** `SignedCommitToGenerationRequest` and the node-side auto-signing path (`mkPopSignature` from a local `BlsKeyPair`). DCC currently keeps the auto-sign path, which may be operationally relied on (it lets a node fill in its own BLS PoP signature rather than requiring the caller to pre-sign).

- [ ] **Step 1: Check whether DCC's auto-sign path is actually used anywhere live**

```bash
git grep -rn "SignedCommitToGenerationRequest\|mkPopSignature" node-it/ node/src/main --include='*.scala'
```
Also check `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala/docs` and testnet deploy configs/scripts for any reference to this endpoint being used operationally (e.g. by a committing-validator script).

- [ ] **Step 2: If nothing depends on it** — port upstream's #4037 consolidation verbatim (same pull/rebrand/compile/commit pattern as Task 5).

- [ ] **Step 3: If something depends on it** — do NOT remove it. Instead, port upstream's `senderPublicKey`/`endorserPublicKey`/`generationPeriodStart`-required shape as an *additional* accepted request format alongside DCC's existing auto-sign path, so both work. Flag this explicitly in the commit message and in the Task 14 docs update as an intentional, permanent DCC divergence (not a remaining gap).

- [ ] **Step 4: Compile, test, commit** (message depends on which branch of Step 2/3 was taken).

---

### Task 7: Port Miner.scala (merge — heaviest task, DCC's committedGeneratorsHash/blockEndorser logic layered on top)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala`

**Interfaces:**
- Consumes: `NgState` new API (Task 2), `FinalizationState`/`EndorsementStorage` (Task 3).
- Produces: `referencedBlockchain(reference)`-pinned read pattern that Task 8 (appender) and Task 9 (BlockchainUpdaterImpl) must match for consistency — this is the actual fix for the construction/validation asymmetry class of bug.

**DCC-specific — MUST preserve (confirmed present, real DCC code):**
- `committedGeneratorsHash` computation and its call sites.
- `blockEndorser` field/wiring.
- Any `FinalizationVoting`-related imports/usage DCC added specifically for its own endorsement flow (distinct from the shared upstream `FinalizationState` plumbing already ported in Task 3).
- The `initSnapshot == StateSnapshot.empty` guard *does* get replaced by upstream's version here (that's the actual fix — the mismatch vs `BlockDiffer.scala:459`'s broader guard is upstream's own known inconsistency, carry it over as-is, don't invent a third variant).

- [ ] **Step 1: Get both versions**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/mining/Miner.scala > /tmp/upstream_miner.scala
cp node/src/main/scala/com/decentralchain/mining/Miner.scala /tmp/dcc_miner_before.scala
```

- [ ] **Step 2: Identify every DCC-only block in the "before" copy**

```bash
grep -n "committedGeneratorsHash\|blockEndorser\|FinalizationVoting" /tmp/dcc_miner_before.scala
```
Note every line number returned — these blocks must all reappear in the merged result.

- [ ] **Step 3: Apply upstream's `referencedBlockchain(reference)` refactor**

In `/tmp/upstream_miner.scala`, find where `val blockchain = blockchainUpdater.referencedBlockchain(reference)` is introduced and everywhere it's subsequently used for `generatingBalance`, `isMiningAllowed`, `isConflict`, `nextBlockVersion`, `lastStateHash`, `isFeatureActivated`, `consensusData`, `blockFeatures` (all at `newBlockHeight = Height(height + 1)`). Rewrite DCC's file so every one of those reads goes through the same `referencedBlockchain`-pinned `blockchain` value instead of the live `blockchainUpdater`/`tempBlockchain` — this is the direct fix for miner/validator read-skew. Keep every block identified in Step 2 exactly where it functionally was, just updated to read off the new pinned `blockchain` value where it previously read off the live one.

- [ ] **Step 4: Update the guard condition** to upstream's narrower `if (initSnapshot == StateSnapshot.empty) prevHash` (was already matching per the audit — confirm it still matches after your edits, don't accidentally widen it).

- [ ] **Step 5: Compile**

```bash
sbt "node/compile"
```

- [ ] **Step 6: Run mining test suite**

```bash
sbt "node/testOnly com.decentralchain.mining.*"
```
Expected: all pass, including any HotStuff/committedGeneratorsHash-specific tests. If those fail, diff against `/tmp/dcc_miner_before.scala` to find what got lost in the merge.

- [ ] **Step 7: Commit**

```bash
git add node/src/main/scala/com/decentralchain/mining/Miner.scala
git commit -m "port: Miner.scala referencedBlockchain refactor from upstream PR #4034, preserve DCC committedGeneratorsHash/blockEndorser"
```

---

### Task 8: Fix appender/package.scala (real fork/rollback edge case — CONFIRMED NOT the cause of height 3325, see Task 25.6 for the real fix)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala`

**Interfaces:**
- Consumes: `BlockchainUpdaterImpl.referencedBlockchain` (already exists in DCC per the audit — `BlockchainUpdaterImpl.scala:203`, `BlockchainUpdater.scala:26` — this task only rewires the call site, doesn't add the method).

**DCC-specific — MUST preserve:** whatever HotStuff-related guards currently exist in `appendKeyBlock`/`validateConflictingEndorsement` beyond the specific pre-#4034 items being fixed below — check `git log --follow -p` on this file for any DCC-authored commit before touching it, so you know what's DCC's vs inherited.

- [ ] **Step 1: Check DCC-authored history on this file first**

```bash
git log --oneline -- node/src/main/scala/com/decentralchain/state/appender/package.scala
```
Note any commit not authored by an upstream Waves dev — read its diff before proceeding, so Step 3 doesn't clobber it.

- [ ] **Step 2: Pull upstream reference**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/appender/package.scala > /tmp/upstream_appender.scala
```

- [ ] **Step 3: Apply the 3 specific fixes identified by the audit into DCC's current file:**
  1. In `appendKeyBlock`: change the validation base from `blockchainUpdater` (current tip) to `blockchainUpdater.referencedBlockchain(block.header.reference)`, matching upstream's comment: *"The block can reference only one of the latest liquid blocks. We have to validate the new block against a state by this reference."* This affects `validateStateHash`, `blockConsensusValidation`, and `responseToSnapshot(…, Height(height + 1))` — all three must use the referenced-blockchain value, not the live one.
  2. In `validateConflictingEndorsement`: restore `Either.raiseWhen(commitedGenerators.isEmpty)("No one committed")` (DCC currently allows an empty committee through).
  3. Change `signatureValid`/`verifyAgg` to return `Either` instead of `Boolean`, matching DCC's existing `BlsUtils.verifyAgg` short-circuit pattern (`if (validEndorsers.isEmpty) Either.unit`) — check `BlsUtils.scala` for the exact `Either` type/error value already in use elsewhere in the codebase and reuse it, don't invent a new error type.

- [ ] **Step 4: Compile**

```bash
sbt "node/compile"
```

- [ ] **Step 5: Run the appender test suite**

```bash
git grep -l "appendKeyBlock\|appendMicroBlock" node/src/test -- '*.scala'
sbt "node/testOnly <suites found>"
```

- [ ] **Step 6: Commit**

```bash
git add node/src/main/scala/com/decentralchain/state/appender/package.scala
git commit -m "fix(consensus): appendKeyBlock validates against referencedBlockchain, not live tip (upstream PR #4034)"
```

---

### Task 9: Port BlockchainUpdaterImpl.scala (mechanical rename + one semantic fix)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala`

**Interfaces:**
- Consumes: `NgState`'s new API from Task 2 (`liquidBlockOf` replacing `snapshotOf`, `createTotalBlockId` replacing `createBlockId`).

**DCC-specific — MUST preserve:** the `raiseHotStuffFinalizedHeight` override — confirmed clean, well-guarded, no upstream equivalent, don't touch it.

- [ ] **Step 1: Pull upstream reference**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/state/BlockchainUpdaterImpl.scala > /tmp/upstream_bui.scala
```

- [ ] **Step 2: Apply the rename + semantic fix to DCC's file:**
  1. `ng.snapshotOf(id)` (6-tuple return) → `ng.liquidBlockOf(id)` (case class return) — update every call site and any destructuring that assumed the 6-tuple shape.
  2. `ng.createBlockId` → `ng.createTotalBlockId`.
  3. `miner.scheduleMining(blockchain = ...)` → whatever upstream renamed that parameter to (`baseBlockchain` per the audit) — check upstream's exact call signature and match it.
  4. Remove the now-resolved `// TODO: case class instead of tuple` comment.
  5. **Semantic fix:** `conflictGenerators = ...fold(ConflictGenerators.empty)(this.conflictGenerators).upTo(newHeight)` → change the receiver from `this` to the `blockchain` value that includes the block being appended (upstream: `SnapshotBlockchain(rocksdb, newBlockSnapshot, block, …)`), matching upstream's `blockchain.conflictGenerators`.

- [ ] **Step 3: Compile**

```bash
sbt "node/compile"
```
This should now be a much shorter failure list — mostly Task 7 (Miner.scala) and Task 8 (appender) call sites, which are already done by this point if you're following task order.

- [ ] **Step 4: Run the state/blockchain-updater test suite**

```bash
sbt "node/testOnly com.decentralchain.state.BlockchainUpdaterImpl* com.decentralchain.state.*ConflictGenerators*"
```

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala
git commit -m "port: BlockchainUpdaterImpl.scala NgState API rename + conflictGenerators receiver fix from upstream PR #4034"
```

---

### Task 10: Port RocksDBWriter.scala rollback fix + Keys.scala cosmetic refactor

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/database/RocksDBWriter.scala`
- Modify: `node/src/main/scala/com/decentralchain/database/Keys.scala`

**DCC-specific — MUST preserve in RocksDBWriter.scala:** `loadHotStuffFloor`/`persistHotStuffFloor`, the `finalizedHeightAt` reader-side `max(recorded, floor)`, post-rollback floor capping in `doRollback`, `DirectBufferPool` usage (already ported separately, unrelated to this fix). **In Keys.scala:** `KeyTag.HotStuffAuthoritativeFloor` + `hotStuffAuthoritativeFloor` key.

- [ ] **Step 1: Fix the `forall`/`exists` bug in RocksDBWriter.scala's `doRollback`**

Find:
```scala
if (finalizedHeight.forall(blockchainHeight < _)) { // Happens only during a force rollback
```
Replace with:
```scala
if (finalizedHeight.exists(blockchainHeight < _)) { // Happens only during a forced rollback. Reset only if we had a finalized height before
```
Do NOT touch the DCC-specific floor-capping logic that sits near this block — only this one conditional changes.

- [ ] **Step 2: Extract the duplicated finalized-height decoder in Keys.scala**

Find the three inlined `Option(bytes).collect { ... }` lambdas used in `finalizedHeight` and `finalizedHeightAt`. Factor them into:
```scala
private def readFinalizedHeight(bytes: Array[Byte]): Option[Height] =
  Option(bytes).collect { /* same body as the existing inlined lambdas */ }
```
and call `readFinalizedHeight(bytes)` from both `finalizedHeight` and `finalizedHeightAt`. Leave `KeyTag.HotStuffAuthoritativeFloor`/`hotStuffAuthoritativeFloor` untouched.

- [ ] **Step 3: Compile and test**

```bash
sbt "node/compile"
sbt "node/testOnly com.decentralchain.database.*"
```

- [ ] **Step 4: Commit**

```bash
git add node/src/main/scala/com/decentralchain/database/RocksDBWriter.scala node/src/main/scala/com/decentralchain/database/Keys.scala
git commit -m "fix(database): forced-rollback finalizedHeight reset used forall instead of exists (upstream PR #4034); extract readFinalizedHeight"
```

---

### Task 11: Port Application.scala fixes

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/Application.scala`

**DCC-specific — MUST preserve:** all HotStuff/T2 wiring and settings plumbing (confirmed ~300 lines of intentional DCC divergence by the audit — this task only touches 3 specific spots, nothing else).

- [ ] **Step 1: Fix the BlockChallengerImpl endorser** — find where `BlockChallengerImpl`'s `appendBlock` is constructed (DCC line ~190 per the audit) and change it to build with `BlockEndorser.Disabled` instead of the live `blockEndorser`, matching upstream's comment: *"BlockEndorser is disabled, because the challenging block doesn't contain finalization voting header."*

- [ ] **Step 2: Wire `blacklistOnScoreMismatch`** — find where `RxExtensionLoader` is constructed and pass `settings.synchronizationSettings.blacklistOnScoreMismatch` into it, matching upstream's `fa54286c97` change.

- [ ] **Step 3 (optional, low priority — do only if time allows):** register `FinalityApiRoute(blockchainUpdater, blocksApi, generatorsApi)` alongside DCC's existing route registrations, porting the route class itself first if choosing to do this (`node/src/main/scala/com/decentralchain/api/http/FinalityApiRoute.scala` + `node/src/main/scala/com/decentralchain/features/api/FinalityStatus.scala` currently don't exist in DCC at all — straight port from `com.wavesplatform.api.http.FinalityApiRoute` / `com.wavesplatform.features.api.FinalityStatus`, rebrand only, no DCC-specific content to preserve since these files don't exist yet). This is observability-only, skip if deprioritized.

- [ ] **Step 4: Compile and test**

```bash
sbt "node/compile"
sbt "node/testOnly com.decentralchain.ApplicationSpec"
```
(confirm the exact test class name first: `git grep -l Application node/src/test -- '*.scala'`)

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/Application.scala
git commit -m "fix: BlockChallengerImpl uses BlockEndorser.Disabled, wire blacklistOnScoreMismatch (upstream PR #4034/#4043)"
```

---

### Task 12: Port TransactionFactory.scala (full rewrite, low consensus risk)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/transaction/TransactionFactory.scala`

**DCC-specific — MUST preserve:** DCC's `commitToGeneration` request-handling behavior — confirmed by the audit to be functionally equivalent to upstream's new `mkPopSignature`-based auto-signing (both derive `commitmentSignature`/`generationPeriodStart`/`endorserPublicKey` server-side), just structurally different. After the rewrite, confirm a `CommitToGeneration` request round-trips correctly (Step 3 below) rather than trying to preserve DCC's old code shape — the goal is equivalent behavior, not identical code.

- [ ] **Step 1: Pull and rebrand upstream's rewrite**

```bash
git show c1fcc5e0b58cba6743e2d636da81574291c8068c:node/src/main/scala/com/wavesplatform/transaction/TransactionFactory.scala | sed 's/com\.wavesplatform/com.decentralchain/g' > node/src/main/scala/com/decentralchain/transaction/TransactionFactory.scala
```

- [ ] **Step 2: Compile** — this is a large structural change (`class TransactionFactory(wallet, time, currentPeriod)` → `object TransactionFactory` with `parseRequest`/`parseRequestAndSign`), expect call-site failures across API route files. Fix each call site to use the new `object`-based API as they surface.

```bash
sbt "node/compile"
```

- [ ] **Step 3: Write/run a test confirming CommitToGeneration signing still works end to end**

```bash
sbt "node/testOnly *CommitToGeneration*"
```
If no such test exists yet, write one in `node/src/test/scala/com/decentralchain/transaction/TransactionFactoryTest.scala` following the existing suite's pattern for other transaction types, asserting `TransactionFactory.parseRequestAndSign` on a `CommitToGenerationRequest` produces a transaction with a valid `commitmentSignature` for a known `BlsKeyPair`.

- [ ] **Step 4: Commit**

```bash
git add node/src/main/scala/com/decentralchain/transaction/TransactionFactory.scala
git commit -m "port: TransactionFactory.scala rewrite from upstream PR #4037"
```

---

### Task 13: Port api/http/package.scala and UtilApp.scala

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/api/http/package.scala`
- Modify: `node/src/main/scala/com/decentralchain/utils/UtilApp.scala`

**Interfaces:**
- Consumes: `TransactionFactory.parseRequest` from Task 12.

**DCC-specific — MUST preserve in UtilApp.scala:** `SignTxWithSk` mode, `--current-height`/`--finality-activation-height` CLI options, `NodeState`, `GenerationPeriod.from` usage.

- [ ] **Step 1: Fix `idOrHash` in api/http/package.scala** — change `PathMatcher1[ByteStr]`-returning eager/throwing version to upstream's lazy `PathMatcher1[Coeval[ByteStr]]` using `Coeval.evalOnce`, so a malformed Base58 segment falls through to 404 instead of throwing. Update every call site of `idOrHash` in route files to force the `Coeval` where the value is actually needed.

- [ ] **Step 2: Adopt `TransactionFactory.parseRequest`/`fromSignedRequest` consolidation** in this file, replacing the hand-rolled 16-case `TransactionType` match, per upstream PR #4037.

- [ ] **Step 3: Port UtilApp.scala's `maybeFindKeyPair` + `signOptions: String | KeyPair` + `PKKeyPair` refactor and the BLS smoke-test coverage** (`BlsKeyPair`, `BlsSignature.agg`, `verifyAgg` in `doSmokeTest`) — DCC's smoke test currently stops after P-256 and never exercises BLS aggregation, the exact primitive the finality/HotStuff path depends on. Preserve DCC's `SignTxWithSk` mode and the two CLI options listed above untouched — add the BLS smoke-test step alongside them, don't replace them.

- [ ] **Step 4: Compile and test**

```bash
sbt "node/compile"
sbt "node/testOnly com.decentralchain.utils.UtilAppTest com.decentralchain.api.http.*"
```

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/api/http/package.scala node/src/main/scala/com/decentralchain/utils/UtilApp.scala
git commit -m "port: lazy idOrHash matcher + TransactionFactory consolidation (PR #4034/#4037); add BLS smoke-test coverage to UtilApp, preserve DCC SignTxWithSk/CLI options"
```

---

### Task 14: Port Importer.scala and CommonValidation.scala

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/Importer.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/CommonValidation.scala`

**DCC-specific — MUST preserve:** `Importer.scala`'s `committedGeneratorsHash = None` in the shutdown pseudo-block header. `CommonValidation.scala`'s `InvokeVersionGating` rejection in the activation barrier.

- [ ] **Step 1: Fix Importer.scala's resource-loading check** — replace the `try { URI.create(file).toURL … } catch { case _: MalformedURLException => FileInputStream }` pattern with upstream's explicit `isRemoteResource(uri)` scheme check (`scheme != null && scheme != "file"`). Also adopt upstream's improved error log: `log.error(s"Error appending block ${blockchain.height + 1}: $ve")`.

- [ ] **Step 2: Port the Ethereum balance-validation branch into CommonValidation.scala** — add `enforceEthTxValidationAfter` to functionality settings (check `node/src/main/resources/*.conf` and the settings case class for where feature-activation-height settings live, follow that existing pattern) and the `toInvokeScriptLike`/`toTransferLike` gated balance check for `EthereumTransaction`, from upstream PR #4043. Extract `validateInvokeScript` as its own method as upstream did, to keep the diff mechanically traceable. Leave `InvokeVersionGating` exactly where it is.

- [ ] **Step 3: Compile and test**

```bash
sbt "node/compile"
sbt "node/testOnly com.decentralchain.state.diffs.CommonValidation* *Importer*"
```

- [ ] **Step 4: Commit**

```bash
git add node/src/main/scala/com/decentralchain/Importer.scala node/src/main/scala/com/decentralchain/state/diffs/CommonValidation.scala
git commit -m "port: Importer.scala isRemoteResource check (PR #4034), CommonValidation.scala Ethereum balance validation (PR #4043)"
```

---

### Task 17: Fix BlockAppender.scala early-out (same class of bug as Task 8, from #4034)

**Files:** Modify: `node/src/main/scala/com/decentralchain/state/appender/BlockAppender.scala`

**DCC-specific — MUST preserve:** the `blockEndorser.voteSelf(gs)` call DCC added in this method — confirmed intentional, don't touch it.

- [ ] **Step 1:** Add upstream's early-out as the *first* check in the append logic: `if (blockchainUpdater.isLastBlockId(newBlock.id())) Right(Ignored) // Cheap to test`, and reduce the later duplicate-check branch to plain `.contains(...)` (drop the redundant `|| isLastBlockId(...)` there — that's what upstream did, moving the check earlier rather than duplicating it). Without this, DCC re-runs `appendKeyBlock`/`appendChallengeBlock` on a block already at the tip instead of returning `Ignored` — directly in the rollback/re-append path implicated in height 3325.
- [ ] **Step 2:** Compile: `sbt "node/compile"`.
- [ ] **Step 3:** Run `sbt "node/testOnly *BlockAppender*"`.
- [ ] **Step 4:** Commit: `git commit -m "fix(consensus): BlockAppender early-out for already-appended tip block (upstream PR #4034)"`.

---

### Task 18: Fix FinalizationVoting.scala aggregation (from #4034)

**Files:** Modify: `node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala`, `node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala` (caller — should already be mostly correct after Task 3, this task finishes wiring it)

- [ ] **Step 1:** Replace DCC's single-endorser `withValid(endorser, signature): FinalizationVoting` (folding via `aggregatedEndorsement.fold(signature)(_.append(signature))`, which cannot fail) with upstream's `withValid(endorserIdxs: Iterable[GeneratorIndex], endorserSigs: Iterable[BlsSignature]): Either[GenericError, FinalizationVoting]`, which aggregates the whole chosen-valid set in one `BlsSignature.agg(Iterable.concat(aggregatedEndorsement, endorserSigs))` call and propagates aggregation failure as `Left`.
- [ ] **Step 2:** Update `EndorsementStorage.createVoting` (from Task 3) to call the new batch `withValid` instead of folding one index/signature at a time, and to propagate a `Left` up instead of silently producing a bad `aggregatedEndorsement`.
- [ ] **Step 3:** Compile and run `sbt "node/testOnly *FinalizationVoting* *EndorsementStorage*"`.
- [ ] **Step 4:** Commit: `git commit -m "fix(consensus): FinalizationVoting batch aggregation with real error path (upstream PR #4034)"`.

---

### Task 19: Fix EndorsementFilter.scala miner double-counting

**Files:** Modify: `node/src/main/scala/com/decentralchain/state/EndorsementFilter.scala`

- [ ] **Step 1:** Change the candidate filter from `if !(conflict.contains(gi) || newConflictIndexes.contains(i))` to upstream's `if !(gi == miner || conflict.contains(gi) || newConflictIndexes.contains(i)) // Miner is included below` — DCC is currently missing the `gi == miner` exclusion, which lets the miner's balance be counted twice toward the `endorsedBalance*3 >= doubledTotalBalance` (2/3) quorum test (once via the seeded `endorsedBalance = BigInt(minerBalance)`, once again if it appears in the candidate set).
- [ ] **Step 2:** Compile and run `sbt "node/testOnly *EndorsementFilter*"`.
- [ ] **Step 3:** Commit: `git commit -m "fix(consensus): EndorsementFilter double-counted miner balance toward finality quorum"`.

---

### Task 20: Port BLS crypto hardening (BlsUtils, BlsPublicKey, BlsSignature) — security-relevant, from #4034

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala`
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsPublicKey.scala`
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsSignature.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/CommitToGenerationTransactionDiff.scala`
- Modify: `node/src/main/scala/com/decentralchain/consensus/GeneratingBalanceProvider.scala`
- Test: write two adversarial tests before touching any code (TDD — this is a security fix)

**DCC-specific — MUST preserve:** `BlsKeyPair.scala`'s additive `verify` default method and the `mkBlsSecretKey`/`mkBlsPublicKey` naming — confirmed clean, not part of this gap.

- [ ] **Step 1: Write the failing adversarial tests first, in `node/src/test/scala/com/decentralchain/crypto/bls/BlsUtilsTest.scala` (create if it doesn't exist):**

```scala
"registering a point-at-infinity BLS public key" should "be rejected" in {
  val infinityKeyBytes = /* the all-zero / identity-element encoding per the blst library's infinity representation — check BlsUtils' constants for the exact 48-byte pattern */ Array.fill(48)(0: Byte)
  BlsPublicKey(infinityKeyBytes) shouldBe a[Left[?, ?]]  // once Step 2 is done; currently this will pass construction, which is the bug
}

"verifyBasic" should "reject when the underlying pairing aggregate does not return BLST_SUCCESS" in {
  // construct a case that forces ctx.aggregate to return a non-SUCCESS code (e.g. mismatched/invalid pairing input per the blst API)
  // and assert BlsUtils.verifyBasic returns a failure — currently it doesn't check the return code at all
}
```
Run them, confirm they FAIL against current code (this proves the gap is real, not theoretical).

- [ ] **Step 2:** Port from upstream `693484d4ec` (sub-commits "Fixes in BLS validation, new tests" and "BLS related code improvements"):
  - `BlsUtils.scala`: add `validatePublicKey` (`in_group()` + `is_inf()` checks), `sanityCheckPublicKey`/`sanityCheckSignature`, empty-set guard in `verifyAgg` (raise `"Empty BLS public key list"` instead of relying on `reduceLeft` throwing), single-pass `aggSig(Iterable)` instead of DCC's pairwise `reduceLeft(BlsUtils.aggSign)`, and change `verifyBasic` to check `ctx.aggregate`'s `BLST_ERROR` return and reject on anything but `BLST_SUCCESS`. Standardize on `Either[String, Unit]` return types (replacing the `Boolean`/`Either[String, Boolean]` mix — a `Right(false)` being treated as success is itself a latent bug).
  - `BlsPublicKey.scala`: constructor calls `BlsUtils.sanityCheckPublicKey`, exposes `validated`.
  - `BlsSignature.scala`: constructor calls `BlsUtils.sanityCheckSignature`, add `BlsSignature.agg`, make `unsafe` actually validate rather than blindly wrap.
  - `CommitToGenerationTransactionDiff.scala`: call `tx.endorserPublicKey.validated` when registering a new committed generator (this is the actual enforcement point — a point-at-infinity key must be rejected here, at registration, not just at verify time).
  - `GeneratingBalanceProvider.scala`: add `minMiningBalance(blockchain, height)`, feature-gated on `SmallerMinimalGeneratingBalance` (`MinimalEffectiveBalanceForGenerator2` if active, else `...Generator1`), and update `CommitToGenerationTransactionDiff.scala`'s balance check to call it instead of hardcoding `MinimalEffectiveBalanceForGenerator2`.
- [ ] **Step 3:** Update all 5 DCC call sites of the old verify signatures to the new `Either[String, Unit]` shape: `HotStuffQuorum.scala`, `BlockEndorsement.scala`, `EndorsementStorage.scala`, `CommitToGenerationTransactionDiff.scala`, `appender/package.scala`.
- [ ] **Step 4:** Run the Step-1 tests, confirm they now PASS.
- [ ] **Step 5:** Compile and run the full BLS/endorsement/finality test suite: `sbt "node/testOnly com.decentralchain.crypto.bls.* *HotStuff* *Endorsement* *CommitToGeneration*"`.
- [ ] **Step 6:** Commit: `git commit -m "fix(security): BLS point-at-infinity/subgroup validation, verifyBasic error-code check, feature-gated minMiningBalance (upstream PR #4034)"`.

---

### Task 21: Fix PBTransactions.scala deserialization issues (from #4034)

**Files:** Modify: `node/src/main/scala/io/decentralchain/protobuf/transaction/PBTransactions.scala`

- [ ] **Step 1:** `CommitToGeneration` case: change `blsPk = BlsPublicKey(...).explicitGet()` (which throws on a malformed inbound protobuf tx — a remotely-triggerable crash) to upstream's for-comprehension form (`blsPk <- BlsPublicKey(...)`) that yields a `ValidationError` instead.
- [ ] **Step 2:** `CreateAlias` unsafe path: change `Proofs(signature)` (drops proofs 2..N) to pass the full `proofs`, matching upstream.
- [ ] **Step 3:** `UpdateAssetInfo`: change the raw `ByteStr` parameter to `IssuedAsset(...)`, matching upstream's type.
- [ ] **Step 4:** Compile and run `sbt "node/testOnly *PBTransactions* *ProtobufCodecs*"` (confirm exact suite name via `git grep -l PBTransactions node/src/test`).
- [ ] **Step 5:** Commit: `git commit -m "fix: PBTransactions deserialization — no throw on malformed BLS pubkey, preserve all proofs, correct UpdateAssetInfo type (upstream PR #4034)"`.

---

### Task 22: Port missing feature-gated validity rules (ExchangeTransactionDiff, EthereumTransactionDiff)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/ExchangeTransactionDiff.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/EthereumTransactionDiff.scala`

**Priority note:** `ExchangeTransactionDiff.scala`'s gap is a **live** behavior divergence — DeterministicFinality is active on testnet (confirmed pre-activated at height 0 in `infra/node-config/testnet/dcc.conf`), so DCC is right now accepting oversized EIP-712 order signatures that a correctly-synced node would reject.

- [ ] **Step 1:** In `ExchangeTransactionDiff.scala`'s `checkOrderPkRecover`, add upstream's `Either.raiseWhen(signature.size > 65 && blockchain.isFeatureActivated(BlockchainFeatures.DeterministicFinality))(GenericError("Invalid order signature format"))`, from upstream commit `fa54286c97` (#4043).
- [ ] **Step 2:** In `EthereumTransactionDiff.scala`, add the two rejections from `fa54286c97`: cross-chain-ID (`tx.longChainId().exists(_ != AddressScheme.current.chainId)`) and non-canonical ECDSA signature (`!tx.ecdsaSignature().isCanonical`), inside the renamed `checkCommonFields` (upstream renamed this from `checkLeadingZeros`).
- [ ] **Step 3:** Compile and run `sbt "node/testOnly *ExchangeTransactionDiff* *EthereumTransactionDiff*"`.
- [ ] **Step 4:** Commit: `git commit -m "fix(consensus): port missing EIP-712 signature-size and Ethereum chain-id/canonical-signature validity rules (upstream PR #4043)"`.

---

### Task 23: Fix BlockchainContext.scala / lang cache-key gaps (feature 28, live since height 0)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/transaction/smart/BlockchainContext.scala`
- Modify: `lang/shared/src/main/scala/com/decentralchain/lang/utils/package.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/utils/UtilsEvaluator.scala`

**Priority note:** feature 28 (`ModernGroth16Verifier`) is pre-activated at height 0 on testnet — this is live, not hypothetical.

- [ ] **Step 1:** In `BlockchainContext.scala`, add both `fixEcrecover` and `fixGroth16` to the `cache.computeIfAbsent` key tuple (currently `(ds.stdLibVersion, fixUnicodeFunctions, useNewPowPrecision, fixBigScriptField, ds)` — neither activation flag is in it). This closes the restart-dependent script-context divergence: currently a node whose cache was warmed pre-activation keeps serving a stale context without `groth16Verify_v2` after activation, while a freshly restarted peer gets the new one, causing divergent verifier-script acceptance between nodes. Note upstream has the identical `fixEcrecover` omission — DCC is fixing a bug DCC widened, not one upstream has already fixed; there is no upstream commit to port for this specific key, write the fix directly.
- [ ] **Step 2:** In `lang/utils/package.scala`'s `lazyContexts` (varies over `useNewPowPrecision`, `fixBigScriptField`, `fixEcRecover` but not `fixGroth16`), add `fixGroth16` as a fourth axis, and update the `CryptoContext.build(Global, version, fixEcRecover)` call to pass `fixGroth16` too (4-arg). Without this, `groth16Verify_v2` is unreachable through the normal script-compile path and has no cost entry in `lazyFunctionCosts` for complexity estimation.
- [ ] **Step 3:** In `UtilsEvaluator.scala:150`, replace the hardcoded `fixGroth16 = false` with the real activation check (same pattern used for the other feature flags in that file), so `/utils/script/evaluate` agrees with consensus once feature 28 is active.
- [ ] **Step 4:** Compile and run `sbt "node/testOnly *BlockchainContext* *UtilsEvaluator*" "lang/testOnly *utils*"`.
- [ ] **Step 5:** Commit: `git commit -m "fix: thread fixGroth16 through script-context cache key and lazy contexts (feature 28 is live since height 0)"`.

---

### Task 24: Should-fix batch — RxExtensionLoader, default timestamp, LeaseApiRoute, RootActorSystem, BlockEndorsement

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/network/RxExtensionLoader.scala`, `node/src/main/scala/com/decentralchain/settings/SynchronizationSettings.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/requests/package.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/leasing/LeaseApiRoute.scala`
- Modify: `node/src/main/scala/com/decentralchain/actor/RootActorSystem.scala`
- Modify: `node/src/main/scala/com/decentralchain/block/BlockEndorsement.scala`

None of these are consensus/state-hash relevant, but all are real and worth fixing in the same pass.

- [ ] **Step 1 (RxExtensionLoader):** Add `blacklistOnScoreMismatch` to `SynchronizationSettings.scala`, and add the missing `case Right(Some(newLocalScore)) if newLocalScore != applying.remoteScore` branch to `RxExtensionLoader.scala` (from upstream `fa54286c97`) that warns and optionally blacklists a peer whose declared remote score doesn't match the delivered blocks' actual score. This is the exact diagnostic that would have surfaced the InvalidStateHash-class divergence sooner — worth prioritizing for its detection value alone. Wire the new setting through in `Application.scala` (already scheduled in Task 11 Step 2 — do this as part of that same edit if Task 11 hasn't run yet, or as a follow-up commit if it has).
- [ ] **Step 2 (timestamp bug):** In `api/http/requests/package.scala`, change `defaultTimestamp = 0L` to `System.currentTimeMillis()`, matching upstream. This is the fallback for `BurnRequest`/`IssueRequest`/`ReissueRequest`/`CommitToGenerationRequest` — currently any request omitting `timestamp` builds a tx stamped at epoch 0, which then gets rejected as stale.
- [ ] **Step 3 (LeaseApiRoute):** Once Task 13 makes `idOrHash`/`TransactionId`/`BlockId` lazy (`PathMatcher1[Coeval[ByteStr]]`), update `LeaseApiRoute.scala` to call `commonAccountApi.leaseInfo(leaseId())` instead of the eager form — it currently throws inside the matcher, suppressing Akka route fallback (including the `~ anyParam("id", …)` alternative in this route). Do this task after Task 13, not before.
- [ ] **Step 4 (RootActorSystem):** Add upstream's `EscalatingStrategy` `SupervisorStrategyConfigurator` and `sys.exit(1)` on root-actor failure — DCC currently exits 0 on a fatal actor crash, which can mask a crash-loop from process supervisors/orchestration.
- [ ] **Step 5 (BlockEndorsement):** Change `signatureValid: Boolean` (via `BlsUtils.verifyBasic`) to `Either[String, Unit]` (via `signature.verifyBasic`), matching upstream and Task 20's new `BlsUtils` signatures — this preserves the failure reason instead of degrading to a flat "BLS signature is invalid" in `EndorsementStorage.verifySig`. Do this as part of Task 20's Step 3 call-site update if Task 20 hasn't run yet.
- [ ] **Step 6:** Compile and run `sbt "node/testOnly *RxExtensionLoader* *LeaseApiRoute* *RootActorSystem* *BlockEndorsement*"`.
- [ ] **Step 7:** Commit: `git commit -m "fix: peer score-mismatch detection, default timestamp, lease route fallback, actor crash exit code, endorsement failure reason (upstream PR #4034/#4043)"`.

---

### Task 25: Decision-record for deliberate DCC consensus divergences (no code change, documentation only)

**Files:** Modify: `docs/mainnet-upgrade-validation.md` (or create `docs/consensus-divergences-from-upstream.md` if that file doesn't already cover this — check first with `git log --all --oneline -- '**/mainnet-upgrade-validation.md'` since it was referenced by a comment in `Caches.scala` but the audit couldn't find it on `dev`)

Two real, intentional-but-undocumented-in-a-central-place divergences surfaced by the audit that are not bugs, but should have a decision record so a future sync attempt doesn't "fix" them by accident:

- [ ] **Step 1:** Document `crypto/package.scala`'s `checkWeakPk` default: DCC defaults `true`, upstream defaults `false` (unchanged since introduction in 2021). This means DCC rejects microblock/MicroBlockInv signatures from blacklisted weak keys that a stock Waves node would accept. State explicitly whether this is intended to stay this way.
- [ ] **Step 2:** Document `MassTransferTxSerializer.scala`'s stricter parse bound (`buf.remaining() >= entryCount * 9` vs upstream's `> entryCount`) as deliberate DoS hardening.
- [ ] **Step 3:** Document `TxStateSnapshotHashBuilder.scala`/`Caches.scala`'s exclusion of `nextCommittedGenerators`/`CommittedGeneratorBalances` from the state hash — this is already covered by PR #53's own commit message and code comments, just make sure it's cross-referenced from wherever this new decision-record doc lives so it isn't rediscovered as a "gap" by a future audit.
- [ ] **Step 4:** Commit: `git commit -m "docs: record deliberate consensus-behavior divergences from upstream Waves"`.

---

### Task 25.5: Port de4a93025b (Jul 7 2026, "Misc Time-related tweaks #4076") — genuinely latest upstream, confirmed non-consensus

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/utils/Time.scala` (full rewrite: drop `getTimestamp()`, add `Time.SystemTime`/`Time.apply(ntpServer)` factory, `ScheduledThreadPoolExecutor` instead of monix `Task` for NTP polling)
- Modify: `node/src/main/scala/com/decentralchain/Application.scala` (constructor param `NTP` → `Time`, `new NTP(...)` → `Time(...)`)
- Modify: `node/src/main/scala/com/decentralchain/Importer.scala`, `node/src/main/scala/com/decentralchain/Exporter.scala` (same `Time(...)` factory swap)
- Modify: `node/src/main/scala/com/decentralchain/api/http/eth/EthRpcRoute.scala`, `node/src/main/scala/com/decentralchain/metrics/Metrics.scala` (`time.getTimestamp()` → `time.correctedTime()`)
- Modify: `node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala` (same swap, in the finalization-height check near `currentFinalizedHeight.foreach`)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/ExtensionAppender.scala` (add `TxValidationError.BlockFromFuture` → don't blacklist peer, just `log.debug`)
- Modify: `node/src/main/scala/com/decentralchain/utils/Schedulers.scala` (`threadFactory` visibility `private`→public, needed by the new `Time.scala`)
- Modify: `node/src/main/scala/com/decentralchain/utx/UtxPoolImpl.scala` (`runCleanupAsync` — add `!transactions.isEmpty &&` guard, pure efficiency, don't schedule cleanup on an empty pool)
- Modify (optional, low-value): `node/src/main/scala/com/decentralchain/Explorer.scala` (upstream's new `"ALC"` debug subcommand — active-lease counter, pure CLI tooling, port only if time allows)
- Modify: `node/src/main/scala/com/decentralchain/utils/generator/BlockchainGeneratorApp.scala` (`FakeTime` test helper keeps a local `getTimestamp()` for compat with anything still calling it)

**Why this is separate from Tasks 2-25:** this commit is the ONLY one on upstream's default branch (`origin/version-1.6.x`) newer than `c1fcc5e0`/v1.6.3 with real logic changes (the other 10 commits since are dependency/CI bumps only — confirmed via `git log --oneline c1fcc5e0..origin/version-1.6.x`). It was checked and confirmed **non-consensus**: every touched file is Time/NTP plumbing, a debug CLI subcommand, or a cleanup-scheduling efficiency guard — nothing here affects state-hash computation, transaction validation, or block diffing, so it does not help fix height 3325. It's included for genuine "at absolute upstream latest" status per an explicit enterprise-completeness requirement, not because it's expected to matter for the bug.

**DCC-specific — MUST preserve:** DCC's `Time`-consuming call sites for HotStuff/T2 (`GenerationPeriod`, endorsement timing) if any exist — check `git grep -rn "\.getTimestamp()\|\.correctedTime()" node/src/main/scala/com/decentralchain` on DCC's current `Time` usage before this task and confirm every call site is updated consistently, not just the ones upstream happened to touch (DCC has additional `Time` consumers upstream doesn't, e.g. anything in `BlockEndorser.scala`/`EndorsementStorage.scala` if they call `time.getTimestamp()`).

- [ ] **Step 1:** `git grep -rn "getTimestamp()" node/src/main/scala/com/decentralchain` — list every call site in DCC (not just the ones upstream's commit touched) before starting, since DCC-added files may call the old API too.
- [ ] **Step 2:** Port `Time.scala` per upstream, rebranded.
- [ ] **Step 3:** Update every call site found in Step 1 to `correctedTime()`, including any DCC-only ones the upstream commit didn't need to touch.
- [ ] **Step 4:** Port the `Application.scala`/`Importer.scala`/`Exporter.scala` constructor/factory swap, the `EthRpcRoute.scala`/`Metrics.scala`/`BlockchainUpdaterImpl.scala` call-site swap, the `ExtensionAppender.scala` `BlockFromFuture` no-blacklist branch, the `Schedulers.scala` visibility change, and the `UtxPoolImpl.scala` cleanup guard.
- [ ] **Step 5:** Compile: `sbt "node/compile"`.
- [ ] **Step 6:** Run the full scoped suite (same command as Task 26) plus anything touching `Time`/NTP: `sbt "node/testOnly *Time* *NTP* *Metrics*"`.
- [ ] **Step 7:** Commit: `git commit -m "port: Time/NTP refactor, ExtensionAppender BlockFromFuture no-blacklist, UtxPoolImpl cleanup guard (upstream #4076, confirmed non-consensus)"`.

---

### Task 25.6: THE REAL FIX for height 3325 — activation-gate the CommitToGeneration carry exclusion (new feature 29)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/BlockDiffer.scala:168` (the exclusion filter)
- Modify: `infra/node-config/testnet/dcc.conf` (pre-activated-features list) — this is in the `infra` repo, not `node-scala`; note it as a follow-up PR there, don't try to edit it from this worktree
- Test: `node/tests/src/test/scala/com/decentralchain/state/diffs/BlockDifferTest.scala` (a draft regression test already exists from the investigation, in a throwaway worktree — port it here for real, with the real activation height wired in)

**Background — this is the actual, confirmed root cause, found and proven after Tasks 1-25 were originally written:**

`BlockDiffer.scala:168`'s `.filterNot(_.isInstanceOf[CommitToGenerationTransaction])` (added by DCC's own PR #53, `c903a9b9b7`, 2026-08-07) excludes CommitToGeneration transaction fees from the normal NG 60% carry-forward. This fix is real and necessary — without it, replay is stuck at height ~1798. But it was shipped with **no activation-height gate**, applying to all history including blocks mined before the fix existed.

**Proven with a real, bidirectional test** (not inference): block 3324 (mined 2026-06-28) is the chain's first-ever CommitToGeneration commitment at/above `sponsoredFeesSwitchHeight` (=3000 exactly on testnet). The live chain's actual persisted balance at height 3325 shows the carry WAS included (old rule, since the exclusion didn't exist yet on 2026-06-28). Current `dev` code excludes it unconditionally (new rule, merged 2026-08-07), producing a different hash for the same historical block. A real test confirmed: with the exclusion active, the delta is 0; with it removed, the delta is exactly 600,000 DCClet — 60% of the one fee involved, matching the live chain's actual balance discrepancy to the integer. Cross-checked against 7 other real historical CommitToGeneration carry transitions — all 7 correctly match current code (they're all below height 3000, where the exclusion is moot for other reasons), confirming this is a real, singular, precisely-located divergence, not a general code defect.

**This is a DCC-only issue.** `CommitToGenerationTransaction`/`DeterministicFinality` are upstream Waves features; this specific carry-exclusion fix has no upstream equivalent at all — it's not a missed port, it's DCC's own unfinished work.

**DCC-specific — MUST preserve:** the exclusion logic itself, unchanged — this task only adds a height gate around it, does not alter what the new rule does.

- [ ] **Step 1: Find the real activation height — do NOT use the git merge date as a stand-in.** The relevant number is when the fix actually started running live on testnet (deploy date, not commit date — they can differ by hours/days). Check `infra` repo's deploy history/CI logs for when the `node-scala` image containing commit `c903a9b9b7` (or the PR #53 merge) was actually rolled out to the testnet nodes. Cross-check against the live chain: scan blocks after height 3325 for the next CommitToGeneration carry transition and confirm whether it follows the old or new rule — the exact height where it flips from old-rule to new-rule is the real activation point (± the deploy lag). Use the same live-node balance-history technique already validated in this investigation (`GET /debug/balances/history/{address}` with the node's API key, cross-referenced against `GET /blocks/at/{height}`).

- [ ] **Step 2: Add feature 29** to `BlockchainFeature.scala`, following the exact pattern of features 25/28/30:

```scala
val CommitToGenerationCarryExclusion = BlockchainFeature(29, "Exclude CommitToGenerationTransaction fee from NG carry")
```
Add it to `dict`/`implemented` the same way 28 and 30 are added (check the exact lines — `dict` is a `Seq`, confirm insertion doesn't disturb existing ordering-dependent logic elsewhere, though feature IDs are independent so this should be additive-only).

- [ ] **Step 3: Gate the exclusion in `BlockDiffer.scala`:**

```scala
val excludeCommitToGenerationFromCarry = blockchain.isFeatureActivated(BlockchainFeatures.CommitToGenerationCarryExclusion, heightWithNewBlock)
pb.transactionData
  .filterNot(tx => excludeCommitToGenerationFromCarry && tx.isInstanceOf[CommitToGenerationTransaction])
  .map { t => ... }  // rest unchanged
```
(Adjust exact variable names to match the surrounding code — read the current function signature first, don't paste blind.)

- [ ] **Step 4: Write the permanent regression test**, pinning both eras: a commit-block-carry-transition below the activation height must show the OLD (carry included) behavior; one at/above it must show the NEW (excluded) behavior. Base this on the draft test already written during the investigation (`"the carry exclusion is a no-op below sponsoredFeesSwitchHeight and consensus-changing above it"` in a throwaway worktree's `BlockDifferTest.scala`) — port its structure, but add the real feature-29 gate rather than testing the unconditional exclusion.

- [ ] **Step 5: Compile and run the targeted test**

```bash
sbt "node/compile"
sbt "node/testOnly com.decentralchain.state.diffs.BlockDifferTest"
```

- [ ] **Step 6: Pre-activate feature 29 on testnet** — add it to `infra/node-config/testnet/dcc.conf`'s `pre-activated-features` map at height 0 IF testnet is being wiped/relaunched fresh (per the team's stated tolerance for resetting testnet), so the activation-height logic is exercised correctly going forward. If testnet is NOT being wiped, this needs the real historical activation height from Step 1 instead of height 0 — using height 0 on a chain that already has history before the real fix's live-deploy date would reintroduce exactly this bug for that in-between window.

- [ ] **Step 7: Commit**

```bash
git add node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala node/src/main/scala/com/decentralchain/state/diffs/BlockDiffer.scala node/tests/src/test/scala/com/decentralchain/state/diffs/BlockDifferTest.scala
git commit -m "fix(consensus): activation-gate CommitToGeneration carry exclusion as feature 29 — root cause of height-3325 InvalidStateHash (PR #53 shipped without a height gate)"
```

---

### Task 25.7: Fix the other two ungated changes in PR #53 (state-hash exclusion + prevStateHash source)

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/state/TxStateSnapshotHashBuilder.scala`
- Modify: `node/src/main/scala/com/decentralchain/database/Caches.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/diffs/BlockDiffer.scala` (the `prevStateHash` line, separate from Task 25.6's carry fix)

**Background:** the full-history audit that found Task 25.6's fix also found `c903a9b9b7` contains two MORE ungated consensus changes, both real, neither yet confirmed as actually causing a live divergence (unlike the carry-fee one, which is proven) — but both are exactly the same class of risk and need the same treatment before any stagenet handoff:

1. **State-hash exclusion of committee data**: `TxStateSnapshotHashBuilder` stopped folding `snapshot.nextCommittedGenerators` into the hash, and `Caches` stopped calling `addNextCommittedGenerator`/`addCommittedGeneratorBalances` — unconditionally, for all heights. This is confirmed *intentional, correct design* per the team's own prior audit (see `[[project_committed_generators_statehash]]` — do NOT re-include this data, it would reintroduce a worse chain-switch-divergence problem). But "correct design" and "correctly gated" are different things: it's still ungated, meaning it applies retroactively. Whether this specific one needs a gate depends on whether any *already-mined* block was hashed under the OLD (including) rule — check this the same way Task 25.6's root cause was found: cross-reference real historical CommitToGeneration blocks' state hashes against what each rule would produce, before deciding whether this needs its own feature gate or can ship as pure go-forward-only (if no historical block was ever mined under the old rule, there's nothing to retroactively break, and no gate is needed).
2. **`prevStateHash` source change** — RESOLVED, no fix needed. `maybePrevBlock.flatMap(_.header.stateHash)` → `blockchain.lastStateHash(Some(block.header.reference))`. The commit message claimed these diverge under live microblocks; this was tested for real (a chain with 2 real live microblocks, both formulas compared directly, then the next key block actually forged on top) and FALSIFIED — both formulas resolve through the same underlying liquid-block snapshot (`ng.snapshotOf(refId)`) regardless of which expression is used, so they are always byte-identical in practice. The commit's premise (that "previous block" meant the persisted key block rather than the liquid/microblock-extended state) was simply wrong. No gate, no feature, no code change needed for this half of the task.

- [ ] **Step 1: Determine whether the state-hash exclusion needs a gate** — scan real historical CommitToGeneration blocks (same technique as Task 25.6: `GET /debug/balances/history`, `GET /blocks/at/{height}`, cross-referenced against what the hash WOULD be with vs without `nextCommittedGenerators` folded in) for any case where the live/canonical hash reflects the OLD (including) rule. If none found, document that finding and ship as-is with a comment explaining why no gate is needed (first-ever-behavior, nothing to diverge from). If found, gate it the same way as Task 25.6 (new feature, or reuse feature 29 if the timing lines up — check).

- [ ] **Step 2: Determine whether the prevStateHash change needs a gate** — this requires checking whether any historical block prior to `c903a9b9b7`'s real deploy date was originally produced via a liquid-tip/microblock merge (as opposed to a clean, no-microblock key block). If DCC's testnet history has ANY such block before the fix's deploy date, this change is a live, unconfirmed, second divergence source that fresh-genesis replay cannot detect on its own — it would need a differently-shaped test (constructing a scenario with real trailing microblocks, not a clean linear replay) to verify either way.

- [ ] **Step 3:** based on Steps 1-2's findings, either gate each change behind an appropriate feature/height check (following the same pattern as Task 25.6), or document explicitly why no gate is needed with the evidence backing that conclusion — do not leave this undecided.

- [ ] **Step 4: Compile, test, commit** following the same pattern as Task 25.6.

---

### Task 25.8: Fix CancelLeasesToDisabledAliases network-filter inversion (95fc1cd4f8) — will crash on stagenet/testnet at SynchronousCalls activation

**Files:** Modify: `node/src/main/scala/com/decentralchain/state/patch/CancelLeasesToDisabledAliases.scala`

**Severity: HIGH, and time-sensitive** — confirmed this will throw an exception the first time any node crosses feature 16 (SynchronousCalls)'s activation height on stagenet (`'S'`) or testnet (`'!'`), because `PatchOnFeature(SynchronousCalls, Set.empty)`'s `networks.isEmpty` check means "fire on ALL networks" (not "fire on none," which was presumably the intent), and no `CancelLeasesToDisabledAliases-S.json`/`-!.json` resource file exists for those chain IDs — `PatchDataLoader.readPatchData`'s `Source.fromResource` throws.

- [ ] **Step 1:** Confirm the intended behavior — was `Set.empty` meant to disable the patch entirely for DCC's own chains (most likely, since this patch is a Waves-mainnet-specific historical lease cleanup that doesn't apply to a DCC chain that never had that specific historical state), or was it meant to apply to some specific DCC network(s)? Check `docs/dcc-patch-inventory.md` (referenced in earlier audit work) or ask — don't guess.
- [ ] **Step 2:** If disable-entirely is correct, fix `DiffPatchFactory.scala`'s `PatchOnFeature`/`PatchAtHeight` base classes so an explicitly-empty `networks` set means "never applies" (the safe, intuitive reading), not "applies everywhere" — check all other `PatchOnFeature`/`PatchAtHeight` call sites in the codebase first to make sure this semantic change doesn't flip any of THEM from disabled to enabled unexpectedly. Alternatively, if changing the shared base class is too risky, fix locally: give `CancelLeasesToDisabledAliases` a network set that genuinely excludes every DCC chain ID (`'?'`/`'!'`/`'S'`/etc.) rather than `Set.empty`.
- [ ] **Step 3:** Add a regression test that constructs a chain reaching the SynchronousCalls activation height on a non-`'W'` chainId and confirms this patch does NOT throw and does NOT apply.
- [ ] **Step 4: Compile, test, commit.**

---

### Task 25.9: Gate the CommitToGeneration minimum-fee reduction (86ea1c5af2) — mixed-version chain-split risk

**Files:** Modify: `node/src/main/scala/com/decentralchain/state/diffs/FeeValidation.scala`

**Severity: HIGH for mixed-version rollout risk** (not a replay-divergence risk on existing history — old blocks all satisfy the new, lower floor too — but a real split risk between differently-versioned live nodes).

- [ ] **Step 1:** Confirm via testnet history whether any live CommitToGeneration transaction has ever had a fee between 0.01 and 1 DCC (below the old floor, above the new one) — if none exist yet, this is a forward-looking risk only, lower urgency but still worth gating before it matters.
- [ ] **Step 2:** Add a feature gate (new feature, or check if this timing lines up with an existing one) around `FeeValidation.scala:47`'s `CommitToGeneration -> 10` FeeUnits value, falling back to the old `100` FeeUnits value below activation.
- [ ] **Step 3: Compile, test, commit.**

---

### Task 25.10: Fix BlockchainContext.scala cache-key gap for BOTH fixEcrecover and fixGroth16 (extends Task 23's scope)

**Note:** this is the same underlying bug Task 23 already covers for `fixGroth16` — this audit pass confirmed the SAME cache key also omits `fixEcrecover`, and that omission predates DCC's own changes (inherited, then compounded). Fold this into Task 23's fix rather than creating a separate task: when implementing Task 23, make sure the cache key includes BOTH flags, not just `fixGroth16`.

---

### Task 26: Full verification — scoped test suite + live testnet replay past height 3325

**Files:** none (verification only)

**Files:** none (verification only) — this task now runs AFTER Tasks 17–25 as well as 2–14, since those add real consensus-relevant fixes that must be verified together, not just the original NgState/Miner/appender port.

- [ ] **Step 1: Run the full scoped consensus test suite**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala-upstream-port
sbt "node/testOnly com.decentralchain.state.* com.decentralchain.database.* com.decentralchain.mining.* *HotStuff* *Finaliz* *History* com.decentralchain.api.http.DebugApiRouteStateHashSpec"
```
Expected: zero failures, zero regressions vs the pre-port baseline captured in each task's Step-1 test run.

- [ ] **Step 2: Build the node package**

```bash
sbt "node/assembly"
```

- [ ] **Step 3: Fresh-genesis replay against live testnet, past height 3325**

Follow the exact procedure and known gotchas documented in `docs/height-3325-diagnostic-log.md` (in particular: the `dcc.directory` config-placement mistake that silently invalidated an earlier attempt — put it inside the `dcc {}` block, not at the top level). Config: `infra/node-config/testnet/dcc.conf`, mining off, `known-peers=66.228.55.154:6868`. Fresh RocksDB directory (do not reuse a prior attempt's data dir).

```bash
rm -rf /tmp/dcc-replay-verify-data   # fresh dir, adjust to match the diagnostic log's actual data-dir setting
java -jar node/target/node-assembly-*.jar infra/node-config/testnet/dcc.conf
```

Watch the log for height 3325. Expected: no `InvalidStateHash` error; node continues past 3325 to the live chain's current tip without divergence.

- [ ] **Step 4: If it still fails at 3325** — do NOT add more fixes on top per `systematic-debugging`'s Phase 4 rule (3+ fixes failed = question the architecture, not "one more patch"). Instead: capture the new computed-vs-header hash values the same way the diagnostic log did for the pre-port state, and treat this as a fresh Phase-1 investigation — the asymmetry class of bug may have more than one instance. Report back with the new evidence before attempting anything further.

- [ ] **Step 5: If it passes** — let the replay continue running and confirm it stays in sync with the live chain for at least 30 minutes past 3325 (catches a second, further-out divergence rather than declaring victory at the first known blocker).

---

### Task 27: Correct docs/UPSTREAM.md §19 and merge back

**Files:**
- Modify: `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain/docs/UPSTREAM.md` (note: different repo from node-scala — this is in the `Ecosystem/DecentralChain` docs repo/directory)

- [ ] **Step 1: Update the node-scala row in §19** to reflect verified reality: sync point is now genuinely `c1fcc5e0b58cba6743e2d636da81574291c8068c` (v1.6.3, confirmed via this port — not just claimed), list the actual DCC-original commits on top (existing 2 + whatever new ones this plan created, e.g. the BlockEndorser/Miner merge commits, the appender fix, RocksDBWriter fix), and remove the false "fully synced" framing that existed before this work — replace with something verifiable, e.g. "synced through v1.6.3 content (PRs #4034/#4037/#4043 ported YYYY-MM-DD) + N DCC-original commits listed below."

- [ ] **Step 2: Commit the docs change** (in the DecentralChain docs repo, separately from the node-scala commits)

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain
git add docs/UPSTREAM.md
git commit -m "docs: correct node-scala §19 sync status post PR #4034/#4037/#4043 port"
```

- [ ] **Step 3: Push the node-scala branch and open a PR against dev** (only after Task 15's replay verification passes)

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala-upstream-port
git push -u origin fix/upstream-sync-port
gh pr create --title "fix(consensus): port missed upstream PR #4034/#4037/#4043, fix height-3325 InvalidStateHash" --body "$(cat <<'EOF'
## Summary
- Full audit (documented in conversation history) found node-scala's dev branch silently missed upstream Waves PR #4034 (2026-03-31) across 10 files, plus #4037 and #4043, despite docs/UPSTREAM.md claiming full sync as of v1.6.3.
- Ports all missing content, preserving every DCC-specific feature (HotStuff/T2, CommitToGenerationTransaction, BLS, crypto hardening) layered on top.
- Root cause of the long-standing height-3325 InvalidStateHash bug, CONFIRMED with a real bidirectional test: DCC's own PR #53 (2026-08-07) excluded CommitToGenerationTransaction fees from NG carry with no activation-height gate, so replaying block 3325 (mined 2026-06-28, under the old rule) with today's code produces a different hash than what's on-chain. Fixed by gating the exclusion behind a new feature 29 (Task 25.6). This is a DCC-only issue, not a missed upstream port.
- Also includes real upstream sync gaps unrelated to 3325 (appender/referencedBlockchain fork-handling edge case, BLS validation hardening, EndorsementFilter/FinalizationVoting fixes, etc.) — all confirmed real via a two-pass, commit-list-closed audit, none of them the actual cause of 3325.

## Test plan
- [ ] Full scoped consensus test suite passes with zero regressions
- [ ] Fresh-genesis replay against live testnet passes height 3325
- [ ] Replay stays in sync for 30+ minutes past 3325
- [ ] docs/UPSTREAM.md §19 corrected
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** every file the original 4-agent audit flagged as REAL GAP is covered (NgState→2, FinalizationState/EndorsementStorage→3, BlockEndorser→4, CommonGeneratorsApi/GeneratorsApiRoute→5, CommitToGenerationRequest→6, Miner→7, appender→8, BlockchainUpdaterImpl→9, RocksDBWriter/Keys→10, Application→11, TransactionFactory→12, api/http/package+UtilApp→13, Importer/CommonValidation→14). Every REAL GAP the second, corrected, full-module audit found is covered by Tasks 17–25: BlockAppender→17, FinalizationVoting→18, EndorsementFilter→19, BLS crypto hardening→20, PBTransactions→21, ExchangeTransactionDiff/EthereumTransactionDiff→22, BlockchainContext/lang cache-key gaps→23, the should-fix batch (RxExtensionLoader, timestamp bug, LeaseApiRoute, RootActorSystem, BlockEndorsement)→24, decision-record docs→25. Verification is Task 26, UPSTREAM.md correction is Task 27.
- **Completeness guarantee:** the second audit pass wasn't just a bigger sample — it was closed against the exact file lists of the 3 source commits (`git show --name-only` on `693484d4ec`/`52acd9d237`/`fa54286c97`, 123 combined non-test files) cross-checked one-for-one against DCC. Every file traces to either a task above, a confirmed non-issue (documented inline in the relevant task), or the explicitly out-of-scope `testkit`/build-file tooling.
- **Preservation constraint:** every task with identified DCC-specific content has an explicit "MUST preserve" list pulled directly from the completed audit findings, not invented. The Global Constraints section also now lists 4 files where DCC is confirmed *ahead* of upstream — don't let any task's rebrand-and-replace pattern accidentally regress these.
- **"Update to latest if it helps" constraint — corrected:** an earlier check only verified no post-`c1fcc5e0` commit touches the ~19 originally-flagged files, which is narrower than "latest." A full check of `git log c1fcc5e0..origin/version-1.6.x` (all files, no scope restriction) found 11 commits — 10 are dependency/CI bumps, 1 (`de4a93025b`, Jul 7 2026, #4076) has real logic changes, confirmed non-consensus (Time/NTP plumbing) and now covered by Task 25.5. After Task 25.5, this plan brings node-scala to the genuine current tip of upstream's default branch (`origin/version-1.6.x`), not just to the `v1.6.3` tag.
- **Gaps not covered by a task, on purpose:** `Keys.scala`'s `finalizedHeight` decoder duplication (folded into Task 10, cosmetic), `FinalityApiRoute`/`FinalityStatus` (Task 11 Step 3, marked optional — observability only), the `#4037` broadcast-request signing refactor's remaining long tail (`CreateAliasRequest`/`TransferRequest`/`BurnRequest`/`UpdateAssetInfoRequest`/`InvokeExpressionRequest`/`ReissueRequest`/`LeaseRequest`/`ExchangeRequest`/`ProvenTransaction.addProof` — all non-consensus API-shape refactors bundled with the Task 6 decision; port them the same way as `TransactionFactory.scala` in Task 12 if the decision in Task 6 is "port cleanly," skip if DCC's existing hand-rolled equivalents are kept intentionally).
