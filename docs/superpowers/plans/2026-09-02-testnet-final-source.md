# Testnet Final Source — Waves-Identical Feature Registry, DCC Improvements Unconditional

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the final node-scala source for the testnet relaunch: feature registry byte-identical to upstream Waves 1.6.x (ids 1–28, including a real port of 26), every DCC consensus/crypto improvement shipped as the ONLY behavior (no DCC-native feature ids, no legacy paths, no activation gates), the live stall root-caused from real logs, the commit-generator automation made fail-loud, and a full-suite-green, reviewed, tagged build.

**Architecture:** Two decisions drive everything. (1) Testnet is disposable and mainnet's legacy chain has no BLS/HotStuff/commitment history, so the BLS domain-separation + bound-PoP fix (was feature 30) and block-carried equivocation evidence (was feature 29) need no activation gate — they become unconditional, and features 29/30 are deleted. (2) The registry mirrors upstream exactly, which requires porting upstream's `AdjustedBlockRewardDistribution` (id 26, upstream commit `f1bedddb2e`) as real logic — never as a placeholder, because `implemented = dict.keySet` is the unknown-feature safety net. Every node on any chain must run this binary from genesis; mixed versions = split (documented rule, enforced operationally).

**Tech Stack:** Scala 3, sbt, ScalaTest, blst BLS, protobuf-schemas 1.6.6 (monorepo), GitHub Actions (infra), Kubernetes/LKE + Linode VPS (testnet).

## Global Constraints

- Branch off `dev` @ `36caa7edc1` for node-scala work; `feat/bls-crypto-v2` (10 commits, contains the H2/M2 code) is the source to strip gates FROM — merge it first (Task 1), then remove gates on top. Never re-implement what that branch already has.
- Registry target (verbatim from `upstream-waves/version-1.6.x` `features/BlockchainFeature.scala`): 1–25 unchanged (DCC's 1 says "DCC" not "WAVES" — keep DCC wording), `26 = AdjustedBlockRewardDistribution` (in dict, logic ported), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (both not-exposed/not in dict, as upstream). No id > 28. No `Dummy`-style burned-id comments about 28.
- NEVER register a feature in `dict` whose logic is not implemented (`BlockchainUpdaterImpl.scala:139-145` / `:235-241` treat `dict.keySet` as "implemented"; a placeholder converts a loud UNIMPLEMENTED shutdown into a silent fork).
- v2 crypto is the ONLY crypto: DSTs `…_POP_` / `…_ENDORSE_` / `…_HSVOTE_`; PoP message = `chainId(1) ‖ senderPublicKey(32) ‖ endorserPublicKey(48) ‖ generationPeriodStart(4)`. The legacy `_NUL_` tag is deleted from production code entirely.
- `hotstuffConflicts` is always valid when well-formed (no activation gate). `slashing-enabled` stays an operator flag (default `false`).
- `HotStuffSettings` unchanged: `authoritative=false` default (advisory floor — documented, F-1 decision = advisory), `slashingEnabled=false`, `maxTargetLagFraction=0.25`.
- FOREGROUND sbt only in every task (background-and-wait has wedged multiple agents). Full-suite gate command: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-testkit/compile" "node-tests/test"` (~8-10 min).
- Commits sole-authored by jourlez; NEVER add Co-Authored-By / AI attribution. No `git worktree` (sbt-git breaks); use a full clone if isolation is needed.
- Infra changes: read-only investigation first; no live-node mutation (restarts, PVC wipes, config rollouts) without an explicit go-ahead step in the task.

---

### Task 1: Merge the H2/M2 code branch, then remove features 29 and 30 with all gates

**Files:**
- Merge: `feat/bls-crypto-v2` → new branch `feat/testnet-final-source` (off `dev`)
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` (remove 29, 30; leave 26/27 for Task 2)
- Modify: `node/src/main/scala/com/decentralchain/state/Blockchain.scala` (delete `supportsHotStuffEquivocationEvidence` :312-313, `supportsBlsCryptoV2` :323)
- Modify: `node/src/main/scala/com/decentralchain/state/appender/package.scala` (:364 feature-29 gate → delete; :388-395 activation-period boundary block → delete; :397/:446 DST → constants)
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` (:346-350 gate → delete)
- Modify: `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala` (delete legacy tag :17; `dst` params keep NO default — every caller names its context)
- Modify: `BlockEndorsement.scala` (:27 `_ENDORSE_` unconditional), `HotStuffQuorum.scala` (:64 `_HSVOTE_` unconditional; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC`), `HotStuffEquivocationProof.scala` (dst = HSVOTE constant), `HotStuffCoordinator.scala`, `HotStuffEngine.scala`, `HotStuffVotePool.scala` (drop `cryptoV2` provider/params), `CommitToGenerationTransaction.scala` (:70-77 v2 branch only; drop `cryptoV2` param from `popMessage`/`popDst`/`mkPopSignature`), `TransactionFactory.scala`, `BlockEndorser.scala` (remove `carrierHeight` :110/:123/:154/:160 — its only purpose was the gate), `EndorsementFilter.scala` (drop `cryptoV2` field), `EndorsementStorage.scala`, `BlockDiffer.scala` (:643 → v2), `CommitToGenerationTransactionDiff.scala` (:26 → v2), `BlockchainSettings.scala` (:155/:181 remove `BlsCryptoV2.id -> 1`), `Application.scala` (:351-355 remove provider), `TransactionsApiRoute.scala` (:261), `requests/CommitToGenerationRequest.scala`, `utils/UtilApp.scala` (:415-423 v2 tags; remove `--bls-crypto-v2-activation-height`), `utx/UtxPoolImpl.scala` (comment), `node/testkit/.../TxHelpers.scala`
- Delete tests: `settings/BlsCryptoV2PreActivationSpec.scala`, `state/BlsCryptoV2ActivationHelperSpec.scala`, `state/BlsCryptoV2RollbackDeterminismSpec.scala`, `state/appender/BlsCryptoV2EquivocationProofBoundarySpec.scala`, `utils/UtilAppSpec.scala`, and case 2 (:103-117) of `HotStuffEquivocationValidationSpecification.scala`
- Edit tests to v2-only: `crypto/bls/BlsUtilsTest.scala` (drop "legacy tag remains default" case; keep 3x3 matrices), `crypto/bls/BlsLegacyVectorRegressionSpec.scala` → rename `BlsVectorRegressionSpec`, RE-PIN as v2 vectors, REWRITE header (vectors are synthesized fixed-seed pins of the current encoding — regenerate ONLY with a deliberate encoding change, never to "fix" a failure), `transaction/CommitToGenerationPopMessageSpec.scala` (drop legacy-layout cases; keep chainId/sender-differ + exact 85-byte layout), `state/diffs/CommitToGenerationPopV2Spec.scala` (keep transplant-by-domain; collapse pre/post pairs), `finalization/BlsCryptoV2EndorsementSpec.scala` (collapse 8→4), `state/BlsCryptoV2SnapshotPathPopSpec.scala`, `state/BlockEndorserSpec.scala` (remove `carrierHeight` block :136), `features/BlockchainFeaturesRegistrySpec.scala` (assert 29/30 ABSENT; Task 2 adds 26-28 assertions), `MultipleConflictEndorserSuite.scala:89` + `mining/HotStuffEquivocationEvidenceE2ESpecification.scala:55` (drop `.addFeatures(HotStuffEquivocationEvidence)`), ~20 `consensus/hotstuff/*Specification` (drop positional `cryptoV2 = false` / legacy-dst args)

**Interfaces:**
- Produces: `BlsUtils.{BlsPopDomainSeparationTag, BlsEndorseDomainSeparationTag, BlsHsVoteDomainSeparationTag}` (rename from `…TagV2` — there is no v1 anymore); `HotStuffQuorum.VoteDst` constant; `CommitToGenerationTransaction.popMessage(chainId, sender, endorserPk, periodStart)`; `HotStuffQuorum.verifyVote(vote, committee)` / `formQC(votes, committee)` / `verifyQC(qc, committee)` without `cryptoV2`.

- [ ] **Step 1: Create branch and merge the H2/M2 code**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git checkout dev && git pull --ff-only
git checkout -b feat/testnet-final-source
git merge --no-ff feat/bls-crypto-v2 -m "Merge branch 'feat/bls-crypto-v2' into feat/testnet-final-source (H2/M2 code; gates removed in following commits)"
sbt "node/compile" "node-testkit/compile"
```
Expected: clean merge (branch is a strict descendant of dev + 10 task commits + fixes), compile green.

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

Run: `sbt "node-tests/testOnly com.decentralchain.features.BlockchainFeaturesRegistrySpec"`
Expected: FAIL — `feature(29)` is `Some(...)`.

- [ ] **Step 4: Remove feature 29 + its gate**

`BlockchainFeature.scala`: delete the `HotStuffEquivocationEvidence` val (:32), its dict entry (:67), and the "Id 28 is deliberately BURNED" comment (:33). `Blockchain.scala`: delete `supportsHotStuffEquivocationEvidence` (:312-313). `appender/package.scala` `validateHotStuffEquivocationProofs`: delete the `raiseUnless(blockchain.supportsHotStuffEquivocationEvidence(blockHeight))(...)` step at :364 (keep every other rule: consistency, epoch==period, bounds, dedup, known-conflict, overlap, signatures). `Miner.scala` `withHotStuffConflicts`: delete the `if (!blockchainUpdater.supportsHotStuffEquivocationEvidence(...)) voting else` wrapper (:346-350), keep the `generationPeriodOf` match. `HotStuffEquivocationValidationSpecification.scala`: delete case 2 (:103-117); renumber nothing else. Drop `.addFeatures(BlockchainFeatures.HotStuffEquivocationEvidence)` at `MultipleConflictEndorserSuite.scala:89` and `HotStuffEquivocationEvidenceE2ESpecification.scala:55`.

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.state.appender.* com.decentralchain.mining.* com.decentralchain.features.*"` — expected green except the registry test still failing on 30.

- [ ] **Step 5: Remove feature 30 — crypto becomes unconditional**

`BlsUtils.scala`: delete `BlsDomainSeparationTag` (legacy, :17). Rename `BlsPopDomainSeparationTagV2` → `BlsPopDomainSeparationTag`, `BlsEndorseDomainSeparationTagV2` → `BlsEndorseDomainSeparationTag`, `BlsHsVoteDomainSeparationTagV2` → `BlsHsVoteDomainSeparationTag`. Remove the `= BlsDomainSeparationTag` defaults at :75/:88/:151 (params stay required). Rewrite the :14-17 scaladoc: three contexts, three tags, no legacy — regenerating a tag is a chain-identity change.

`CommitToGenerationTransaction.scala`:
```scala
  /** Canonical PoP message: chainId ‖ senderPublicKey ‖ endorserPublicKey ‖ generationPeriodStart (85 bytes).
    * chainId defeats cross-chain PoP replay (BLS audit M2); sender defeats mempool PoP lifting (M2). */
  def popMessage(chainId: Byte, sender: PublicKey, endorserPublicKey: BlsPublicKey, generationPeriodStart: Height): Array[Byte] =
    Array(chainId) ++ sender.arr ++ endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  val PopDst: String = BlsUtils.BlsPopDomainSeparationTag
```
Update `mkPopSignature(blsKeyPair, generationPeriodStart, sender, chainId)` (drop `cryptoV2`) and every caller (`TxHelpers`, `CommitToGenerationRequest.toTxFrom`, `TransactionFactory`, `UtilApp`, tests — compiler names them).

`BlockDiffer.scala:643`, `CommitToGenerationTransactionDiff.scala:26`: delete the `val cryptoV2 = blockchain.supportsBlsCryptoV2(...)` lines; call `popMessage(tx.chainId, tx.sender, tx.endorserPublicKey, tx.generationPeriodStart)` + `PopDst`.

`BlockEndorsement.scala`: `mkMessage` unchanged; `sign`/`signed`/`signatureValid` drop `cryptoV2`, use `BlsUtils.BlsEndorseDomainSeparationTag`. `EndorsementFilter.scala`: delete the `cryptoV2` field (and its 3 test constructions). `EndorsementStorage.verifySig`: use the endorse tag. `BlockEndorser.scala`: delete `carrierHeight` parameter + the `supportsBlsCryptoV2(carrierHeight)` read (:110/:123/:154/:160) and the `cryptoV2` val; `castVote(votingHeight, endorsedHeight, ...)` as before Task 6 of the old plan. `appender/package.scala`: `endorsementDst` → constant; `proofDst` → `HotStuffQuorum.VoteDst`; delete the boundary block :388-395.

`HotStuffQuorum.scala`: `val VoteDst: String = BlsUtils.BlsHsVoteDomainSeparationTag`; delete `voteDst(cryptoV2)`; drop `cryptoV2` from `verifyVote`/`formQC`/`verifyQC` and their `HotStuffVotePool`/`HotStuffEngine` (`EngineState.cryptoV2`)/`HotStuffCoordinator` (`cryptoV2: () => Boolean` provider; both read sites)/`NodeHotStuffEffects.signVote(dst)` (pass `VoteDst`)/`Application.scala:351-355` callers. `HotStuffEquivocationProof.signaturesValid` drops `dst` and uses `HotStuffQuorum.VoteDst`; its two call sites simplify.

`BlockchainSettings.scala`: delete the `BlsCryptoV2.id -> 1` entries at :155/:181. `network-defaults.conf` devnet: delete `30 = 1`. `application.conf`: delete the BlsCryptoV2 comment block. `UtilApp.scala`: delete the `--bls-crypto-v2-activation-height` option and era arithmetic; sign with `PopDst`. `UtxPoolImpl.scala`: keep the pack-side era comment but drop the BlsCryptoV2 mention. `Blockchain.scala`: delete `supportsBlsCryptoV2` (:323). `BlockchainFeature.scala`: delete the `BlsCryptoV2` val + dict entry.

Delete the six gate-only test files listed under Files. Edit the kept specs to v2-only exactly as listed (each pre/post pair collapses to the post half; every `cryptoV2 = false`/legacy-tag argument is removed — the compiler is the checklist).

`BlsLegacyVectorRegressionSpec.scala` → `git mv` to `BlsVectorRegressionSpec.scala`: regenerate the three pinned triples under the v2 tags with the SAME fixed seeds (31 ASCII bytes + one 0x00 pad, as the current header documents), paste as literals, delete the printer, and rewrite the header:

```scala
/** Pinned byte vectors for the three BLS contexts (PoP / endorsement / HotStuff vote), synthesized from
  * fixed seeds and pasted as literals. They pin TODAY'S encoding + domain tags so an accidental change
  * to a message layout or DST fails loudly. Regenerate ONLY as part of a deliberate, reviewed encoding
  * change (which is a chain-identity change: every node must ship it from genesis). Never regenerate
  * to make a red test green.
  */
```

- [ ] **Step 6: Run the registry test and the affected suites**

Run: `sbt "node/compile" "node-testkit/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.crypto.bls.* com.decentralchain.transaction.* com.decentralchain.finalization.* com.decentralchain.state.* com.decentralchain.consensus.hotstuff.* com.decentralchain.mining.* com.decentralchain.settings.*"`
Expected: all green; `git grep -n "cryptoV2\|supportsBlsCryptoV2\|BlsDomainSeparationTag\b\|_NUL_\|HotStuffEquivocationEvidence\|BlsCryptoV2" node/src node/testkit node/tests` returns ZERO hits in `node/src` and `node/testkit` (tests may keep the words only inside the new registry test's "absent" assertions).

- [ ] **Step 7: Commit (two commits)**

```bash
git add -A node/src node/testkit node/tests
git commit -m "refactor(consensus): remove feature 29 -- block-carried equivocation evidence is unconditional

Testnet is re-genesised and mainnet's legacy chain has no HotStuff history,
so the activation gate protected nothing. hotstuffConflicts is now valid
whenever well-formed (consistency, epoch==period, bounds, dedup, signatures
all still enforced). Rule: every node on a chain runs this binary from
genesis."
```
(Split the Step 5 work into the second commit:)
```bash
git commit -m "refactor(crypto): per-context BLS domain tags + chain/sender-bound PoP are the only crypto (remove feature 30)

Deletes the legacy _NUL_ tag, every cryptoV2 gate/provider, the
activation-period boundary rule, and the rollback-across-activation
scaffolding. The fixes for BLS-audit H2 and M2 ship unconditionally;
pinned vectors re-pinned under the three tags. Registry has no id > 28."
```

---

### Task 2: Port upstream feature 26 (`AdjustedBlockRewardDistribution`) and realign 27/28

**Files:**
- Read: `git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala`, and upstream commit `f1bedddb2e` (`git show f1bedddb2e --stat`, then each file): `BlockRewardCalculator.scala` (:63, :91), `Blockchain.scala` (:276-278), `BlockchainUpdaterImpl.scala` (:198-199), `EthereumTransactionDiff.scala` (:106, :112), `ExchangeTransactionDiff.scala` (:292), plus its tests.
- Modify: `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala` — `26 = AdjustedBlockRewardDistribution` (in dict), `27 = ContinuationTransaction`, `28 = LeaseExpiration` (not exposed, not in dict)
- Modify: the five DCC counterparts of the upstream files (`com/decentralchain/...` paths)
- Modify: `node/src/main/scala/com/decentralchain/settings/BlockchainSettings.scala` — TESTNET/STAGENET/MAINNET presets pre-activate 1–26 only where DCC already pre-activates 1–25 (check each preset's current map; add `26 -> 1` beside `25`), `network-defaults.conf` devnet likewise
- Test: port `f1bedddb2e`'s spec changes (`BlockRewardCalculatorSpec` or equivalent — read the commit), extend `BlockchainFeaturesRegistrySpec`

**Interfaces:**
- Produces: `BlockchainFeatures.AdjustedBlockRewardDistribution = BlockchainFeature(26, "Adjusted Block Reward Distribution")` implemented; `ContinuationTransaction` id 27; `LeaseExpiration` id 28. Renumbering 27/28 is FREE only because the relaunch is a fresh genesis (non-votable placeholders never appear on-chain) — state this in the commit body.

- [ ] **Step 1: Read the upstream commit fully and map each hunk to the DCC file** (`git show f1bedddb2e -- <file>` for all 5 + tests). DCC's `BlockRewardCalculator` may already differ from upstream (DCC reward economics — check `docs/consensus-divergences-from-upstream.md` for any deliberate divergence in reward distribution; if DCC deliberately diverged, porting 26's LOGIC must respect that divergence — write down the reconciliation before coding).

- [ ] **Step 2: Failing registry test**

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

- [ ] **Step 3: Port the logic** hunk-by-hunk (upstream's `isFeatureActivated(AdjustedBlockRewardDistribution, height)` gates; keep DCC's reward constants), add the feature val + dict entry, renumber 27/28, update presets/devnet conf. Port upstream's tests for the reward-distribution change; add a RED/GREEN case proving the adjusted distribution applies when 26 is active and the old one when not.

- [ ] **Step 4: Verify**

Run: `sbt "node/compile" "node-tests/testOnly com.decentralchain.features.* com.decentralchain.state.* com.decentralchain.mining.* com.decentralchain.settings.*"` — green. Then confirm registry parity mechanically:
```bash
diff <(git show upstream-waves/version-1.6.x:node/src/main/scala/com/wavesplatform/features/BlockchainFeature.scala | grep -o 'BlockchainFeature([0-9-]*, "[^"]*")' ) \
     <(grep -o 'BlockchainFeature([0-9-]*, "[^"]*")' node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala)
```
Expected: the ONLY difference is id 1's description ("1000 DCC" vs "1000 WAVES").

- [ ] **Step 5: Commit** — `feat(consensus): port upstream feature 26 AdjustedBlockRewardDistribution; registry now mirrors Waves 1.6.x (ids 1-28)`. Body: cite `f1bedddb2e`, note 27/28 renumbering is safe only under fresh genesis, and that DCC-native ids are gone.

---

### Task 3: Root-cause the 2026-09-01 stall from node logs (read-only), fix the misleading miner message

**Files:**
- Infra (read): `infra/.github/workflows/export-kubeconfig.yml` (or the documented access path), `infra/clusters/testnet/TOPOLOGY.md`
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala:214` message
- Docs: `/Users/jourlez/Documents/Code/Blockchain/CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` (new incident section), `infra/` incident doc

**Interfaces:** none produced; a written root cause consumed by Task 4 (workflow fixes) and Task 8 (relaunch checklist).

- [ ] **Step 1 (needs the operator — this is an explicit go-ahead step):** obtain node logs. Options, in order of preference: (a) operator runs `infra`'s `export-kubeconfig.yml` (or supplies kubeconfig) → `kubectl -n <ns> logs dcc-gen-0 --since=48h | grep -Ei "not committed|Quorum|InvalidStateHash|BlockFromFuture|suspend|EQUIVOCATION|HotStuff\] (onQC|castVotes SKIPPED|QUORUM REACHED)|Exception" | tail -300` for gen-0, gen-1, val-0, plus `kubectl describe pod` restart counts; (b) operator runs the same on the VPS via SSH (`journalctl -u dcc-node --since "2026-09-01 08:00" ...`). Verified fact to test against: the code does NOT halt on an empty committee (`appender/package.scala:74-77` falls back to classic PoS); the only permanent forge veto is the generating-balance floor at `Miner.scala:214` (whose message misleadingly says "not committed").
- [ ] **Step 2:** Write the incident timeline + root cause with quoted log lines into the reference doc. Distinguish: balance-floor veto vs endorsement starvation (`authoritative` + `BlockEndorser.scala:128` suppression + `Miner.scala:320-334` grace polls) vs peer/network vs something new. If the root cause is a code defect, open a follow-up task in this plan (append as Task 3b) before proceeding to Task 8.
- [ ] **Step 3:** Fix `Miner.scala:214`: the message must state the actual check (`generating balance X below minimal Y at height H`) — RED/GREEN via the existing Miner spec that asserts on this string (grep `"is not committed on"` in tests). Commit: `fix(mining): say what the forge veto actually checks (generating balance floor, not commitment)`.

---

### Task 4: Make the commit-generator workflows fail loudly and verify the committee

**Files (infra repo `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra`):**
- Modify: `.github/workflows/auto-commit-generators.yml` (:103-108 retry loop, :111 conflation, :145 `continue-on-error`, :169-181 summary)
- Modify: `.github/workflows/commit-generators-hotstuff.yml` (:103, :146 `continue-on-error`; :128-139 retry loop)
- Modify: `monitoring/alerts.yml` (new `CommitteeGapUpcoming` alert), `monitoring/exporter.py` (expose committee size for the next period if the node REST offers it — check `/generators` or the committed-generators endpoint the workflows already read)

- [ ] **Step 1:** Rewrite the broadcast loop in both workflows: `set -euo pipefail`; treat sign failure as a hard error (no "already committed or sign failed" string); on broadcast, `break` ONLY when the response JSON contains an `id` (success) or the specific "already committed" error; retry on `not enough connections` AND on network errors; any other error → `exit 1`. Remove all `continue-on-error: true`.
- [ ] **Step 2:** Add a post-commit verification step: after the broadcast, poll the node until the tx is confirmed (`/transactions/info/<id>`), then assert via REST that the committed-generator set for the NEXT period is non-empty (use whatever endpoint the node exposes — the investigation agent found `committedGeneratorsHash` in headers; find the REST for the set itself, e.g. `/generators/committed?period=`; if none exists, add one to node-scala as Task 4b and reference it). Fail the job if empty.
- [ ] **Step 3:** Alert: `CommitteeGapUpcoming` — fires when the current period is > 60% elapsed and the next period's committee is still empty. Severity critical. Also close or supersede alert issue #148 with a note.
- [ ] **Step 4:** Validate YAML (`python3 -c "import yaml,sys;yaml.safe_load(open('...'))"`) and run `promtool check rules` via docker if available. Dispatch the workflow manually once against the CURRENT (dead) chain expecting a LOUD failure — that failure is the proof the fix works. Commit: `fix(ci): commit-generator workflows fail loudly + verify next-period committee (root cause of silent committee gaps)`.

---

### Task 5: Publish protobuf-schemas 1.6.6 to Maven Central (operator step) and drop the mavenLocal crutch

**Files:** DecentralChain monorepo (workflow), node-scala `build.sbt:253-255` (mavenLocal resolver TODO), `.github/workflows/check-pr.yaml:39-48,78` (monorepo sparse-checkout `mvnw install` step)

- [ ] **Step 1 (operator):** the earlier run `33597222129` was cancelled at the 4h NVD-download timeout (no `NVD_API_KEY`). Run, from `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/DecentralChain`:
```bash
gh workflow run publish-protobuf-schemas.yml -f version=1.6.6 -f run-audit-profile=false
```
(zero new dependencies in 1.6.6 — the documented condition for skipping the audit profile), then approve the `maven-central-release` environment gate, then poll `curl -s https://repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/maven-metadata.xml | grep 1.6.6` until present (can take ~1h). Optionally add an `NVD_API_KEY` secret so future runs don't time out.
- [ ] **Step 2 (after Central shows 1.6.6):** in node-scala remove the `Resolver.mavenLocal` addition (`build.sbt:253-255`) and the monorepo sparse-checkout + `mvnw install` steps in `check-pr.yaml`; CI must resolve 1.6.6 from Central. Run `sbt "node/compile"` with a clean ivy cache path if possible (`sbt -Dsbt.ivy.home=/tmp/ivy-check ...`) to prove Central resolution. Commit: `build: resolve protobuf-schemas 1.6.6 from Maven Central; drop mavenLocal + monorepo source build (DCC-269 phase 3)`.

---

### Task 6: scalafmt debt to zero and gate it in CI

**Files:** all files failing `scalafmtCheckAll` (~29), `.github/workflows/check-pr.yaml` (add `sbt scalafmtCheckAll` step), `build.sbt` `checkPRRaw` (:309-316 — add `scalafmtCheckAll` to the command)

- [ ] **Step 1:** `sbt scalafmtAll scalafmtSbt`; review the diff is whitespace-only (`git diff --stat`, spot-check 3 files with `git diff -w --stat` showing zero non-whitespace changes). Commit: `style: scalafmt the whole tree (29 files were unformatted; CI never gated it)`.
- [ ] **Step 2:** Add `scalafmtCheckAll` to `checkPRRaw` in `build.sbt` and a dedicated step in `check-pr.yaml`. Push the branch, open a draft PR to confirm the gate runs green (public repo — CI is free). Commit: `ci: gate PRs on scalafmtCheckAll`.

---

### Task 7: Docs closure — no document may still describe the gated design

**Files:** `docs/hotstuff-audit-readiness.md` (§7 items 8-9 legacy-DST-forever + 29/30 mentions :152/:156/:259/:273; §8 checklist incl. the stale "1.6.5 published" line; add the "every node from genesis" rule), `docs/hotstuff-bls-crypto-audit-2026-08-31.md` (H2/M2 STATUS → fixed unconditionally, :231 feature-29 mention), `docs/hotstuff-bft-audit-2026-08-31.md`, `docs/superpowers/plans/2026-09-02-bls-crypto-v2.md` + `docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md` + `docs/superpowers/plans/2026-09-01-hotstuff-equivocation-evidence.md` (supersession banners: gates removed by this plan, why), `BlsUtils.scala` scaladoc (already rewritten in Task 1 — verify), `/Users/jourlez/Documents/Code/Blockchain/CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` (checkpoint section: what changed since `49809b487c`, the feature-id decision, the stall root cause from Task 3, the relaunch build tag)

- [ ] **Step 1:** `git grep -n "feature 29\|feature 30\|feature-29\|feature-30\|BlsCryptoV2\|HotStuffEquivocationEvidence\|legacy DST\|_NUL_" docs/ ../../CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` — every hit is either rewritten or wrapped in a historical-note banner. Zero live-design references remain.
- [ ] **Step 2:** Commit: `docs(consensus): registry mirrors Waves; DCC improvements unconditional; supersede gated designs`.

---

### Task 8: Final whole-branch review, full-suite gate, merge, tag

- [ ] **Step 1:** `scripts/review-package $(git merge-base dev HEAD) HEAD` and dispatch a whole-branch adversarial review (most capable model) with this plan's Global Constraints as the lens. Fix anything Critical/Important; re-review.
- [ ] **Step 2:** Full gate: `sbt "node/compile" "node-testkit/compile" "node-tests/test"` — must be 0 failed (baseline: 2,943 tests). Then `sbt "node-it/docker"` and run `FourNodeHotStuffTestSuite` + `FourNodeHotStuffAuthoritativeTestSuite` (or on CI if local Docker is constrained). Record counts in the reference doc.
- [ ] **Step 3:** Merge `feat/testnet-final-source` → `dev` (`--no-ff`), push, tag `testnet-relaunch-<yyyymmdd>`; open PR `dev` → `main` for the release (CI free on public repo).

---

### Task 9: Testnet relaunch checklist (operator-executed; this task produces the runbook, not the mutation)

**Files:** `infra/docs/RELAUNCH-<date>.md` (new runbook), `infra/node-config/testnet/dcc.conf`, `infra/clusters/testnet/apps/nodes.yaml`

- [ ] **Step 1:** Write the runbook from the verified 2026-08-31 procedure: `sbt generateGenesis node/genesis-dcc-testnet-relaunch.conf` (re-verify every seed via `GenesisBlockGenerator.toFullAddressInfo`) → commit new `timestamp`/`block-timestamp`/`signature` into `dcc.conf` + `nodes.yaml` → pre-activated features `1..26 = 0` (no 27+; verify against Task 2's registry) → image = the Task 8 tag on ALL FOUR nodes (mixed versions = split) → wipe the 4 PVCs → restart → confirm `/node/version` identical on all four, `/activation/status` shows exactly 1–26 → run the fixed Task 4 workflow and confirm the FIRST period's committee is non-empty before period end → reset matcher/BPS/scanner, re-fund faucet + treasury → arm alerts.
- [ ] **Step 2:** Post-relaunch evidence plan (this is the §8 checklist): 72h soak with recorded crash/partition/committee-rotation/equivocation drills; live epoch transition observed (T10); one live equivocation exercised end to end with `slashing-enabled=true` on ONE node first; then decide `slashing-enabled` fleet-wide. External audit engagement remains the mainnet gate.

---

## Decisions encoded (change the plan if you disagree)

| # | Decision | Rationale |
|---|---|---|
| D1 | Port upstream 26; never register a placeholder | `implemented = dict.keySet` is the unknown-feature safety net; a placeholder = silent fork |
| D2 | Renumber 27/28 to match upstream | Free under fresh genesis (non-votable ids never hit chain); mainnet legacy chain has neither |
| D3 | No carry-forward committee mechanism | Premise was false — code already falls back to classic PoS; root-cause the real stall first (Task 3) |
| D4 | `authoritative` floor stays advisory; `slashing-enabled` off at relaunch | Audit F-1 option (b); flip slashing after the first live equivocation drill |
| D5 | T11 first-boot window stays documented | Operational rule in `slashing-enabled` doc; not worth consensus code |
| D6 | Rule: every node on a chain runs the binary from genesis | Replaces activation gates for pre-launch fixes; future rule changes on live mainnet still use features |

## Self-Review

- Spec coverage: registry parity (T1, T2), unconditional improvements (T1), stall root cause (T3), automation (T4), schema (T5), format gate (T6), docs (T7), final gate + tag (T8), relaunch + soak (T9). The verified gap list's every bullet maps to a task.
- Placeholder scan: Task 3 Step 1 and Task 5 Step 1 are operator steps by necessity (credentials/classifier) and say so; Task 4 Step 2 names a possible follow-up (4b) if a REST endpoint is missing rather than assuming one.
- Type consistency: `popMessage(chainId, sender, endorserPk, periodStart)` (T1) is the signature T2/T5 callers assume; `VoteDst`/`PopDst` constants named identically across T1 and T7.
