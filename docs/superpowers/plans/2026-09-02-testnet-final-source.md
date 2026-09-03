# Testnet Final Source — Waves-Identical Feature Registry, DCC Improvements Unconditional (rev. 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Rev. 3 (2026-09-02):** two operator decisions landed. (1) Feature 26's live hunks (reward reset to 20 DCC, retiring the 10x boost) are APPROVED for a faithful port, matching Waves exactly — Task 2 is no longer gated. (2) The 2026-09-01 stall was root-caused via real VPS logs (not the earlier empty-committee guess, which was wrong): the chain froze at height 2639 because 102 consecutive attempts to seal block 2640 each computed a DIFFERENT wrong state hash (`InvalidStateHash`, non-deterministic — same bug *class* as the already-solved height-3325 divergence, a new occurrence, not yet traced to a line). Former Task 3 (root-cause) is done; a new **Task 3b** (find + fix the actual code defect) is inserted and is now the hardest open item — the relaunch cannot proceed without it, or the new chain freezes again. Full incident record: `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §8 (2026-09-02 entry).
>
> **Rev. 2** (superseded by rev. 3's decisions): rev. 1 was adversarially audited against the real tree; 29 corrections applied (wrong anchors, a false CI premise, a would-break-CI step, a missing image-build step, and upstream feature 26's real economics).

**Goal:** Produce the final node-scala source for the testnet relaunch: feature registry mirroring upstream Waves 1.6.x (ids 1–28), every DCC consensus/crypto improvement shipped as the ONLY behavior (no DCC-native feature ids, no legacy paths, no activation gates), the live stall root-caused from real logs, the commit-generator automation made fail-loud, and a full-suite-green, reviewed, tagged, image-built release.

**Architecture:** Three decisions drive everything. (1) Testnet is disposable and mainnet's legacy chain has no BLS/HotStuff/commitment history (its genesis pre-activates `[1..13,15,16]` — feature 25 never activated, so no BLS bytes exist anywhere we keep), so the BLS domain-separation + bound-PoP fix (was feature 30) and block-carried equivocation evidence (was feature 29) need no activation gate — they become unconditional and 29/30 are deleted. (2) The registry mirrors upstream, which means registering upstream's 26 `AdjustedBlockRewardDistribution` WITH its logic ported faithfully (operator-approved 2026-09-02: DCC adopts the same reward economics Waves runs — 20-DCC reward, retiring the 10x boost, once 26 activates) — never as a placeholder, because `implemented = dict.keySet` is the unknown-feature safety net. (3) The 2026-09-01 stall is a real, unsolved code defect (state-hash non-determinism at height 2640, same class as height-3325) that must be found and fixed before any relaunch, or it recurs at the next height that triggers the same condition.

**Tech Stack:** Scala 3, sbt, ScalaTest, blst BLS, protobuf-schemas 1.6.6 (monorepo), GitHub Actions (infra), Kubernetes/LKE + Linode VPS (testnet).

## Global Constraints

- Branch off `dev` @ `36caa7edc1` for node-scala work; `feat/bls-crypto-v2` (**19 commits** ahead of dev, contains the H2/M2 code, gated) is the source to strip gates FROM — merge it first (Task 1), then remove gates on top. Never re-implement what that branch already has.
- Registry target (verbatim from `upstream-waves/version-1.6.x` `features/BlockchainFeature.scala`): 1–25 unchanged (id 1 keeps DCC wording), `26 = AdjustedBlockRewardDistribution` (in dict, logic ported), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (both not-exposed/not in dict, as upstream). No id > 28.
- NEVER register a feature in `dict` whose logic is not implemented (`BlockchainUpdaterImpl.scala:130-147` warn/`forceStopApplication`, `:235-241` append rejection — both key on `dict.keySet`; a placeholder converts a loud UNIMPLEMENTED shutdown into a silent fork).
- v2 crypto is the ONLY crypto: DSTs `…_POP_` / `…_ENDORSE_` / `…_HSVOTE_`; PoP message = `chainId(1) ‖ senderPublicKey(32) ‖ endorserPublicKey(48) ‖ generationPeriodStart(4)` = 85 bytes (asserted today at `CommitToGenerationPopMessageSpec:26`). The legacy `_NUL_` tag is deleted from production code entirely.
- `hotstuffConflicts` is always valid when well-formed (no activation gate). `slashing-enabled` stays an operator flag (default `false`).
- `HotStuffSettings` unchanged: `authoritative=false` default (advisory floor — audit F-1 decision = advisory), `slashingEnabled=false`, `maxTargetLagFraction=0.25`.
- Infra repo paths are repo-root-relative: `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra/.github/workflows/...`, `.../infra/monitoring/...` (source) vs `.../infra/clusters/testnet/monitoring/...` (deployed copy — edit source, let Flux roll it), runbooks live at the infra repo ROOT (`DEPLOY.md`, `RUNBOOK-*.md`), not under `docs/`.
- FOREGROUND sbt only in every task (background-and-wait has wedged multiple agents). Full-suite gate command: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-testkit/compile" "node-tests/test"` (~8-10 min; baseline 2,943 tests / 0 failed).
- Commits sole-authored by jourlez; NEVER add Co-Authored-By / AI attribution. No `git worktree` (sbt-git breaks); use a full clone if isolation is needed.
- Infra: read-only investigation first; no live-node mutation (restarts, PVC wipes, config rollouts, workflow dispatch against live nodes) without the explicit go-ahead step named in the task.
- Review packages: there is no `scripts/review-package` in this repo. Use the superpowers script by absolute path: `/Users/jourlez/.claude/plugins/cache/superpowers-dev/superpowers/6.1.1/skills/subagent-driven-development/scripts/review-package BASE HEAD` (or `git log --oneline BASE..HEAD; git diff --stat BASE..HEAD; git diff -U10 BASE..HEAD > file`).

---

### Task 1: Merge the H2/M2 code branch, then remove features 29 and 30 with all gates

**Files (all on `feat/testnet-final-source` after the merge; anchors verified on `feat/bls-crypto-v2`):**
- Merge: `feat/bls-crypto-v2` → new branch `feat/testnet-final-source` (off `dev`)
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (:32 `HotStuffEquivocationEvidence`, :33 "28 is BURNED" comment, :34 `BlsCryptoV2`, :69-70 dict entries — remove; 26/27 handled in Task 2)
- Modify: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (delete `supportsHotStuffEquivocationEvidence` :312-313, `supportsBlsCryptoV2` :323)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala` (**:369-371** feature-29 gate → delete; :388-395 activation-period boundary block → delete; :397/:446 DST → constants)
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` (:344-350 `withHotStuffConflicts` gate + its comment → delete)
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala` (:11-17 legacy tag + "kept HERE FOREVER" scaladoc → delete; :75/:88/:151 `dst` defaults → remove, params stay required), `crypto/bls/BlsSignature.scala:18` (stale "legacy paths" scaladoc)
- Modify: `block/BlockEndorsement.scala` (:27 `_ENDORSE_` unconditional), `consensus/hotstuff/HotStuffQuorum.scala` (:64 `_HSVOTE_` unconditional; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC`), `HotStuffEquivocationProof.scala` (dst = HSVOTE constant), `HotStuffCoordinator.scala`, `HotStuffEngine.scala` (`EngineState.cryptoV2`), `HotStuffVotePool.scala`, `NodeHotStuffEffects.scala` (`signVote(dst)` → constant), `transaction/CommitToGenerationTransaction.scala` (:70-77 v2 branch only; drop `cryptoV2` from `popMessage`/`popDst`/`mkPopSignature`), `transaction/TransactionFactory.scala`, `state/BlockEndorser.scala` (remove `carrierHeight` :110/:123/:154/:160 — its ONLY use is the era read at :160), `state/EndorsementFilter.scala` (drop `cryptoV2` field), `state/EndorsementStorage.scala`, `state/diffs/BlockDiffer.scala` (:643), `state/diffs/CommitToGenerationTransactionDiff.scala` (:26), `settings/BlockchainSettings.scala` (:155 MAINNET `BlsCryptoV2.id -> 1`, :181 STAGENET — delete; compile error otherwise), `Application.scala` (:351-355 provider), `api/http/TransactionsApiRoute.scala` (:261), `api/http/requests/CommitToGenerationRequest.scala`, `utils/UtilApp.scala` (:347/:357 `blsCryptoV2Era` + :415-423 tags + the `--bls-crypto-v2-activation-height` option → delete/v2), `utx/UtxPoolImpl.scala` (comment), `node/src/main/resources/network-defaults.conf:47` (`30 = 1` → delete), `application.conf` BlsCryptoV2 comment block, `node/testkit/.../TxHelpers.scala`
- Delete tests (gate-only): `settings/BlsCryptoV2PreActivationSpec.scala`, `state/BlsCryptoV2ActivationHelperSpec.scala`, `state/BlsCryptoV2RollbackDeterminismSpec.scala`, `state/appender/BlsCryptoV2EquivocationProofBoundarySpec.scala`, `utils/UtilAppSpec.scala` (tests only `blsCryptoV2Era`), and case 2 (:103-117) of `state/appender/HotStuffEquivocationValidationSpecification.scala`
- Edit tests to v2-only: `crypto/bls/BlsUtilsTest.scala` (drop "legacy tag remains the BlsUtils-level default"; keep both 3x3 matrices), `crypto/bls/BlsLegacyVectorRegressionSpec.scala` → `BlsVectorRegressionSpec.scala` (re-pin, see Step 5), `transaction/CommitToGenerationPopMessageSpec.scala` (drop legacy-layout cases; keep 85-byte layout + chainId/sender-differ), `state/diffs/CommitToGenerationPopV2Spec.scala` (keep transplant-by-domain; collapse pre/post pairs), `finalization/BlsCryptoV2EndorsementSpec.scala` (8→4), `state/BlsCryptoV2SnapshotPathPopSpec.scala`, `state/BlockEndorserSpec.scala` (remove `carrierHeight` block :136), `features/BlockchainFeaturesRegistrySpec.scala` (assert 29/30 ABSENT), `finalization/conflict/MultipleConflictEndorserSuite.scala:89` + `mining/HotStuffEquivocationEvidenceE2ESpecification.scala:55` (drop `.addFeatures(HotStuffEquivocationEvidence)`), ~20 `consensus/hotstuff/*Specification` (drop positional `cryptoV2 = false` / legacy-dst args)

**Interfaces:**
- Produces: `BlsUtils.{BlsPopDomainSeparationTag, BlsEndorseDomainSeparationTag, BlsHsVoteDomainSeparationTag}` (renamed from `…TagV2`); `HotStuffQuorum.VoteDst`; `CommitToGenerationTransaction.{popMessage(chainId, sender, endorserPk, periodStart), PopDst}`; `HotStuffQuorum.verifyVote(vote, committee)` / `formQC(votes, committee)` / `verifyQC(qc, committee)` without `cryptoV2`.

- [ ] **Step 1: Create branch and merge the H2/M2 code**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git checkout dev && git pull --ff-only
git checkout -b feat/testnet-final-source
git merge --no-ff feat/bls-crypto-v2 -m "Merge branch 'feat/bls-crypto-v2' into feat/testnet-final-source (H2/M2 code; gates removed in following commits)"
sbt "node/compile" "node-testkit/compile"
```
Expected: clean merge (strict descendant), compile green.

- [ ] **Step 2: Write the failing registry test**

In `node/tests/src/test/scala/com/decentralchain/features/BlockchainFeaturesRegistrySpec.scala` replace the 29/30 assertions with:

```scala
  "the registry has no DCC-native feature ids" in {
    BlockchainFeatures.feature(29) shouldBe None
    BlockchainFeatures.feature(30) shouldBe None
    BlockchainFeatures.implemented.max should be <= 28.toShort
  }
```

- [ ] **Step 3: Run to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.features.BlockchainFeaturesRegistrySpec"` — Expected: FAIL (`feature(29)` is `Some`).

- [ ] **Step 4: Remove feature 29 + its gate**

`BlockchainFeature.scala`: delete the `HotStuffEquivocationEvidence` val (:32), its dict entry, and the "Id 28 is deliberately BURNED" comment (:33). `Blockchain.scala`: delete `supportsHotStuffEquivocationEvidence` (:312-313). `appender/package.scala` `validateHotStuffEquivocationProofs`: delete the `raiseUnless(blockchain.supportsHotStuffEquivocationEvidence(blockHeight))(...)` step at **:369-371** (keep every other rule: consistency, epoch==period, bounds, dedup, known-conflict, overlap, signatures). `Miner.scala` `withHotStuffConflicts`: delete the `if (!blockchainUpdater.supportsHotStuffEquivocationEvidence(...)) voting else` wrapper and its comment (:344-350), keep the `generationPeriodOf` match. `HotStuffEquivocationValidationSpecification.scala`: delete case 2 (:103-117). Drop `.addFeatures(BlockchainFeatures.HotStuffEquivocationEvidence)` at `MultipleConflictEndorserSuite.scala:89` and `HotStuffEquivocationEvidenceE2ESpecification.scala:55`.

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.state.appender.* com.decentralchain.mining.* com.decentralchain.features.*"` — green except the registry test still failing on 30.

- [ ] **Step 5: Remove feature 30 — crypto becomes unconditional**

`BlsUtils.scala`: delete `BlsDomainSeparationTag` and its :11-17 "kept HERE FOREVER / chain split" scaladoc (obsolete: mainnet never activated feature 25, so no BLS bytes exist pre-25 — say this in the commit body). Rename `Bls{Pop,Endorse,HsVote}DomainSeparationTagV2` → `Bls{Pop,Endorse,HsVote}DomainSeparationTag`. Remove the `= BlsDomainSeparationTag` defaults at :75/:88/:151 (params stay required). New scaladoc: three contexts, three tags; changing a tag is a chain-identity change (every node ships it from genesis).

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

`BlockEndorsement.scala`: `mkMessage` unchanged (68 bytes); `sign`/`signed`/`signatureValid` drop `cryptoV2`, use `BlsUtils.BlsEndorseDomainSeparationTag`. `EndorsementFilter.scala`: delete the `cryptoV2` field (+ its 3 test constructions). `EndorsementStorage.verifySig`: endorse tag. `BlockEndorser.scala`: delete `carrierHeight` (:110/:123/:154) and the `cryptoV2 = blockchain.supportsBlsCryptoV2(carrierHeight.toInt)` read (:160); `castVote(votingHeight, endorsedHeight, ...)`. `appender/package.scala`: `endorsementDst` → constant; `proofDst` → `HotStuffQuorum.VoteDst`; delete the boundary block :388-395.

`HotStuffQuorum.scala`: `val VoteDst: String = BlsUtils.BlsHsVoteDomainSeparationTag`; delete `voteDst(cryptoV2)`; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC` and from `HotStuffVotePool` / `HotStuffEngine` (`EngineState.cryptoV2`) / `HotStuffCoordinator` (`cryptoV2: () => Boolean` provider and both read sites) / `NodeHotStuffEffects.signVote` (pass `VoteDst`) / `Application.scala:351-355`. `HotStuffEquivocationProof.signaturesValid` drops `dst`, uses `HotStuffQuorum.VoteDst`; both call sites simplify.

`BlockchainSettings.scala`: delete `BlsCryptoV2.id -> 1` at :155 and :181. `network-defaults.conf:47`: delete `30 = 1`. `application.conf`: delete the BlsCryptoV2 comment block. `UtilApp.scala`: delete `blsCryptoV2Era` (:347/:357), the `--bls-crypto-v2-activation-height` option, the era arithmetic; sign with `PopDst`. `UtxPoolImpl.scala`: keep the pack-side era comment minus the BlsCryptoV2 mention. `Blockchain.scala`: delete `supportsBlsCryptoV2` (:323). `BlockchainFeature.scala`: delete the `BlsCryptoV2` val + dict entry. `BlsSignature.scala:18`: drop the "legacy paths" sentence.

Delete the six gate-only test files. Edit the kept specs to v2-only exactly as listed (each pre/post pair collapses to the post half; every `cryptoV2 = false` / legacy-tag argument removed — the compiler is the checklist).

**Vector re-pin** — `git mv` `BlsLegacyVectorRegressionSpec.scala` → `BlsVectorRegressionSpec.scala`. The one-shot printer was ALREADY deleted (file header :25) — re-create a throwaway printer (do not commit it) that derives the keys from the seeds documented at :29-38 (each seed = the 31-ASCII-byte string + one `0x00` pad = 32 bytes; **signer-1's seed and the PoP seed are intentionally the same string — do not "fix" that**), `generationPeriodStart = 12345`, `chainId = 'D'`, `finalizedId = 32×0x07`, `finalizedHeight = 100`, `endorsedId = 32×0x09`; sign under the three tags; paste the Base64 triples as literals; assert each verifies under its own tag and FAILS under the other two. Rewrite the header:

```scala
/** Pinned byte vectors for the three BLS contexts (PoP / endorsement / HotStuff vote), synthesized from
  * fixed seeds (documented below) and pasted as literals. They pin TODAY'S message layouts + domain tags
  * so an accidental change to either fails loudly. Regenerate ONLY as part of a deliberate, reviewed
  * encoding change — which is a chain-identity change (every node must ship it from genesis). Never
  * regenerate to make a red test green.
  */
```

- [ ] **Step 6: Verify**

Run: `sbt "node/compile" "node-testkit/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.crypto.bls.* com.decentralchain.transaction.* com.decentralchain.finalization.* com.decentralchain.state.* com.decentralchain.consensus.hotstuff.* com.decentralchain.mining.* com.decentralchain.settings.* com.decentralchain.utils.*"` — all green.
Then: `git grep -n "cryptoV2\|supportsBlsCryptoV2\|BlsDomainSeparationTag\b\|_NUL_\|HotStuffEquivocationEvidence\|BlsCryptoV2\|carrierHeight\|voteDst\|popDst\|TagV2" -- node/src node/testkit` → ZERO hits. In `node/tests` the only permitted hits are the registry spec's "absent" assertions.

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
(second commit for the Step 5 work)
```bash
git commit -m "refactor(crypto): per-context BLS domain tags + chain/sender-bound PoP are the only crypto (remove feature 30)

Deletes the legacy _NUL_ tag (its 'kept forever / chain split' rationale
was obsolete: no BLS bytes exist on any chain we keep, since mainnet never
activated feature 25), every cryptoV2 gate/provider, the activation-period
boundary rule, and the rollback-across-activation scaffolding. BLS-audit
H2 and M2 fixes ship unconditionally; vectors re-pinned under the three
tags. Registry has no id > 28."
```

---

### Task 2: Register upstream feature 26 with its logic ported faithfully; realign 27/28

**Decision (operator-approved 2026-09-02): port `f1bedddb2e` faithfully, matching Waves exactly.** DCC adopts the same reward economics upstream runs once 26 activates: reward resets to `AdjustedFullReward = 20 * UnitsInWave`-equivalent, and feature 23's `blockRewardBoost` retires (returns 1). No DCC-specific constant substitution. This is a real monetary-policy change (DCC's current reward is 6 DCC/block with a live 10x boost period) — it is intentional, not a bookkeeping default, and both the commit body and `consensus-divergences-from-upstream.md` must say so plainly so a future reader doesn't mistake it for an oversight.

**Files:**
- Read: `git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala`; upstream commit `f1bedddb2e` (`git show f1bedddb2e --stat`, then each file).
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` — `26 = AdjustedBlockRewardDistribution` (in dict), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (not exposed, not in dict)
- Modify (port targets, DCC counterparts of upstream's 5 files): `state/diffs/BlockRewardCalculator.scala`, `state/Blockchain.scala`, `state/BlockchainUpdaterImpl.scala`, `state/diffs/EthereumTransactionDiff.scala`, `state/diffs/ExchangeTransactionDiff.scala`
- Modify: `docs/consensus-divergences-from-upstream.md` (NEW section: reward distribution — DCC has `daoAddress = None` / `xtnBuybackAddress = None` on all presets, documented today only as an inline comment at `BlockchainSettings.scala:147-148/169-170/188-189`; the split table (hunk a) is therefore inert even though it's ported — record that 100% of the 20-unit reward goes to the miner, no DAO/buyback split, by design)
- Test: port `f1bedddb2e`'s spec changes; extend `BlockchainFeaturesRegistrySpec`

**What `f1bedddb2e` actually is — four hunks, all ported faithfully:**

| Hunk | On DCC | Port |
|---|---|---|
| (a) `RewardDistribution` split table (10/2 dao/xtn, 5/6–1/6 remainder) | inert (`daoAddress`/`xtnBuybackAddress` are `None` on all presets — every payout folds to 0) | port as-is |
| (b) `blockRewardBoost` returns 1 once 26 active (retires feature 23's 10×) | **adopted**: DCC's `blockRewardBoostPeriod` (300,000 mainnet / 2,000 testnet) ends when 26 activates | port as-is |
| (c) one-time force-set of voted `blockReward` to `AdjustedFullReward = 20 * UnitsInWave` at activation | **adopted**: DCC's reward becomes 20-equivalent (up from 6) at activation, matching Waves | port as-is, use the SAME constant Waves uses (no DCC substitution) |
| (d) Eth/Exchange gate-widening `isFeatureActivated(25) → 25 || 26` | free no-op on any chain with 25 | port as-is |

**Pre-activation on the relaunch genesis (Task 9): NOT pre-activated.** This is a real reward-economics change; it activates by the ordinary feature-voting process on the relaunched testnet, same as it would on mainnet later — not silently baked into genesis. Confirm this reading holds before Task 9's genesis config is written; if the operator instead wants it pre-activated from block 1, that is a one-line change to Task 9, but the default here is votable.

**Interfaces:**
- Produces: `BlockchainFeatures.AdjustedBlockRewardDistribution = BlockchainFeature(26, "Adjusted Block Reward Distribution")` fully implemented (all four hunks); `ContinuationTransaction` id 27; `LeaseExpiration` id 28. Renumbering 27/28 is FREE only because the relaunch is a fresh genesis (non-votable placeholders never appear on-chain) and mainnet legacy has neither — state in the commit body.

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

- [ ] **Step 2: Port hunk-by-hunk, all four, faithfully.** Map each upstream hunk to its DCC file; keep DCC's `daoAddress = None` handling for hunk (a) (folds to 0, no code change needed there beyond the gate); port hunks (b)/(c) exactly as upstream wrote them — same `AdjustedFullReward` constant value, same boost-retirement logic; port (d) as pure gate-widening. Port upstream's tests for all four hunks and add a RED/GREEN case per hunk proving pre/post-26 behavior (in particular: a case proving the reward is 6-DCC-equivalent before 26 and 20-DCC-equivalent after, and a case proving `blockRewardBoost` returns the boosted multiplier before 26 and `1` after). Renumber 27/28. Presets: **there is no `25` entry to sit beside** — current maps are TESTNET `Map.empty`, MAINNET `{}` after Task 1 (was `{30->1}`), STAGENET `{1..13->0}` after Task 1, devnet conf `{1..15->0}` (gap 16–25 — leave as-is). Do NOT add `26` to any pre-activated-features map — it is votable, not pre-activated (see the decision note above).

- [ ] **Step 3: Verify** — `sbt "node/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.state.* com.decentralchain.mining.* com.decentralchain.settings.*"` green, then registry parity:
```bash
diff <(git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala | grep -o 'BlockchainFeature([0-9-]*, "[^"]*")') \
     <(grep -o 'BlockchainFeature([0-9-]*, "[^"]*")' node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala)
```
Expected: the ONLY difference is id 1's description.

- [ ] **Step 4: Commit** — `feat(consensus): register upstream feature 26 -- reward economics ported faithfully, matching Waves; registry mirrors Waves 1.6.x ids 1-28`. Body: cite `f1bedddb2e`, state plainly that DCC's block reward becomes 20-DCC-equivalent and the 10x boost retires once 26 activates (operator-approved monetary change, not a bookkeeping default), note 27/28 renumbering is safe only under fresh genesis, and that 26 is votable (not pre-activated at the relaunch genesis).

---

### Task 3: ✅ DONE — root-cause the 2026-09-01 stall from node logs; fix the misleading miner message

**Status: root-cause research complete (2026-09-02), via real logs pulled from the VPS.** Full record: `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §8 (2026-09-02 entry), filtered log archive at `node-scala/.superpowers/sdd/stall-2026-09-01-filtered.log` (local, not committed).

**Confirmed sequence:** chain advanced cleanly to height 2639 (`2026-09-01T09:02:15Z`, microblocks/endorsements all healthy). Sealing the next key block (2640), the miner made 102 consecutive attempts (`09:02:23Z`..`10:00:31Z`) and failed EVERY time with `InvalidStateHash(expected, computed)` — **a different expected/computed pair on every attempt**, meaning the state-hash computation is genuinely unstable, not one bad transaction being retried. After the last attempt the miner went silent forever — no crash, no exception, no restart; the container stayed "healthy" (its Docker healthcheck only pings the REST API, not chain advance), which is why surface checks looked fine for 24+ hours. Two `ERROR`-level exceptions in the same window (an unrelated `ERC20Address` length-validation bug in the Ethereum RPC route, and a stray future-timestamped tx rejected a day later) are confirmed noise.

**This is the SAME BUG CLASS as the already-solved height-3325 divergence and the Waves committee-state-hash timing bug (§9 Bug 1/2 in the reference doc) — a NEW occurrence, not yet traced to a specific line.** The earlier hypotheses in this plan (H1 generating-balance floor, H2 conflict exclusion, H3 partial committee, empty-committee halt) are all RULED OUT by the logs: the miner never got far enough to hit any of those checks — it fails computing the block's own state hash, before any committee/conflict/finality logic runs. H6 (`InvalidStateHash` recurrence) is confirmed as the real cause; H4/H5 are irrelevant here.

- [x] Logs obtained (VPS `deploy@66.228.55.154`, `docker logs node-scala-testnet`).
- [x] Root cause written up in the reference doc.
- [ ] **Remaining step: fix `Miner.scala:213`'s misleading "not committed" message anyway (cosmetic; unrelated to the real bug, do alongside Task 3b).** The only existing test referencing the string is a NEGATIVE assertion (`MinerWithFinalitySuite.scala:84` … `shouldBe empty`) — it stays green under any rewording, so WRITE a new positive test that triggers the balance-floor veto and asserts the new message (`generating balance <x> below minimal <y> at height <h>`). Commit: `fix(mining): say what the forge veto actually checks (generating balance floor, not commitment)`.

---

### Task 3b: ⛔ FIND AND FIX the state-hash non-determinism at height 2640 (blocking — the relaunch cannot proceed without this)

**This is the hardest, least-scoped task in the plan.** Task 3 found the symptom; this task finds the cause. Treat it like the original height-3325 investigation (`docs/height-3325-diagnostic-log.md`, and `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §2's "confirmed with a real bidirectional test, not inference" standard) — evidence-first, no guessing, use `superpowers:systematic-debugging`.

**What's known:**
- Height: block 2640 (the key block sealing after liquid height 2639). Generation period boundary: `generationPeriodLength = 100` on testnet, so period `[2601,2700]` — 2640 is mid-period, not a period-boundary block (rules out anything gated on `committedGeneratorsHash`, which only fires at period boundaries).
- Symptom: `MinerImpl`'s own computed state hash disagrees with itself across retries — not a peer-disagreement (`InvalidStateHash(expected, computed)` is a local check before broadcast, confirm via `MinerImpl`/`BlockDiffer` call site), and not a single-transaction issue (the miner would otherwise fail identically each retry if one bad tx were the cause; it doesn't — the hash pairs differ).
- What changed on this chain vs the pre-08-30 chain: this is a FRESH genesis (2026-08-31), running the dev@ade354adcb-era fix for height-3325 PLUS everything merged since (T5 equivocation evidence, BFT/BLS/F-6 hardening, all merged 2026-09-02 — but the VPS is running an OLDER image, `ghcr.io/decentral-america/node-scala:testnet-1bd671f8e6`, per `docker ps` — verify this commit's actual content before assuming any post-`1bd671f8e6` change is implicated).
- Non-determinism across retries at a FIXED height, with (presumably) the same transaction set each retry, points at something order-dependent or time-dependent in the state-hash computation itself — candidates to investigate first (do not assume, verify each): (a) transaction ordering non-determinism in the mempool/UTX pack step feeding slightly different tx sets/orders into each retry; (b) a HashMap/Set iteration-order dependency inside `TxStateSnapshotHashBuilder` or a diff accumulator that isn't using a canonically-ordered collection; (c) the same class of bug height-3325 was: an old vs new rule disagreement for some specific transaction type present at this height (check what tx types actually landed in the 09:02-10:00 window — the log's `MicroBlock(...txs=N)` lines around height 2639 name tx counts; cross-reference `/transactions/info` on-chain for the actual tx list at that height once the historical block is inspectable); (d) a genuinely non-deterministic source (wall-clock, `System.nanoTime`, unordered `Future`/parallel collection) reaching into diff computation.

**Files:** unknown until root-caused — likely candidates to inspect first: `state/diffs/BlockDiffer.scala`, `state/TxStateSnapshotHashBuilder.scala` (or wherever state-hash accumulation lives — grep for `stateHash`/`checkStateHash`), `mining/MinerImpl.scala` (the retry loop itself — confirm it rebuilds the tx set identically each retry or if IT is the source of the differing input), `state/diffs/` transaction-specific diffs active at height ~2640 (identify from the real tx list first).

- [ ] **Step 1: Reproduce locally, not just read logs.** Attempt to replay the real chain (genesis → height 2639, using the actual relaunch genesis config) against the CURRENT dev/final-source build in a local/CI environment, and see if the same `InvalidStateHash` recurs at 2640. If it reproduces locally, this becomes a normal debuggable failure (bisectable, attachable to a debugger) rather than a live-log-only mystery — hugely preferable. If it does NOT reproduce (e.g. depends on real wall-clock timing, real peer message ordering, or the specific older image build), fall back to Step 2.
- [ ] **Step 2 (if no local repro):** identify the exact transaction set/order at height 2640 from the real chain (once inspectable — either from the live frozen node's REST API before any relaunch touches it, or from a state snapshot taken first) and construct a minimal unit/property test that feeds that exact input through `BlockDiffer`/state-hash computation twice and asserts byte-identical output. If it's already flaky in isolation, that's the bug, contained.
- [ ] **Step 3:** Once root-caused, fix with the SAME rigor the height-3325 fix used (real regression test, RED before the fix, GREEN after, no speculative "this probably fixes it" commits). Update `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §8/§9 with the confirmed cause and the fix commit, promoting this from "open item" to a numbered, solved bug alongside height-3325 and the Waves committee-state-hash bugs.
- [ ] **Step 4:** Add a general regression guard if the root cause suggests one exists elsewhere (e.g. if it's an iteration-order bug in a shared collection type, grep for the same anti-pattern across the diff/state-hash codebase — this is exactly the class of issue `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §5's "full-history sweep" methodology was built for).

**Do not relax, skip, or work around this task.** A relaunch without it fixed is a coin-flip on which height the same freeze recurs at.

---

### Task 4: Make the commit-generator workflows fail loudly and verify the committee

**Files (infra repo root):**
- Modify: `.github/workflows/auto-commit-generators.yml` — retry loop :103-108; `else`-branch echo `"Gen-0: already committed or sign failed"` at :111 (conflates no-op with failure); `continue-on-error: true` at :145 (val-0 ONLY — gen-0/gen-1 have none); `Report finality` SSH probe :169-181 (no `$GITHUB_STEP_SUMMARY` exists in this file)
- Modify: `.github/workflows/commit-generators-hotstuff.yml` — `continue-on-error` at :103 (gen-0) and :146 (gen-1); retry loop :128-139
- Modify: `monitoring/alerts.yml` (source) + `monitoring/exporter.py`; the deployed copy is `clusters/testnet/monitoring/` — edit source, let Flux roll it

**Endpoint fact:** `GET /generators/at/{height}` EXISTS (`GeneratorsApiRoute.scala:16`, registered `Application.scala:857`, no API key). It takes a HEIGHT, serves only the CURRENT and NEXT generation period (guard `reqGenerationPeriod <= currGenerationPeriod.next`, :17-18), returns **HTTP 404 with body `[]`** out of range (:30), and folds in un-finalized liquid commits for the next period (`CommonGeneratorsApi:77-87`). Verification MUST check the HTTP status — a naive `jq length` reads 404-`[]` as "0 generators".

- [ ] **Step 1:** Rewrite the broadcast loop in both workflows: `set -euo pipefail`; sign failure → hard error (replace the conflating echo with two distinct branches); `break` ONLY on a response containing `"id"` (success) or the specific already-committed error; retry on `not enough connections` and on curl network errors; any other error → `exit 1`. Remove `continue-on-error` (1 line in auto-commit, 2 in hotstuff).
- [ ] **Step 2:** Post-commit verification: poll `/transactions/info/<id>` until confirmed; then `curl -s -o body -w '%{http_code}' https://<node>/generators/at/<nextPeriodStart>` → require HTTP 200 AND `jq length > 0`; fail the job otherwise.
- [ ] **Step 3:** Alert `CommitteeGapUpcoming` (critical): current period > 60% elapsed and next-period committee empty — exporter samples `/generators/at/<next period start>` with the same status check. Close or supersede alert issue #148.
- [ ] **Step 4:** Validate YAML + `promtool check rules` (docker). **⛔ Live dispatch go-ahead needed:** dispatch each workflow once against the CURRENT (dead) chain expecting a LOUD failure — that failure is the proof. Commit: `fix(ci): commit-generator workflows fail loudly + verify next-period committee via /generators/at (root cause of silent committee gaps)`.

---

### Task 5: Publish protobuf-schemas 1.6.6 to Maven Central (operator) and remove ONLY the schema source-build step

**Files:** DecentralChain monorepo (workflow); node-scala `.github/workflows/check-pr.yaml:78` (the `protobuf-schemas` `mvnw install` line ONLY); `build.sbt:252-255` stays

**Fact:** `check-pr.yaml:39-48` sparse-checks-out FOUR monorepo packages and installs them all into `.m2` (:78 protobuf-schemas, :79 curve25519, :80 blst, :81 groth16, plus a Rust `cargo build` for groth16's JNI lib at :57-73). `build.sbt:252-255`'s `Resolver.mavenLocal` serves all four. Only protobuf-schemas is going to Central now; **removing the sparse-checkout or `mavenLocal` would break CI**.

- [ ] **Step 1 (⛔ operator):** from `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain`: `gh workflow run publish-protobuf-schemas.yml -f version=1.6.6 -f run-audit-profile=false` (1.6.6 adds zero dependencies — the documented condition for skipping the audit; the prior run was cancelled at the 4h NVD-download timeout for lack of `NVD_API_KEY`), approve the `maven-central-release` environment gate, poll `curl -s https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/maven-metadata.xml | grep 1.6.6`. Optionally add `NVD_API_KEY`.
- [ ] **Step 2 (after Central shows 1.6.6):** delete ONLY `check-pr.yaml:78` (the protobuf-schemas install line). Keep the sparse-checkout, the Rust build, the other three installs, and `Resolver.mavenLocal` — remove those only when curve25519/blst/groth16 are also on Central (separate decision). Prove Central resolution: `sbt -Dsbt.ivy.home=/tmp/ivy-check "node/update"` shows protobuf-schemas 1.6.6 fetched from `repo1.maven.org`. Commit: `build(ci): resolve protobuf-schemas 1.6.6 from Maven Central (other io.decentralchain artifacts still source-built)`.

---

### Task 6: scalafmt debt to zero in TEST sources and gate them

**Fact:** CI ALREADY gates `Compile` formatting — `build.sbt:304` `compilePRRaw` runs `scalafmtCheck.all(ScopeFilter(inAnyProject, inConfigurations(Compile)))`, invoked by `check-pr.yaml:89` `sbt --batch checkPR`. The gap is the `Test` configuration only (the ~29 unformatted files are test sources). 1,765 tracked `.scala` files; `.scalafmt.conf` excludes only `lang/.../parser/Parser.scala`.

- [ ] **Step 1:** `sbt scalafmtAll`; confirm whitespace-only (`git diff -w --stat` shows zero non-whitespace changes; spot-check 3 files). Commit: `style: scalafmt test sources (Test configuration was never gated)`.
- [ ] **Step 2:** In `build.sbt:304` widen the ScopeFilter to `inConfigurations(Compile, Test)` (or switch to `scalafmtCheckAll`). Push the branch, open a draft PR, confirm `checkPR` green. Commit: `ci: gate scalafmt on Test sources too`.

---

### Task 7: Docs closure — no document may still describe the gated design

**Files:** `docs/hotstuff-audit-readiness.md` (§7 items 8-9 "legacy DST forever" :273 + 29/30 mentions :152/:156/:259; §8 checklist incl. the stale "1.6.5 published" line; add the "every node from genesis" rule AND state plainly that deleting the gates removes the loud UNIMPLEMENTED safety net for these two behaviors — a stale node now forks silently instead of shutting down), `docs/hotstuff-bls-crypto-audit-2026-08-31.md` (H2/M2 STATUS → fixed unconditionally; L4 :229-230), `docs/hotstuff-bft-audit-2026-08-31.md`, `docs/superpowers/plans/2026-09-02-bls-crypto-v2.md` + `docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md` + `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-evidence.md` (supersession banners), `node/genesis-dcc-testnet-relaunch.conf` (its comment "no 28, no 30 — both deleted" is stale; its `[1..25]` list must match Task 2's decision on 26), `docs/consensus-divergences-from-upstream.md` (verify Task 2's reward section), `/Users/jourlez/Documents/Code/Blockchain/CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` (checkpoint: changes since `49809b487c`, feature-id decision, stall root cause, relaunch tag), `CHANGELOG`/release notes if the repo has one (grep), `version.sbt` (currently `1.7.0` — bump minor: registry + crypto identity changed)

- [ ] **Step 1:** `git grep -n "feature 29\|feature 30\|feature-29\|feature-30\|BlsCryptoV2\|HotStuffEquivocationEvidence\|legacy DST\|_NUL_\|kept HERE FOREVER" -- docs node/genesis-dcc-testnet-relaunch.conf ../../CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` — every hit rewritten or wrapped in a historical-note banner.
- [ ] **Step 2:** Bump `version.sbt`; write the changelog entry. Commit: `docs(consensus): registry mirrors Waves; DCC improvements unconditional; supersede gated designs; bump version`.

---

### Task 8: Final whole-branch review, full-suite gate, image build, merge, tag

- [ ] **Step 1:** `/Users/jourlez/.claude/plugins/cache/superpowers-dev/superpowers/6.1.1/skills/subagent-driven-development/scripts/review-package $(git merge-base dev HEAD) HEAD` → whole-branch adversarial review (most capable model) with this plan's Global Constraints as the lens. Fix Critical/Important; re-review.
- [ ] **Step 2:** Full gate: `sbt "node/compile" "node-testkit/compile" "node-tests/test"` → 0 failed (record count). Then `sbt "node-it/docker"` and run `FourNodeHotStuffTestSuite` + `FourNodeHotStuffAuthoritativeTestSuite` (locally if Docker allows, else on CI). Record in the reference doc.
- [ ] **Step 3:** Merge `feat/testnet-final-source` → `dev` (`--no-ff`), push, tag `testnet-relaunch-<yyyymmdd>`; open PR `dev` → `main`.
- [ ] **Step 4:** Build + publish the node image from the tag: `sbt buildTarballsForDocker` (`build.sbt:281-290` → `docker/target/dcc.tgz`) → the repo's image-build workflow (grep `.github/workflows` for the docker publish job; memory: images previously published to ghcr) → record the image digest. Task 9 consumes this digest.

---

### Task 9: Testnet relaunch runbook (operator-executed; this task produces the runbook and the config diff, not the mutation)

**⛔ PRECONDITION: Task 3b must be complete, fixed, and verified before this task runs.** Do not write or execute a relaunch runbook while Task 3b is open — the whole point of finding the state-hash non-determinism is to not repeat this exact incident on the next chain.

**Files (infra repo root):** `RELAUNCH-<date>.md` (new, beside `DEPLOY.md`/`RUNBOOK-*.md`), `node-config/testnet/dcc.conf:23-29` (pre-activated block), `clusters/testnet/apps/nodes.yaml` (THREE pre-activated blocks: :53-59, :200-206, :326-332), image tag/digest references in `nodes.yaml` + VPS deploy config

- [ ] **Step 1:** Write the runbook from the verified 2026-08-31 procedure (there is no workflow; it was manual): `sbt generateGenesis node/genesis-dcc-testnet-relaunch.conf` (re-verify every seed via `GenesisBlockGenerator.toFullAddressInfo`) → commit new `timestamp`/`block-timestamp`/`signature` into `dcc.conf` + `nodes.yaml` → pre-activated features per Task 2's decision (`1..25 = 0`, plus `26` only if decided; never 27+) in all FOUR blocks → image = Task 8's digest on ALL FOUR nodes (mixed versions = silent split — no gate protects this anymore) → wipe the 4 PVCs → restart → confirm `/node/version` identical on all four and `/activation/status` shows exactly the intended set → run the Task 4 workflow and confirm the FIRST period's committee via `/generators/at/<next start>` (HTTP 200, non-empty) before period end → reset matcher/BPS/scanner, re-fund faucet + treasury → arm alerts.
- [ ] **Step 2:** Post-relaunch evidence plan (= audit-readiness §8): 72h soak with recorded crash/partition/committee-rotation/equivocation drills; live epoch transition observed (T10); one live equivocation exercised end to end with `slashing-enabled=true` on ONE node first; then decide fleet-wide. External audit remains the mainnet gate.

---

## Decisions encoded (change the plan if you disagree)

| # | Decision | Rationale / caveat |
|---|---|---|
| D1 | Register upstream 26 WITH ported logic, faithfully (all 4 hunks, operator-approved 2026-09-02) | `implemented = dict.keySet` (`BlockchainFeature.scala:73`) is the unknown-feature safety net (`BlockchainUpdaterImpl.scala:130-147`, `:235-241`); placeholder = silent fork. 26's hunks (b)/(c) ARE a real monetary-policy change (20-DCC reward, retires the 10x boost) — approved, matching Waves exactly; not pre-activated at genesis, activates by vote |
| D2 | Renumber 27/28 to match upstream | Free under fresh genesis (non-votable, never on-chain); mainnet legacy genesis pre-activates `[1..13,15,16]` only |
| D3 | No carry-forward committee mechanism | Premise was false — `appender:74-78` already falls back to classic PoS; root-cause the real stall first (Task 3, H1–H6) |
| D4 | `authoritative` floor stays advisory; `slashing-enabled` off at relaunch | `HotStuffSettings.scala:88/:100`; audit F-1 option (b); flip slashing after the first live drill |
| D5 | T11 first-boot window stays documented | Operational rule; not worth consensus code |
| D6 | Rule: every node on a chain runs the binary from genesis | Replaces activation gates for pre-launch fixes. **Explicitly: this removes the loud UNIMPLEMENTED shutdown D1 relies on, for exactly these two behaviors — a stale node forks silently.** Future rule changes on live mainnet still use features |

## Requires the human operator (blocking items marked ⛔)

1. ✅ **Economic decision on feature 26 — RESOLVED 2026-09-02:** port faithfully, matching Waves. No longer blocking.
2. ✅ **Node logs — RESOLVED 2026-09-02:** pulled from the VPS. Root cause found (Task 3); the FIX (Task 3b) is separate unsolved work, not blocked on an operator input — it's a debugging task.
3. ⛔ **Task 3b itself is the new critical-path blocker.** Nothing past it should proceed to a real relaunch (Task 9) until it's solved. Tasks 1/2/4/5/6/7/8 can all proceed in parallel with Task 3b — they don't depend on it — but Task 9 (the actual relaunch) must not run until Task 3b is done and verified.
4. ⛔ **Maven Central publish** of protobuf-schemas 1.6.6 (operator-approved, run pending) + environment-gate approval — blocks Task 5 Step 2. Separate decision: publish curve25519/blst/groth16 too (enables removing `mavenLocal`).
5. Optional `NVD_API_KEY` secret.
6. ✅ **Testnet disposability — CONFIRMED 2026-09-02** by the operator ("stagenet will catch same scenario as mainnet will have anyway").
7. ⛔ Live go-aheads still needed: Task 4 Step 4 workflow dispatch; all of Task 9 (and Task 9 additionally gated on Task 3b, per #3 above).
8. **The SSH key used for Task 3's log pull must be rotated** — it was pasted into a chat session. Not a plan blocker, but do this promptly regardless of plan progress.

## Self-Review

- Spec coverage: registry parity (T1, T2), unconditional improvements (T1), stall root cause (T3 — done) + actual fix (T3b — the critical open item), automation (T4), schema (T5), format gate (T6), docs + version (T7), final gate + image + tag (T8), relaunch + soak (T9, gated on T3b). Every audited gap (E1–E29) is folded in.
- Placeholder scan: the ⛔ steps are operator gates or genuine unsolved debugging by necessity, and say so; no "TBD".
- Type consistency: `popMessage(chainId, sender, endorserPk, periodStart)`, `PopDst`, `VoteDst`, the renamed `Bls*DomainSeparationTag` constants are named identically across T1/T7; `/generators/at/{height}` semantics identical in T4 and T9.
- **Task 9 must explicitly check Task 3b's status before running** — added as its own precondition, not implied.
