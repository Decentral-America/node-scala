# Testnet Final Source — Waves-Identical Feature Registry, DCC Improvements Unconditional (rev. 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Rev. 4 (2026-09-02):** a full re-audit against the real post-merge `dev` tree (61 findings) is folded in. Headline changes from rev. 3: Task 1's file list was materially incomplete (10 missing test files, 2 missing config files) and contained two instructions that would have caused an executor to delete working code or misjudge a full rewrite as a touch-up; Task 2's "votable, not pre-activated" decision had no path to ever actually activate on the relaunched chain (dead on arrival) — now paired with a companion activation step; Task 3b's premise about what the frozen chain was running was **factually wrong** and pointed investigation in the wrong direction — corrected, with a new Step 0 that may resolve it as a one-command `git bisect` instead of open-ended debugging; Task 5 had an unflagged pom-version mismatch that could make its "successful" publish silently republish the wrong version; the "tasks run in parallel" claim was wrong for two task pairs. See the inline `[rev.4]` markers for exactly what changed and why.
>
> **Rev. 3** (superseded): two operator decisions landed — feature 26 approved for faithful port; the 2026-09-01 stall root-caused via real VPS logs (not the earlier empty-committee guess). **Rev. 2** (superseded): rev. 1 audited against the real tree, 29 corrections.

**Goal:** Produce the final node-scala source for the testnet relaunch: feature registry mirroring upstream Waves 1.6.x (ids 1–28), every DCC consensus/crypto improvement shipped as the ONLY behavior (no DCC-native feature ids, no legacy paths, no activation gates), the live stall root-caused AND FIXED, the commit-generator automation made fail-loud, and a full-suite-green, reviewed, tagged, image-built release.

**Architecture:** Three decisions drive everything. (1) Testnet is disposable and mainnet's legacy chain has no BLS/HotStuff/commitment history (its genesis pre-activates `[1..13,15,16]` — feature 25 never activated, so no BLS bytes exist anywhere we keep), so the BLS domain-separation + bound-PoP fix (was feature 30) and block-carried equivocation evidence (was feature 29) need no activation gate — they become unconditional and 29/30 are deleted. (2) The registry mirrors upstream, which means registering upstream's 26 `AdjustedBlockRewardDistribution` WITH its logic ported faithfully (operator-approved 2026-09-02: DCC adopts the same reward economics Waves runs — 20-DCC reward, retiring the 10x boost, once 26 activates) — never as a placeholder, because `implemented = dict.keySet` is the unknown-feature safety net. **[rev.4] It must also actually be activatable — a "votable" feature with no config path to ever vote it in is dead code, defeating the whole point of the operator's approval.** (3) The 2026-09-01 stall is a real, unsolved code defect (state-hash non-determinism at height 2640, same class as height-3325) that must be found and fixed before any relaunch, or it recurs at the next height that triggers the same condition. **[rev.4] The investigation must start from the correct premise about what code was actually running when it froze — getting this wrong wastes the entire investigation.**

**Tech Stack:** Scala 3, sbt, ScalaTest, blst BLS, protobuf-schemas 1.6.6 (monorepo), GitHub Actions (infra), Kubernetes/LKE + Linode VPS (testnet).

## Global Constraints

- Branch off `dev` @ `7f771e9ed1` — **`feat/bls-crypto-v2` is ALREADY MERGED into `dev` as `b3748eec14`** (it was completed and merged as its own independent 10-task plan before this plan reached execution). There is no merge step. Branch directly off `dev`.
- Registry target (verbatim from `upstream-waves/version-1.6.x` `features/BlockchainFeature.scala`): 1–25 unchanged (id 1 keeps DCC wording), `26 = AdjustedBlockRewardDistribution` (in dict, logic ported), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (both not-exposed/not in dict, as upstream). No id > 28.
- NEVER register a feature in `dict` whose logic is not implemented (`BlockchainUpdaterImpl.scala:130-147` warn/`forceStopApplication`, `:235-241` append rejection — both key on `dict.keySet`; a placeholder converts a loud UNIMPLEMENTED shutdown into a silent fork).
- v2 crypto is the ONLY crypto: DSTs `…_POP_` / `…_ENDORSE_` / `…_HSVOTE_`; PoP message = `chainId(1) ‖ senderPublicKey(32) ‖ endorserPublicKey(48) ‖ generationPeriodStart(4)` = 85 bytes (asserted at `CommitToGenerationPopMessageSpec.scala:26`, inside the `cryptoV2 = true` case at `:23-27` — the legacy 52-byte assertion at `:20` is what gets deleted). The legacy `_NUL_` tag is deleted from production code entirely.
- `hotstuffConflicts` is always valid when well-formed (no activation gate). `slashing-enabled` stays an operator flag (default `false`).
- `HotStuffSettings` unchanged: `authoritative=false` default (advisory floor — audit F-1 decision = advisory), `slashingEnabled=false`, `maxTargetLagFraction=0.25` (`HotStuffSettings.scala:88-90`).
- Infra repo paths are repo-root-relative: `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra/.github/workflows/...`; runbooks live at the infra repo ROOT (`DEPLOY.md`, `RUNBOOK-*.md`, `MAINNET-LAUNCH.md`, etc.), not under `docs/` (`docs/` there holds only `superpowers/`). **[rev.4] `monitoring/alerts.yml` + `monitoring/exporter.py` are the config source; `clusters/testnet/monitoring/` is NOT a copy of them — it holds only Flux/Helm deployment manifests (`kube-prometheus-stack.yaml`, `metrics-exporter.yaml`). How `alerts.yml`/`exporter.py` actually reach the running cluster (ConfigMap, image build, or Helm values) is UNVERIFIED — confirm this before Task 4 Step 3, or an edited alert may silently never deploy.**
- FOREGROUND sbt only in every task (background-and-wait has wedged multiple agents). Full-suite gate command: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-testkit/compile" "node-tests/test"`. **[rev.4] Re-baseline the test count at Task 1 Step 1 — the "2,943 tests" figure predates 71 commits (the entire `bls-crypto-v2` merge); do not trust it.**
- Commits sole-authored by jourlez; NEVER add Co-Authored-By / AI attribution. No `git worktree` (sbt-git breaks); use a full clone if isolation is needed.
- Infra: read-only investigation first; no live-node mutation (restarts, PVC wipes, config rollouts, workflow dispatch against live nodes) without the explicit go-ahead step named in the task.
- Review packages: there is no `scripts/review-package` in this repo. Use the superpowers script by absolute path: `/Users/jourlez/.claude/plugins/cache/superpowers-dev/superpowers/6.1.1/skills/subagent-driven-development/scripts/review-package BASE HEAD`.
- **[rev.4] Task ordering is NOT fully parallel.** Parallel-safe: Task 3b, Task 4, Task 5 Step 1. Strictly sequential: **Task 1 → Task 6 → Task 2 → Task 7 → Task 8** (Task 6's `scalafmtAll` must run after Task 1's ~50-file rewrite, or its "whitespace-only" check is meaningless and it will conflict with Task 1's own renames; Task 7 must run after Task 2, since it verifies Task 2's divergence-doc section and reconciles the genesis config's feature list against Task 2's decision). Task 5 Step 2 gates on Task 5 Step 1 succeeding, verified (see Task 5). Task 9 gates on Task 3b AND Task 8.

---

### Task 1: Remove features 29 and 30 with all gates — crypto and evidence become unconditional

**Files (all anchors verified against current `dev` @ `7f771e9ed1`, post-`bls-crypto-v2`-merge):**

- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (:32 `HotStuffEquivocationEvidence`, :33 "28 is BURNED" comment, :34 `BlsCryptoV2`, :69-70 dict entries — remove; 26/27 handled in Task 2)
- Modify: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (delete `supportsHotStuffEquivocationEvidence` :312-313, `supportsBlsCryptoV2` :323-324)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala` (:369-371 feature-29 gate → delete; :384-395 activation-period boundary block → delete; :397 `proofDst`, :446 `endorsementDst` → constants)
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` (`withHotStuffConflicts`'s gate at **:350** + its explanatory comment at **:343-349** → delete. **[rev.4] `forgeHeight` at :348 is STILL USED after the gate goes — by the `generationPeriodOf(forgeHeight)` match and `upTo(forgeHeight)` — verify this before deleting; do not remove it along with the gate, that breaks compile.**)
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala` (:11-17 legacy tag + "kept HERE FOREVER" scaladoc → delete; :75/:88/:151 `dst` defaults → remove, params stay required), `crypto/bls/BlsSignature.scala:18` (stale "legacy paths" scaladoc)
- Modify: `block/BlockEndorsement.scala` (:27 `_ENDORSE_` unconditional), `consensus/hotstuff/HotStuffQuorum.scala` (:64 `_HSVOTE_` unconditional; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC`), `HotStuffEquivocationProof.scala` (dst = HSVOTE constant), `HotStuffCoordinator.scala`, `HotStuffEngine.scala` (`EngineState.cryptoV2`), `HotStuffVotePool.scala`, `NodeHotStuffEffects.scala` (`signVote(dst)` → constant), `transaction/CommitToGenerationTransaction.scala` (:70-77 v2 branch only; drop `cryptoV2` from `popMessage`/`popDst`/`mkPopSignature`), `transaction/TransactionFactory.scala`, `state/BlockEndorser.scala` (remove `carrierHeight` :110/:123/:154 and the era read at :160 — its ONLY use), `state/EndorsementFilter.scala` (drop `cryptoV2` field), `state/EndorsementStorage.scala`, `state/diffs/BlockDiffer.scala` (:643), `state/diffs/CommitToGenerationTransactionDiff.scala` (:26), `settings/BlockchainSettings.scala` (:155 MAINNET `BlsCryptoV2.id -> 1`, :181 STAGENET — delete), `Application.scala` (:351-355 provider), `api/http/TransactionsApiRoute.scala` (:261), `api/http/requests/CommitToGenerationRequest.scala`, **`utils/UtilApp.scala` — see the dedicated note below, DO NOT follow a naive "delete tags at :415-423" instruction**, `utx/UtxPoolImpl.scala` (comment), `node/src/main/resources/network-defaults.conf:47` (`30 = 1` → delete), `application.conf:20-24` (the BlsCryptoV2 comment block), **`docker/private/decentralchain.custom.conf`** (`[rev.4] MISSING FROM PRIOR REVS — added by the same commit that pre-activated feature 30; delete its `30 = 1`/equivalent pre-activation line`), `node/testkit/.../TxHelpers.scala` (`cryptoV2` param on `commitToGeneration`)

  **[rev.4] `UtilApp.scala` — corrected, do not delete the smoke test.** `blsCryptoV2Era` (the era-arithmetic helper) is defined at **:257** and called at **:354**; the CLI option `--bls-crypto-v2-activation-height` is at **:217** — delete all three. But lines **:415/:417/:424** are a DIFFERENT thing: the `--verify` command's BLS-aggregation self-check, which calls `blsSK1.sign(message, BlsUtils.BlsDomainSeparationTag)` / `verifyAgg(..., BlsUtils.BlsDomainSeparationTag)` three times as a smoke test of the aggregation primitive. **Retarget these three call sites to `BlsUtils.BlsEndorseDomainSeparationTag`** (it exercises the aggregate-endorsement primitive) — do NOT delete them, that silently removes working smoke-test coverage.

  **[rev.4] `Miner.scala:211-216` — do not touch these in Task 1; they're Task 3's job.** `:211-213` is the generating-balance-floor veto message (`"$address is not committed on $newBlockHeight..."`); `:214-216` is a SEPARATE, structurally identical conflict-veto message (`"$address is conflict on $newBlockHeight..."`) — leave both alone here, Task 3 fixes only the first one's wording.

- Delete tests (gate-only): `settings/BlsCryptoV2PreActivationSpec.scala`, `state/BlsCryptoV2ActivationHelperSpec.scala`, `state/BlsCryptoV2RollbackDeterminismSpec.scala`, `state/appender/BlsCryptoV2EquivocationProofBoundarySpec.scala`, `utils/UtilAppSpec.scala` (tests only `blsCryptoV2Era`), and case 2 of `state/appender/HotStuffEquivocationValidationSpecification.scala` (**:103-116**, plus the blank line at :117; also drop its `:40` `.addFeatures(..., BlockchainFeatures.HotStuffEquivocationEvidence)` setup call — `[rev.4]` this second edit was missing from prior revs)

- Edit tests to v2-only — **[rev.4] restructured into three groups by how much rewriting each actually needs, since treating all of these as "drop a positional arg" would leave several unable to compile:**

  **Group A — shared fixtures, edit FIRST (everything else may inherit from these):**
  - `finalization/BaseFinalizationSpec.scala` (`:32/:39/:52/:54/:56/:58/:66` — `[rev.4] entirely missing from prior revs`; this is the shared base class most finalization specs extend — its `mkConflictEndorsement`/`signed` helper signatures currently take `cryptoV2`, drop the parameter)

  **Group B — mechanical positional-arg drops (1-5 `cryptoV2` hits each, confirm via `git grep -c cryptoV2 <file>` before assuming — most are exactly this):**
  `crypto/bls/BlsUtilsTest.scala` (see the dedicated note below — this one is NOT purely mechanical), `state/BlockEndorserSpec.scala` (remove `carrierHeight` block :136), `features/BlockchainFeaturesRegistrySpec.scala` (assert 29/30 ABSENT — see Step 2), `finalization/conflict/MultipleConflictEndorserSuite.scala:89`, `finalization/BlsCryptoV2EndorsementSpec.scala` (8→4 cases), `state/BlsCryptoV2SnapshotPathPopSpec.scala`, `HotStuffEngineSpecification.scala`, `HotStuffEquivocationDetectionSpecification.scala`, `HotStuffEquivocationProofSpecification.scala`, `HotStuffLagReanchorSpecification.scala`, `HotStuffLargeCommitteeSpecification.scala`, `HotStuffLockedQCStoreSpecification.scala`, `HotStuffResetDoubleVoteSpecification.scala`, `HotStuffVotePoolBoundedGrowthSpecification.scala`, `HotStuffVotedSetPruningSpecification.scala`, `HotStuffWatchdog*Specification.scala` (both variants), plus (`[rev.4] missing from prior revs`) `http/GeneratorsApiRouteSpec.scala:104`, `lagonaki/unit/MicroBlockSpecification.scala:75`, `state/BlockChallengeTest.scala:284,355`, `state/LightNodeTest.scala:239,254`, `state/EndorsementStorageSpec.scala:47,48,301,324`, `state/FinalizationStateHotStuffConflictsSpecification.scala:55`, `transaction/CommitToGenerationTransactionsSpec.scala:140,192,209`, `finalization/MinerWithFinalitySuite.scala:463,559,577,594`, `state/EndorsementFilterSpec.scala:39,57`, `transaction/CommitToGenerationPopMessageSpec.scala` (drop legacy-layout cases; keep the 85-byte layout + chainId/sender-differ cases), `state/diffs/CommitToGenerationPopV2Spec.scala` (keep transplant-by-domain; collapse pre/post pairs)

  **Group C — substantial rewrites, more than positional args (`[rev.4] these were miscategorized as mechanical in prior revs — inspect each fully before editing, do not skim-and-drop`):**
  - `HotStuffQuorumSpecification.scala` (31 `cryptoV2`/DST hits), `HotStuffVotePoolSpecification.scala` (29), `HotStuffCrossEpochForkSpecification.scala` (28), `HotStuffVotePoolCommitteeChangeSpecification.scala` (19), `HotStuffViewChangeSpecification.scala` (9) — these carry dedicated legacy-vs-v2 test GROUPS, not single positional args; find and collapse each pair to the single surviving (v2) case.
  - `mining/HotStuffEquivocationEvidenceE2ESpecification.scala` (23 hits) — has a WHOLE SECOND settings fixture `withEvidenceFeatureV2` (:58-65) built on `BlsCryptoV2`, a `cryptoV2` param threaded through `signedVote`/`detectFoldAndSend` (:73-78, :89-92, :123-135), and **two entire test groups at :222-263** ("under BlsCryptoV2 (v2 DST)" and the legacy/v2 cross-DST rejection case) that exist only to prove the gate. Delete `withEvidenceFeatureV2` (the plain `withEvidenceFeature` fixture is now the only one needed), drop `cryptoV2` from the two helper signatures, delete the `:227-263` group pair, keep ONE unconditional chain-of-custody proof (detect → fold → wire → validate → conflictGenerators).

  **[rev.4] `BlsUtilsTest.scala` — not purely "drop one test", four separate edits:**
  1. Delete the `:362-366` "legacy tag remains the BlsUtils-level default" test.
  2. Retarget `:74/:80/:217`'s raw `BlsDomainSeparationTag` usages (low-level hash-domain/Pairing constants in unrelated tests) to a surviving tag.
  3. Rewrite `:315-321`'s cross-DST matrix seed so `all` is the three v2 tags — a 3x3 matrix, not the current 4-tag one; update `:341`'s negative assertion to match.
  4. `:325`'s "each v2 tag keeps the legacy suite prefix" assertion currently DEPENDS on the legacy tag existing for comparison — rewrite it to assert each surviving tag literally equals its expected string AND shares the `BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_` prefix. With the legacy tag gone, nothing else pins these three exact strings — a typo here is a silent chain-identity change with no compile error.

  **[rev.4] Vector rewrite — this is a FULL REWRITE, not a re-pin.** `BlsLegacyVectorRegressionSpec.scala` (111 lines) pins **legacy-DST vectors ONLY** — all three test groups assert "verifies under the legacy DST" / "fails under every v2 DST". There are no v2 vectors already in the file to keep. `git mv` it to `BlsVectorRegressionSpec.scala`, then every Base64 literal becomes invalid and every negative assertion inverts ("fails under every v2 DST" → "fails under the other two v2 DSTs"). Re-create a throwaway printer (do not commit it) deriving keys from the seeds documented at `:29-38` (each seed = the 31-ASCII-byte string + one `0x00` pad = 32 bytes; **signer-1's seed and the PoP seed are intentionally the same string — do not "fix" that**; the printer referenced in the file's own comment was already deleted before this file was committed, per its `:24-25` note — this is expected, not a discovery), `generationPeriodStart = 12345`, `chainId = 'D'`, `finalizedId = 32×0x07`, `finalizedHeight = 100`, `endorsedId = 32×0x09`; sign under the three tags; paste the Base64 triples as literals; assert each verifies under its own tag and fails under the OTHER TWO v2 tags. Rewrite the header:

```scala
/** Pinned byte vectors for the three BLS contexts (PoP / endorsement / HotStuff vote), synthesized from
  * fixed seeds (documented below) and pasted as literals. They pin TODAY'S message layouts + domain tags
  * so an accidental change to either fails loudly. Regenerate ONLY as part of a deliberate, reviewed
  * encoding change — which is a chain-identity change (every node must ship it from genesis). Never
  * regenerate to make a red test green.
  */
```

**Interfaces:**
- Produces: `BlsUtils.{BlsPopDomainSeparationTag, BlsEndorseDomainSeparationTag, BlsHsVoteDomainSeparationTag}` (renamed from `…TagV2`); `HotStuffQuorum.VoteDst`; `CommitToGenerationTransaction.{popMessage(chainId, sender, endorserPk, periodStart), PopDst}`; `HotStuffQuorum.verifyVote(vote, committee)` / `formQC(votes, committee)` / `verifyQC(qc, committee)` without `cryptoV2`.

- [ ] **Step 1: Create the branch, re-baseline the test count**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git checkout dev && git pull --ff-only
git checkout -b feat/testnet-final-source
sbt "node/compile" "node-testkit/compile" "node-tests/test"
```
Record the CURRENT total test count from this run — do not reuse "2,943" from earlier docs, it predates 71 commits.

- [ ] **Step 2: Write the failing registry test**

In `node/tests/src/test/scala/com/decentralchain/features/BlockchainFeaturesRegistrySpec.scala`:

```scala
  "the registry has no DCC-native feature ids" in {
    BlockchainFeatures.feature(29) shouldBe None
    BlockchainFeatures.feature(30) shouldBe None
    BlockchainFeatures.implemented.max should be <= 28.toShort
  }

  // [rev.4] pins the safety net D1 depends on and D6 (removing the gates) partially trades away:
  // every registered id must have real logic wired in.
  "implemented is exactly the dict, and every registered feature is real" in {
    BlockchainFeatures.implemented shouldBe BlockchainFeatures.dict.keySet
  }
```

- [ ] **Step 3: Run to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.features.BlockchainFeaturesRegistrySpec"` — Expected: FAIL (`feature(29)` is `Some`).

- [ ] **Step 4: Remove feature 29 + its gate**

`BlockchainFeature.scala`: delete the `HotStuffEquivocationEvidence` val (:32), its dict entry, and the "Id 28 is deliberately BURNED" comment (:33). `Blockchain.scala`: delete `supportsHotStuffEquivocationEvidence` (:312-313). `appender/package.scala` `validateHotStuffEquivocationProofs`: delete the `raiseUnless(blockchain.supportsHotStuffEquivocationEvidence(blockHeight))(...)` step at **:369-371** (keep every other rule: consistency, epoch==period, bounds, dedup, known-conflict, overlap, signatures). `Miner.scala` `withHotStuffConflicts`: delete the gate at **:350** and its comment at **:343-349**, keep `forgeHeight` (:348, still used) and the `generationPeriodOf` match. `HotStuffEquivocationValidationSpecification.scala`: delete case 2 (**:103-116**) and its `:40` `.addFeatures(...)` setup. Drop `.addFeatures(BlockchainFeatures.HotStuffEquivocationEvidence)` at `MultipleConflictEndorserSuite.scala:89` and `HotStuffEquivocationEvidenceE2ESpecification.scala:55` (this file needs the Group-C rewrite too, done in Step 5's pass since it also carries `cryptoV2`).

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.state.appender.* com.decentralchain.mining.* com.decentralchain.features.*"` — green except the registry test still failing on 30.

- [ ] **Step 5: Remove feature 30 — crypto becomes unconditional**

Work through every file in the Files list above and the three test groups (A, B, C), in that order (A before B/C — shared fixtures first). Use `git grep -c cryptoV2 <file>` to confirm which group a Group-B candidate actually belongs to before editing it as mechanical.

`BlsUtils.scala`: delete `BlsDomainSeparationTag` and its :11-17 scaladoc (obsolete: mainnet never activated feature 25, so no BLS bytes exist anywhere pre-25 — say this in the commit body). Rename `Bls{Pop,Endorse,HsVote}DomainSeparationTagV2` → `Bls{Pop,Endorse,HsVote}DomainSeparationTag`. Remove the `= BlsDomainSeparationTag` defaults at :75/:88/:151 (params stay required).

`CommitToGenerationTransaction.scala`:
```scala
  /** Canonical PoP message: chainId ‖ senderPublicKey ‖ endorserPublicKey ‖ generationPeriodStart (85 bytes).
    * chainId defeats cross-chain PoP replay (BLS audit M2); sender defeats mempool PoP lifting (M2). */
  def popMessage(chainId: Byte, sender: PublicKey, endorserPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    Array(chainId) ++ sender.arr ++ endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  val PopDst: String = BlsUtils.BlsPopDomainSeparationTag
```
Update `mkPopSignature(blsKeyPair, generationPeriodStart, sender, chainId)` (drop `cryptoV2`) and every caller (`TxHelpers`, `CommitToGenerationRequest.toTxFrom`, `TransactionFactory`, `UtilApp`, tests — the compiler names them).

`BlockDiffer.scala:643`, `CommitToGenerationTransactionDiff.scala:26`: delete `val cryptoV2 = ...`; call `popMessage(tx.chainId, tx.sender, tx.endorserPublicKey, tx.generationPeriodStart)` + `PopDst`.

`BlockEndorsement.scala`: `mkMessage` unchanged (68 bytes); `sign`/`signed`/`signatureValid` drop `cryptoV2`, use `BlsUtils.BlsEndorseDomainSeparationTag`. `EndorsementFilter.scala`: delete the `cryptoV2` field (+ its 3 test constructions). `EndorsementStorage.verifySig`: endorse tag. `BlockEndorser.scala`: delete `carrierHeight` (:110/:123/:154) and the `cryptoV2 = blockchain.supportsBlsCryptoV2(carrierHeight.toInt)` read (:160); `castVote(votingHeight, endorsedHeight, ...)`. `appender/package.scala`: `endorsementDst` → constant; `proofDst` → `HotStuffQuorum.VoteDst`; delete the boundary block :384-395.

`HotStuffQuorum.scala`: `val VoteDst: String = BlsUtils.BlsHsVoteDomainSeparationTag`; delete `voteDst(cryptoV2)`; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC` and from `HotStuffVotePool` / `HotStuffEngine` (`EngineState.cryptoV2`) / `HotStuffCoordinator` (`cryptoV2: () => Boolean` provider and both read sites) / `NodeHotStuffEffects.signVote` (pass `VoteDst`) / `Application.scala:351-355`. `HotStuffEquivocationProof.signaturesValid` drops `dst`, uses `HotStuffQuorum.VoteDst`.

`BlockchainSettings.scala`: delete `BlsCryptoV2.id -> 1` at :155 and :181. `network-defaults.conf:47`: delete `30 = 1`. `application.conf:20-24`: delete the BlsCryptoV2 block. `docker/private/decentralchain.custom.conf`: delete its pre-activation line. `UtilApp.scala`: delete `blsCryptoV2Era` (def :257, call :354), the `--bls-crypto-v2-activation-height` option (:217); retarget the :415/:417/:424 smoke-test calls to `BlsEndorseDomainSeparationTag` (do NOT delete them). `UtxPoolImpl.scala`: keep the pack-side era comment minus the BlsCryptoV2 mention. `Blockchain.scala`: delete `supportsBlsCryptoV2` (:323-324). `BlockchainFeature.scala`: delete the `BlsCryptoV2` val + dict entry. `BlsSignature.scala:18`: drop the "legacy paths" sentence.

Delete the six gate-only test files (Step listed above). Edit Group A, then B, then C in order.

- [ ] **Step 6: Verify**

Run: `sbt "node/compile" "node-testkit/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.crypto.bls.* com.decentralchain.transaction.* com.decentralchain.finalization.* com.decentralchain.state.* com.decentralchain.consensus.hotstuff.* com.decentralchain.mining.* com.decentralchain.settings.* com.decentralchain.utils.*"` — all green.

Then two greps: (a) `git grep -n "cryptoV2\|supportsBlsCryptoV2\|BlsDomainSeparationTag\b\|_NUL_\|HotStuffEquivocationEvidence\|BlsCryptoV2\|carrierHeight\|voteDst\|popDst\|TagV2" -- node/src node/testkit node/tests` → the ONLY surviving hits must be `BlockchainFeaturesRegistrySpec.scala`'s two "absent"/"exactly the dict" assertions (expect exactly 2 files' worth of hits, all in that one file). (b) `git grep -l "cryptoV2\|BlsCryptoV2" -- . ':!node/src' ':!node/tests' ':!node/testkit' ':!docs'` → must be empty (node-it, lang, etc. need no changes — verified clean today, confirm it stays that way).

- [ ] **Step 7: Commit (two commits)**

```bash
git add -A node/src node/testkit node/tests
git commit -m "refactor(consensus): remove feature 29 -- block-carried equivocation evidence is unconditional

Testnet is re-genesised and mainnet's legacy chain never activated
feature 25 (no HotStuff history), so the activation gate protected
nothing. hotstuffConflicts is now valid whenever well-formed (consistency,
epoch==period, bounds, dedup, signatures all still enforced). Rule: every
node on a chain runs this binary from genesis."
```
```bash
git commit -m "refactor(crypto): per-context BLS domain tags + chain/sender-bound PoP are the only crypto (remove feature 30)

Deletes the legacy _NUL_ tag (its 'kept forever / chain split' rationale
was obsolete: no BLS bytes exist on any chain we keep, since mainnet never
activated feature 25), every cryptoV2 gate/provider, the activation-period
boundary rule, and the rollback-across-activation scaffolding. BLS-audit
H2 and M2 fixes ship unconditionally; vectors rewritten under the three
surviving tags. Registry has no id > 28."
```

---

### Task 2: Register upstream feature 26 with its logic ported faithfully; realign 27/28

**Decision (operator-approved 2026-09-02): port `f1bedddb2e` faithfully, matching Waves exactly.** DCC adopts the same reward economics upstream runs once 26 activates: reward resets to `AdjustedFullReward = 20 * UnitsInWave`-equivalent, and feature 23's `blockRewardBoost` retires (returns 1). No DCC-specific constant substitution. This is a real monetary-policy change (DCC's current reward is 6 DCC/block with a live 10x boost period) — it is intentional, not a bookkeeping default, and both the commit body and `consensus-divergences-from-upstream.md` must say so plainly.

**Files:**
- Read: `git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala`; upstream commit `f1bedddb2e` "Adjusted block reward distribution (#4086)" (`git show f1bedddb2e --stat`, then each file).
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` — `26 = AdjustedBlockRewardDistribution` (in dict), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (not exposed, not in dict)
- **[rev.4] Modify (port targets — upstream's commit touches 6 production/test-support files + 1 spec, not 5; and `BlockRewardCalculator.scala` lives at `state/`, NOT `state/diffs/` — verify the DCC path before editing):**
  `state/BlockRewardCalculator.scala`, `state/Blockchain.scala`, `state/BlockchainUpdaterImpl.scala`, `state/diffs/EthereumTransactionDiff.scala`, `state/diffs/ExchangeTransactionDiff.scala`, and DCC's counterpart of upstream's `test/DomainPresets.scala` (find via `git grep -rl "object DomainPresets" node/testkit node/tests` — DCC's `MultipleConflictEndorserSuite`/`HotStuffEquivocationEvidenceE2ESpecification` both build on `DomainPresets.DeterministicFinality`, so this file is load-bearing, not test-only nicety)
- Test: port upstream's `history/BlockRewardSpec.scala` changes (195 added lines upstream); extend `BlockchainFeaturesRegistrySpec`
- Modify: `docs/consensus-divergences-from-upstream.md` (NEW section: reward distribution — DCC has `daoAddress = None` / `xtnBuybackAddress = None` on all presets, documented today only as inline comments at `BlockchainSettings.scala:147-148/169-170/188-189`; the split table (hunk a) is therefore inert even though it's ported — record that 100% of the 20-unit reward goes to the miner, no DAO/buyback split, by design)

**What `f1bedddb2e` actually is — four hunks, all ported faithfully:**

| Hunk | On DCC | Port |
|---|---|---|
| (a) `RewardDistribution` split table (10/2 dao/xtn, 5/6–1/6 remainder) | inert (`daoAddress`/`xtnBuybackAddress` are `None` on all presets — every payout folds to 0) | port as-is |
| (b) `blockRewardBoost` returns 1 once 26 active (retires feature 23's 10×) | **adopted**: DCC's `blockRewardBoostPeriod` (300,000 mainnet / 2,000 testnet) ends when 26 activates | port as-is |
| (c) one-time force-set of voted `blockReward` to `AdjustedFullReward = 20 * UnitsInWave` at activation | **adopted**: DCC's reward becomes 20-equivalent (up from 6) at activation, matching Waves | port as-is, use the SAME constant Waves uses (no DCC substitution) |
| (d) Eth/Exchange gate-widening `isFeatureActivated(25) → 25 || 26` | free no-op on any chain with 25 | port as-is |

**Activation path — [rev.4] this is new; without it, feature 26 is dead code on the relaunched chain, defeating the whole point of the operator's approval.** "Votable, not pre-activated" (rev. 3's decision) only means something if there's a way to actually vote it in. Testnet's 4 nodes activate features by `blockchain.custom.functionality`/`supported-features` config voting over a `featureCheckBlocksPeriod`/`blocksForFeatureActivation` window — verify the exact DCC config keys via `git grep -n "supported-features\|feature-check-blocks-period\|blocks-for-feature-activation" node/src/main/resources`. **Without adding feature 26 to `supported-features` on ≥ the activation threshold of testnet's 4 nodes (in `infra/node-config/testnet/dcc.conf` and all three `infra/clusters/testnet/apps/nodes.yaml` blocks), the reward-economics change the operator approved will NEVER actually run on the relaunched chain.** This step belongs in Task 9 (the relaunch runbook), not here — but Task 9 must include it, or add the config, or the operator's decision has no effect. Do NOT silently drop this by treating "votable" as equivalent to "done."

**Interfaces:**
- Produces: `BlockchainFeatures.AdjustedBlockRewardDistribution = BlockchainFeature(26, "Adjusted Block Reward Distribution")` fully implemented (all four hunks); `ContinuationTransaction` id 27; `LeaseExpiration` id 28.

- [ ] **Step 1: Failing registry test**

```scala
  "the registry mirrors upstream Waves 1.6.x ids 26-28" in {
    BlockchainFeatures.feature(26) shouldBe Some(BlockchainFeatures.AdjustedBlockRewardDistribution)
    BlockchainFeatures.implemented should contain(26.toShort)
    BlockchainFeatures.ContinuationTransaction.id shouldBe 27
    BlockchainFeatures.LeaseExpiration.id shouldBe 28
    BlockchainFeatures.implemented should not contain 27.toShort
    BlockchainFeatures.implemented should not contain 28.toShort
  }
```
Run → FAIL.

- [ ] **Step 2: Port hunk-by-hunk, all four, faithfully.** Map each upstream hunk to its DCC file (including `DomainPresets.scala` and `BlockRewardSpec.scala` — see the corrected Files list above); keep DCC's `daoAddress = None` handling for hunk (a); port hunks (b)/(c) exactly as upstream wrote them; port (d) as pure gate-widening. Port upstream's tests for all four hunks and add a RED/GREEN case per hunk proving pre/post-26 behavior (in particular: reward 6-DCC-equivalent before 26, 20-DCC-equivalent after; `blockRewardBoost` returns the boosted multiplier before 26, `1` after). Renumber 27/28. Presets: there is no `25` entry to sit beside — current maps are TESTNET `Map.empty`, MAINNET `{}` after Task 1, STAGENET `{1..13->0}` after Task 1, devnet conf `{1..15->0}` (gap 16–25, leave as-is); the INFRA testnet configs pre-activate `1..25 = 0` — those, not `BlockchainSettings.TESTNET`, govern the relaunch. Do NOT add `26` to any pre-activated-features map — it activates by vote (see the activation-path note above, carried into Task 9).

- [ ] **Step 3: Verify** — `sbt "node/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.state.* com.decentralchain.mining.* com.decentralchain.settings.*"` green, then registry parity:
```bash
diff <(git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala | grep -o 'BlockchainFeature([0-9-]*, "[^"]*")') \
     <(grep -o 'BlockchainFeature([0-9-]*, "[^"]*")' node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala)
```
Expected: the ONLY difference is id 1's description.

- [ ] **Step 4: Commit** — `feat(consensus): register upstream feature 26 -- reward economics ported faithfully, matching Waves; registry mirrors Waves 1.6.x ids 1-28`. Body: cite `f1bedddb2e`, state plainly that DCC's block reward becomes 20-DCC-equivalent and the 10x boost retires once 26 activates, note 27/28 renumbering is safe only under fresh genesis, and that 26 activates by vote (config path completed in Task 9, not here).

---

### Task 3: ✅ DONE — root-cause the 2026-09-01 stall from node logs; fix the misleading miner message

**Status: root-cause research complete (2026-09-02), via real logs pulled from the VPS.** Full record: `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §8 (2026-09-02 entry), filtered log archive at `node-scala/.superpowers/sdd/stall-2026-09-01-filtered.log` (local, not committed).

**Confirmed sequence:** chain advanced cleanly to height 2639 (`2026-09-01T09:02:15Z`). Sealing block 2640, the miner made 102 consecutive attempts (`09:02:23Z`..`10:00:31Z`) and failed EVERY time with `InvalidStateHash(expected, computed)` — a different expected/computed pair on every attempt (genuinely unstable computation, not one bad transaction). After the last attempt the miner went silent forever — no crash, no restart; the container stayed "healthy" (its Docker healthcheck only pings the REST API).

**This is the SAME BUG CLASS as the already-solved height-3325 divergence — a NEW occurrence, not yet traced to a line.** Earlier hypotheses (H1 balance floor, H2 conflict exclusion, H3 partial committee, empty-committee halt) are RULED OUT: the miner fails computing the block's own state hash, before any committee/conflict/finality logic runs.

- [x] Logs obtained (VPS `deploy@66.228.55.154`, `docker logs node-scala-testnet`).
- [x] Root cause written up in the reference doc.
- [ ] **Remaining step: fix `Miner.scala:211-213`'s misleading "not committed" message (cosmetic; unrelated to the real bug — do alongside Task 3b, not Task 1).** The only existing test referencing the string is a NEGATIVE assertion (`MinerWithFinalitySuite.scala:84` … `shouldBe empty`) — it stays green under any rewording, so WRITE a new positive test that triggers the balance-floor veto and asserts the new message (`generating balance <x> below minimal <y> at height <h>`). Leave `:214-216`'s separate conflict-veto message untouched — it's accurate. Commit: `fix(mining): say what the forge veto actually checks (generating balance floor, not commitment)`.

---

### Task 3b: ⛔ FIND AND FIX the state-hash non-determinism at height 2640 (blocking — the relaunch cannot proceed without this)

**This is the hardest, least-scoped task in the plan.** Task 3 found the symptom; this task finds the cause. Treat it like the original height-3325 investigation (`docs/height-3325-diagnostic-log.md`, `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §2's "confirmed with a real bidirectional test, not inference" standard) — evidence-first, no guessing, use `superpowers:systematic-debugging`.

**[rev.4] CORRECTED PREMISE — read this before anything else.** Prior revs of this task claimed the frozen chain was running "dev@ade354adcb-era fix for height-3325 PLUS everything merged since (T5 equivocation evidence, BFT/BLS/F-6 hardening...)". **This is false and was pointing the investigation in exactly the wrong direction.** The actual running image, `ghcr.io/decentral-america/node-scala:testnet-1bd671f8e6`, is dev's `fix/hotstuff-audit-findings` merge from **2026-08-31 17:19** — **71 commits have landed on dev since**, including the ENTIRE F-6 lag-reanchor cluster, ALL of the BLS L1-L4/H1/M3/M4 hardening, ALL of `feat/bls-crypto-v2`, and ALL of the T5 equivocation-evidence work. **None of that code was running when the chain froze.** Two consequences: (a) no post-`1bd671f8e6` change can be the cause — do NOT bisect forward from the incident looking for a regression the recent work introduced; (b) the fix may ALREADY exist in current `dev` incidentally, which is why Step 0 below (new in this rev) comes before any deep investigation.

Note also: LKE nodes pin by DIGEST (`ghcr.io/decentral-america/node-scala@sha256:e2e8477...`, set by infra commit `60c4d37`, 2026-08-31) while the VPS docker-compose node uses a TAG (`:testnet-1bd671f8e6`) — confirm which one actually froze (the incident logs are from the VPS) and don't assume the LKE nodes are on the identical build.

**What's known:**
- Height: block 2640 (key block sealing after liquid height 2639). `generationPeriodLength = 100` on testnet (confirmed by infra commit `bc04763835`'s message), so period `[2601,2700]` — 2640 is mid-period, not a period boundary (rules out anything gated on `committedGeneratorsHash`).
- Symptom: `MinerImpl`'s own computed state hash disagrees with itself across retries — a local check before broadcast (confirm the exact call site in `MinerImpl`/`BlockDiffer`), not a peer disagreement, and not a single bad transaction (the hash pairs differ each retry; a bad tx would fail identically every time).
- Candidates to investigate (verify each, do not assume): (a) transaction ordering non-determinism in the mempool/UTX pack step feeding a slightly different tx set/order into each retry; (b) a HashMap/Set iteration-order dependency inside the state-hash builder or a diff accumulator not using a canonically-ordered collection; (c) an old-vs-new rule disagreement for some specific transaction type present at this height (identify the real tx list at 2640 first — the log's `MicroBlock(...txs=N)` lines around 2639 name counts; cross-reference once the chain is inspectable); (d) a genuinely non-deterministic source (wall-clock, `System.nanoTime`, unordered `Future`/parallel collection) reaching into diff computation.

**Files:** unknown until root-caused. `state/diffs/BlockDiffer.scala`, `state/StateHash.scala`, `state/StateHashBuilder.scala`, `state/snapshot/TxStateSnapshotHashBuilder.scala` (all four confirmed to exist and hold state-hash logic — grep further from there), `mining/MinerImpl.scala` (the retry loop — confirm it rebuilds the tx set identically each retry, or if it IS the differing-input source), plus existing specs `state/StateHashSpec.scala`, `state/snapshot/TxStateSnapshotHashSpec.scala`, `http/DebugApiRouteStateHashSpec.scala`, `http/DebugApiRouteStateHashGenesisSpec.scala` — read these first, they're the existing test surface for exactly this area.

- [ ] **Step 0 [rev.4, new — do this FIRST, it is the single highest-value action in this task]:** attempt to replay the real chain (genesis @ 2026-08-31 → height 2639, using the actual relaunch genesis config) against TWO builds: (i) current `dev` (post all 71 commits) and (ii) commit `1bd671f8e6` exactly (what actually froze). If (i) passes and (ii) reproduces the freeze, this is a **one-command `git bisect`** across those 71 commits — converts open-ended debugging into a mechanical search, and there is a real chance the fix already landed incidentally as part of the BFT/BLS/F-6/T5 hardening. If BOTH pass (no repro on either), the bug depends on real wall-clock timing or real peer message ordering not captured by a local replay — fall back to Step 2. If BOTH fail identically, the bug predates `1bd671f8e6` and post-dates the ORIGINAL height-3325 fix — bisect further back. **Also check: `512cfe4441` added a weekly fresh-genesis replay regression CI workflow — read it first, it may already implement most of this replay harness, and its run history may already show whether the 2640 condition reproduces in CI.**
- [ ] **Step 1:** If Step 0's bisect isolates a commit, that commit's diff + a targeted regression test IS the fix — skip to Step 3. If Step 0 found no local repro at all, proceed here: identify the exact transaction set/order at height 2640 from the real chain (once inspectable — either from the live frozen node's REST API before any relaunch touches it, or from a state snapshot taken first) and construct a minimal unit/property test that feeds that exact input through `BlockDiffer`/state-hash computation twice and asserts byte-identical output. If it's already flaky in isolation, that's the bug, contained.
- [ ] **Step 2:** Once root-caused, fix with the SAME rigor the height-3325 fix used (real regression test, RED before the fix, GREEN after, no speculative "this probably fixes it" commits). Update `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §8/§9 with the confirmed cause and fix commit, promoting this from "open item" to a numbered, solved bug alongside height-3325.
- [ ] **Step 3:** Add a general regression guard if the root cause suggests one exists elsewhere (e.g. an iteration-order anti-pattern — grep for it across the diff/state-hash codebase, per `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §5's full-history-sweep methodology).

**Do not relax, skip, or work around this task.** A relaunch without it fixed is a coin-flip on which height the same freeze recurs at.

---

### Task 4: Make the commit-generator workflows fail loudly and verify the committee

**Files (infra repo root):**
- Modify: `.github/workflows/auto-commit-generators.yml` — retry loop :103-108; conflating `else`-branch echo `"Gen-0: already committed or sign failed"` at :111 (this is prose in an echo string, not an `||` operator — replace with two distinct branches, not a boolean-logic edit); sole `continue-on-error: true` at :145 (val-0 ONLY — gen-0/gen-1 have none, so this is a 1-line removal in this file); `Report finality` SSH probe at :169-181 (this is an SSH-based finality check, NOT a `$GITHUB_STEP_SUMMARY` block — the file has no step-summary output at all)
- Modify: `.github/workflows/commit-generators-hotstuff.yml` — `continue-on-error` at :103 (gen-0) AND :146 (gen-1) — 2 lines in this file; retry loop :128-139
- Modify: `monitoring/alerts.yml` (source) + `monitoring/exporter.py` — **[rev.4] confirm the actual deploy mechanism first (see Global Constraints — `clusters/testnet/monitoring/` is NOT a copy of these, it's Flux/Helm manifests)**

**Endpoint fact:** `GET /generators/at/{height}` EXISTS (`GeneratorsApiRoute.scala:16`, registered `Application.scala:857`, no API key). Takes a HEIGHT, serves only the CURRENT and NEXT generation period (guard `reqGenerationPeriod <= currGenerationPeriod.next`, :17-18), returns **HTTP 404 with body `[]`** out of range (:30), folds in un-finalized liquid commits for the next period (`CommonGeneratorsApi:77-87`). Verification MUST check the HTTP status — a naive `jq length` reads 404-`[]` as "0 generators".

- [ ] **Step 1:** Rewrite the broadcast loop in both workflows: `set -euo pipefail`; sign failure → hard error; `break` ONLY on a response containing `"id"` (success) or the specific already-committed error; retry on `not enough connections` and curl network errors; any other error → `exit 1`. Remove `continue-on-error` (1 line in auto-commit, 2 in hotstuff).
- [ ] **Step 2:** Post-commit verification: poll `/transactions/info/<id>` until confirmed; then `curl -s -o body -w '%{http_code}' https://<node>/generators/at/<nextPeriodStart>` → require HTTP 200 AND `jq length > 0`; fail otherwise.
- [ ] **Step 3:** Alert `CommitteeGapUpcoming` (critical): current period > 60% elapsed, next-period committee empty. **First confirm how `alerts.yml`/`exporter.py` reach the running Prometheus/Alertmanager instance** (grep the Helm values / ConfigMap wiring in `clusters/testnet/monitoring/kube-prometheus-stack.yaml` and `metrics-exporter.yaml` for a reference back to the source files) — do not assume "edit source, Flux rolls it" without confirming that mechanism exists. Close or supersede alert issue #148.
- [ ] **Step 4:** Validate YAML + `promtool check rules` (docker). **⛔ Live dispatch go-ahead needed:** dispatch each workflow once against the CURRENT (dead) chain expecting a LOUD failure. Commit: `fix(ci): commit-generator workflows fail loudly + verify next-period committee via /generators/at (root cause of silent committee gaps)`.

---

### Task 5: Publish protobuf-schemas 1.6.6 to Maven Central (operator) and remove ONLY the schema source-build step

**Files:** DecentralChain monorepo (`packages/sdk/protobuf-schemas/pom.xml`, workflow); node-scala `.github/workflows/check-pr.yaml:78` (the `protobuf-schemas` `mvnw install` line ONLY); `build.sbt:252-255` stays

**Fact:** `check-pr.yaml:44-48` sparse-checks-out FOUR monorepo packages and installs them all into `.m2` (:78 protobuf-schemas, :79 curve25519, :80 blst, :81 groth16, plus a Rust `cargo build` for groth16's JNI lib at :57-73). `build.sbt:252-255`'s `Resolver.mavenLocal` serves all four. curve25519/blst/groth16 are ALREADY on Central; only protobuf-schemas is going there now. **Removing the sparse-checkout or `mavenLocal` would break CI for the other three.**

- [ ] **Step 1 (⛔ operator):** **[rev.4] PRE-CHECK FIRST — `packages/sdk/protobuf-schemas/pom.xml:20` currently declares `<version>1.6.5</version>` while node-scala's `Dependencies.scala:43` pins `1.6.6`.** Before dispatching, confirm `.github/workflows/_publish-maven.yml` (the reusable publish workflow) actually rewrites the pom version from its `version` input (e.g. via `mvn versions:set` or equivalent) — if it does NOT, the pom must be bumped to 1.6.6 and committed FIRST, or dispatching with `-f version=1.6.6` will silently republish 1.6.5 again and this whole step "succeeds" while Central still lacks what node-scala needs. Once that's confirmed/fixed: from `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain`, `gh workflow run publish-protobuf-schemas.yml -f version=1.6.6 -f run-audit-profile=false`, approve the `maven-central-release` environment gate. Poll for BOTH: `curl -s https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/maven-metadata.xml | grep 1.6.6` AND `curl -sI https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/1.6.6/protobuf-schemas-1.6.6-protobuf-src.jar` for HTTP 200 (the metadata alone doesn't prove the `protobuf-src` classifier artifact — the one node-scala actually depends on — was published). Optionally add `NVD_API_KEY`.
- [ ] **Step 2 (⛔ ONLY after BOTH of Step 1's polls pass — this is the one step in this entire plan that can break CI for every branch if run early):** delete ONLY `check-pr.yaml:78` (the protobuf-schemas install line). Keep the sparse-checkout, the Rust build, the other three installs, and `Resolver.mavenLocal`. Prove Central resolution: `sbt -Dsbt.ivy.home=/tmp/ivy-check "node/update"` shows protobuf-schemas 1.6.6 fetched from `repo1.maven.org`. Commit: `build(ci): resolve protobuf-schemas 1.6.6 from Maven Central (curve25519/blst/groth16 still source-built)`.

---

### Task 6: scalafmt debt to zero in TEST sources and gate them

**[rev.4] Must run AFTER Task 1, not in parallel with it — Task 1 rewrites/renames ~50 test files; running `scalafmtAll` before that finishes makes the "whitespace-only" verification meaningless and guarantees merge conflicts with Task 1's own edits.**

**Fact:** CI ALREADY gates `Compile` formatting — `build.sbt:304` `compilePRRaw` runs `scalafmtCheck.all(ScopeFilter(inAnyProject, inConfigurations(Compile)))`, invoked by `check-pr.yaml:89` `sbt --batch checkPR`. The gap is the `Test` configuration only. 1,765 tracked `.scala` files; `.scalafmt.conf:7-8` excludes only `lang/.../parser/Parser.scala`. Re-derive the current unformatted-file count at execution time (`sbt scalafmtCheckAll` dry run) rather than trusting any previously-quoted number — it drifts with every commit that touches test sources.

- [ ] **Step 1:** `sbt scalafmtAll`; confirm whitespace-only (`git diff -w --stat` shows zero non-whitespace changes; spot-check 3 files). Commit: `style: scalafmt test sources (Test configuration was never gated)`.
- [ ] **Step 2:** In `build.sbt:304` widen the ScopeFilter to `inConfigurations(Compile, Test)` (or switch to `scalafmtCheckAll`). Push the branch, open a draft PR, confirm `checkPR` green. Commit: `ci: gate scalafmt on Test sources too`.

---

### Task 7: Docs closure — no document may still describe the gated design

**[rev.4] Must run after Task 2 — it verifies Task 2's divergence-doc section and reconciles the genesis config's feature list against Task 2's decision.**

**Files:** `docs/hotstuff-audit-readiness.md` (**[rev.4] the plan's prior line-number citations `:152/:156/:259` are stale and will drift again — do not cite fixed line numbers here; instead run the Step 1 grep and handle every hit it finds.** Known content to preserve rather than delete: item 8 (BlsCryptoV2 activation-boundary window, currently ~:247-270) contains the coupling rationale between the equivocation-proof DST fix and the crypto version — **rewrite as a historical note, don't delete, it records real institutional memory**; the "1.6.5 published, verified live at Central" line (~:193-194) is factually TRUE and should stay — the actual gap to document is that `Dependencies.scala` now pins 1.6.6 and Central doesn't have that yet, gate this claim on Task 5; §8 checklist needs the "1.6.5 published" line updated to reflect the 1.6.6 situation; add the "every node from genesis" rule and state plainly that deleting the gates removes the loud UNIMPLEMENTED safety net for these two behaviors), `docs/hotstuff-bls-crypto-audit-2026-08-31.md` (H2/M2 STATUS → fixed unconditionally), `docs/hotstuff-bft-audit-2026-08-31.md`, `docs/superpowers/plans/2026-09-02-bls-crypto-v2.md`, `docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md`, `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-evidence.md`, **[rev.4] also missing from prior revs:** `docs/superpowers/plans/2026-08-22-hotstuff-equivocation-slashing.md`, `docs/superpowers/plans/2026-08-23-upstream-sync-port.md`, `docs/superpowers/plans/2026-08-30-testnet-final.md`, `docs/superpowers/specs/2026-09-02-hotstuff-lag-reanchor-design.md` (all need supersession banners for the same reason), `node/genesis-dcc-testnet-relaunch.conf` (stale comment at **:29-30**, `pre-activated-features = [1..25]` list at **:31** — reconcile against Task 2's feature-26 activation-path decision), `docs/consensus-divergences-from-upstream.md` (verify Task 2's reward section landed), `/Users/jourlez/Documents/Code/Blockchain/CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` (checkpoint: changes since `49809b487c`, feature-id decision, stall root cause + fix from Task 3b, relaunch tag), `version.sbt` (**[rev.4] bump BOTH `git.baseVersion` AND the separate `ThisBuild / version` line** — currently `1.7.0` in both), CHANGELOG/release notes if the repo has one (grep for it).

- [ ] **Step 1:** `git grep -n "feature 29\|feature 30\|feature-29\|feature-30\|BlsCryptoV2\|HotStuffEquivocationEvidence\|legacy DST\|_NUL_\|kept HERE FOREVER" -- docs node/genesis-dcc-testnet-relaunch.conf ../../CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` — every hit rewritten or wrapped in a historical-note banner (never bare-deleted where it records real reasoning — see the hotstuff-audit-readiness.md note above).
- [ ] **Step 2:** Bump both `version.sbt` lines; write the changelog entry. Commit: `docs(consensus): registry mirrors Waves; DCC improvements unconditional; supersede gated designs; bump version`.

---

### Task 8: Final whole-branch review, full-suite gate, image build, merge, tag

- [ ] **Step 1:** `/Users/jourlez/.claude/plugins/cache/superpowers-dev/superpowers/6.1.1/skills/subagent-driven-development/scripts/review-package $(git merge-base dev HEAD) HEAD` → whole-branch adversarial review (most capable model) with this plan's Global Constraints as the lens. Fix Critical/Important; re-review.
- [ ] **Step 2:** Full gate: `sbt "node/compile" "node-testkit/compile" "node-tests/test"` → 0 failed (record count, compare against Task 1 Step 1's baseline). Then `sbt "node-it/docker"` and run `FourNodeHotStuffTestSuite` + `FourNodeHotStuffAuthoritativeTestSuite` (locally if Docker allows, else on CI). Record in the reference doc.
- [ ] **Step 3:** Merge `feat/testnet-final-source` → `dev` (`--no-ff`), push, tag `testnet-relaunch-<yyyymmdd>`; open PR `dev` → `main`. **[rev.4] Note: `.github/workflows/on-release-published.yml` fires on a GitHub *Release* being published, not on a bare git tag — decide and state explicitly whether Task 8/9 intends to cut a Release (auto-triggers the image publish) or dispatch the image workflow manually (Step 4).**
- [ ] **Step 4:** Build + publish the node image from the tag: `sbt buildTarballsForDocker` (`build.sbt:281-290` → `docker/target/dcc.tgz`) → **[rev.4] the workflow is `.github/workflows/publish-node-scala.yml`** (`workflow_dispatch` inputs `docker-tags`/`base-image`, or `workflow_call` inputs `raw-docker-tags`/`environment`/`ref` with output `digest`; callers are `deploy-node-scala.yml:38` and `on-release-published.yml:46`; there's also a one-off `promote-vps-image.yml` for SSH-based recovery, not relevant here) → record the image digest. Task 9 consumes this digest.

---

### Task 9: Testnet relaunch runbook (operator-executed; this task produces the runbook and the config diff, not the mutation)

**⛔ PRECONDITION: Task 3b must be complete, fixed, and verified before this task runs.** Do not write or execute a relaunch runbook while Task 3b is open.

**Files (infra repo root — [rev.4] no `RELAUNCH-*.md` exists yet, confirmed; root already holds `DEPLOY.md`, `RUNBOOK-mainnet-edge-and-key-rotation.md`, `RUNBOOK-t0-soak-and-t2-audit.md`, `MAINNET-LAUNCH.md`, `MAINNET-READINESS.md` — new file belongs alongside these):**
`RELAUNCH-<date>.md` (new), `node-config/testnet/dcc.conf:23-29` (pre-activated block), `clusters/testnet/apps/nodes.yaml` (THREE pre-activated blocks: :53-59, :200-206, :326-332), image references — **[rev.4] these are NOT uniform:** the three LKE node containers pin by DIGEST at `nodes.yaml:476/:604/:721` (`ghcr.io/decentral-america/node-scala@sha256:...`), while the fourth node (VPS, docker-compose) uses a TAG — the "mixed versions = silent split" check must cover both mechanisms, not just one config format. **[rev.4] Also add: `supported-features`/`blockchain.custom.functionality` config for feature 26's vote-activation** (Task 2's activation-path requirement — verify the exact DCC config key name first).

- [ ] **Step 1:** Write the runbook from the verified 2026-08-31 procedure (there is no workflow; it was manual): `sbt generateGenesis node/genesis-dcc-testnet-relaunch.conf` (re-verify every seed via `GenesisBlockGenerator.toFullAddressInfo`) → commit new `timestamp`/`block-timestamp`/`signature` into `dcc.conf` + `nodes.yaml` → pre-activated features per Task 2's decision (`1..25 = 0`; never 27+; 26 is NOT pre-activated but its `supported-features` voting config IS added per the note above) in all FOUR blocks → image = Task 8's digest/tag on ALL FOUR nodes (verify both the LKE digest pins AND the VPS tag match — mixed versions = silent split, no gate protects this anymore) → wipe the 3 LKE PVCs + the VPS node's data volume → restart → confirm `/node/version` identical on all four and `/activation/status` shows exactly the intended set → run the Task 4 workflow and confirm the FIRST period's committee via `/generators/at/<next start>` (HTTP 200, non-empty) before period end → reset matcher/BPS/scanner, re-fund faucet + treasury → arm alerts.
- [ ] **Step 2:** Post-relaunch evidence plan (= audit-readiness §8): 72h soak with recorded crash/partition/committee-rotation/equivocation drills; live epoch transition observed (T10); one live equivocation exercised end to end with `slashing-enabled=true` on ONE node first; then decide fleet-wide. Also monitor for feature 26's actual activation (it needs a real voting period to pass) and confirm the reward-economics change takes effect as expected once it does. External audit remains the mainnet gate.

---

## Decisions encoded (change the plan if you disagree)

| # | Decision | Rationale / caveat |
|---|---|---|
| D1 | Register upstream 26 WITH ported logic, faithfully (all 4 hunks, operator-approved 2026-09-02) | `implemented = dict.keySet` (`BlockchainFeature.scala:73`) is the unknown-feature safety net; placeholder = silent fork. **[rev.4] Registering it is not enough — it needs a real activation-vote config path (Task 9), or the operator's approved change never executes.** |
| D2 | Renumber 27/28 to match upstream | Free under fresh genesis (non-votable, never on-chain); mainnet legacy genesis pre-activates `[1..13,15,16]` only |
| D3 | No carry-forward committee mechanism | Premise was false — `appender:74-78` already falls back to classic PoS |
| D4 | `authoritative` floor stays advisory; `slashing-enabled` off at relaunch | `HotStuffSettings.scala:88-90`; audit F-1 option (b); flip slashing after the first live drill |
| D5 | T11 first-boot window stays documented | Operational rule; not worth consensus code |
| D6 | Rule: every node on a chain runs the binary from genesis | Removes the loud UNIMPLEMENTED shutdown D1 relies on, for exactly these two behaviors — a stale node forks silently. Future rule changes on live mainnet still use features |

## Requires the human operator (blocking items marked ⛔)

1. ✅ **Economic decision on feature 26 — RESOLVED:** port faithfully. **[rev.4] Not fully actioned until Task 9 adds the vote-activation config — flag this to the operator explicitly when Task 9 is reached.**
2. ✅ **Node logs — RESOLVED:** root cause found (Task 3). **[rev.4] The premise about what code was running was corrected — see Task 3b's rewrite.**
3. ⛔ **Task 3b is the critical-path blocker.** Task 9 must not run until it's solved.
4. ⛔ **Maven Central publish** of protobuf-schemas 1.6.6 + environment-gate approval — blocks Task 5 Step 2. **[rev.4] Pre-check the pom-version rewrite mechanism first (see Task 5 Step 1) or the publish may silently be a no-op for the version that matters.**
5. Optional `NVD_API_KEY` secret.
6. ✅ **Testnet disposability — CONFIRMED.**
7. ⛔ Live go-aheads still needed: Task 4 Step 4 workflow dispatch; all of Task 9.
8. **The SSH key used for Task 3's log pull must be rotated** — it was pasted into a chat session.

## Self-Review

- Spec coverage: registry parity (T1, T2 + activation path), unconditional improvements (T1), stall root cause (T3 — done) + actual fix (T3b — corrected premise, new Step 0 bisect), automation (T4), schema (T5 — pom pre-check added), format gate (T6 — reordered after T1), docs + version (T7 — reordered after T2), final gate + image + tag (T8 — Release-vs-tag clarified), relaunch + soak (T9 — feature-26 activation config added). All 61 audit findings folded in (P0/P1/P2 from the audit report).
- Placeholder scan: ⛔ steps are operator gates or genuine unsolved debugging, and say so.
- Type consistency: `popMessage(chainId, sender, endorserPk, periodStart)`, `PopDst`, `VoteDst`, the renamed `Bls*DomainSeparationTag` constants named identically across T1/T7; `/generators/at/{height}` semantics identical in T4 and T9.
- Ordering fixed: T1 → T6 → T2 → T7 → T8, strictly; T3b/T4/T5-step-1 may run in parallel with anything.
