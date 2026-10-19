# In-Process Self-Commit-to-Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every miner account in `MinerImpl` self-check, at each key-block forge, whether it is committed to the *next* generation period's committee — and if not, build, sign, and submit its own `CommitToGenerationTransaction` directly into the local UTX pool, with zero external trigger.

**Architecture:** Add a private method `maybeSelfCommit(account, blockchain, forgeHeight)` to `MinerImpl`, called once per `forgeBlock` invocation right after the existing `committedGeneratorsHash` computation (`Miner.scala:242-253`) reads `blockchain.committedGenerators(period.next)` — the check is "free" because that same read already tells us whether `account.toAddress` is present in the next period's committee. If absent, and DeterministicFinality is activated for this height, build a `CommitToGenerationTransaction` via `TransactionFactory`-equivalent logic (reusing `CommitToGenerationTransaction.mkPopSignature` + `.selfSigned`, mirroring `CommitToGenerationRequest.toTxFrom`'s existing construction path at `api/http/requests/CommitToGenerationRequest.scala:31-64`) and submit it with `utx.putIfNew(tx)`. This runs off the miner/forging hot path (dispatched via `appenderScheduler`, fire-and-forget `Task`, never blocking `forgeBlock`'s synchronous return).

**Tech Stack:** Scala 3, Monix `Task`/`Scheduler`, existing `UtxPool.putIfNew`, existing `CommitToGenerationTransaction`/`BlsKeyPair` machinery. No new dependencies.

## Global Constraints

- Never construct BLS PoP message bytes by hand — always go through `CommitToGenerationTransaction.mkPopSignature`/`popMessage` (the codebase's own comment at `CommitToGenerationTransaction.scala:50-57` states a hand-rolled divergence between call sites is a consensus split; this plan must not introduce a fourth hand-rolled construction site).
- The self-commit check must key off `blockchain.generationPeriodOf(...)`, never `height % generationPeriodLength == 0` — see the existing in-code warning at `Miner.scala:243-248` about non-zero-activation-height periods.
- The commit-tx build/sign must NOT run synchronously inside `forgeBlock` (called via `Task(forgeBlock(account)).executeOn(minerScheduler)` at `Miner.scala:486` — this is the actual forging hot path and must stay fast).
- Must not duplicate-submit: gate on `blockchain.committedGenerators(period.next)` containing `account.toAddress` BEFORE building/signing, so an already-committed account never re-submits (mirrors the diff-level "$sender is already committed" rejection already present in `CommitToGenerationTransactionDiff.scala:35`, but avoids the wasted validation round-trip by checking locally first).
- Every new/changed method needs a unit test; no placeholder tests.
- Config default for the new feature flag: **off** (`false`) — this ships disabled until a real testnet soak validates it, per this codebase's own convention of feature-gating consensus-adjacent behavior (see `hotStuffSettings.slashingEnabled` used identically in `Miner.scala:349`).

---

## File Structure

- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala` — add `maybeSelfCommit` method + wire into `forgeBlock`.
- Modify: `node/src/main/scala/com/decentralchain/settings/MinerSettings.scala` — add `selfCommitToGeneration: Boolean` field.
- Modify: `node/src/main/resources/application.conf` (or wherever `dcc.miner` defaults live — located in Task 1) — add the new config key, default `false`.
- Create: `node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala` — unit tests for the new pure decision logic.
- Modify (retirement, final task): `Ecosystem/infra/.github/workflows/auto-commit-generators.yml` and `Ecosystem/infra/.github/workflows/commit-generators-hotstuff.yml` — delete both once the in-process path is soaked and confirmed live.

---

### Task 1: Add `selfCommitToGeneration` config flag

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/settings/MinerSettings.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/settings/MinerSettingsSpecification.scala`

**Interfaces:**
- Produces: `MinerSettings.selfCommitToGeneration: Boolean` — read by `MinerImpl` in Task 3 to gate whether `maybeSelfCommit` does anything at all.

- [ ] **Step 1: Find the current application.conf default block for `dcc.miner`**

Run: `grep -rn "dcc.miner" node/src/main/resources/application.conf`

Expected: a block like:
```
miner {
  enable = yes
  quorum = 1
  ...
  private-keys = []
}
```

- [ ] **Step 2: Write the failing test**

Add to `node/tests/src/test/scala/com/decentralchain/settings/MinerSettingsSpecification.scala` (open the file first to match its existing style/imports before appending):

```scala
  "self-commit-to-generation defaults to false" in {
    val config = ConfigFactory.parseString("""dcc.miner {
                                              |  enable = yes
                                              |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
    val settings = ConfigSource.fromConfig(config).at("dcc.miner").loadOrThrow[MinerSettings]
    settings.selfCommitToGeneration shouldBe false
  }

  "self-commit-to-generation can be enabled explicitly" in {
    val config = ConfigFactory.parseString("""dcc.miner {
                                              |  enable = yes
                                              |  self-commit-to-generation = yes
                                              |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
    val settings = ConfigSource.fromConfig(config).at("dcc.miner").loadOrThrow[MinerSettings]
    settings.selfCommitToGeneration shouldBe true
  }
```

- [ ] **Step 2b: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.settings.MinerSettingsSpecification"`
Expected: FAIL — `value selfCommitToGeneration is not a member of MinerSettings`

- [ ] **Step 3: Add the field to `MinerSettings`**

Edit `node/src/main/scala/com/decentralchain/settings/MinerSettings.scala`:

```scala
package com.decentralchain.settings

import com.decentralchain.account.PrivateKey
import com.decentralchain.mining.Miner
import pureconfig.*

import scala.concurrent.duration.FiniteDuration

case class MinerSettings(
    enable: Boolean,
    quorum: Int,
    intervalAfterLastBlockThenGenerationIsAllowed: FiniteDuration,
    noQuorumMiningDelay: FiniteDuration,
    microBlockInterval: FiniteDuration,
    minimalBlockGenerationOffset: FiniteDuration,
    maxTransactionsInMicroBlock: Int,
    minMicroBlockAge: FiniteDuration,
    privateKeys: Seq[PrivateKey],
    // In-process replacement for the external auto-commit-generators/commit-generators-hotstuff
    // GH Actions crons: when true, MinerImpl self-checks committee membership at every key-block
    // forge and self-submits a CommitToGenerationTransaction if it's about to fall out of the next
    // period's committee. Default false until a testnet soak confirms it; see
    // docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md.
    selfCommitToGeneration: Boolean = false
) derives ConfigReader {
  require(maxTransactionsInMicroBlock <= Miner.MaxTransactionsPerMicroblock)
}
```

- [ ] **Step 4: Add the config default**

Edit `node/src/main/resources/application.conf`, inside the `dcc.miner { ... }` block, add:

```
    # In-process self-commit-to-generation. See MinerSettings.selfCommitToGeneration.
    self-commit-to-generation = no
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.settings.MinerSettingsSpecification"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add node/src/main/scala/com/decentralchain/settings/MinerSettings.scala node/src/main/resources/application.conf node/tests/src/test/scala/com/decentralchain/settings/MinerSettingsSpecification.scala
git commit -m "feat(miner): add self-commit-to-generation config flag (default off)"
```

---

### Task 2: Pure decision function — "should this account self-commit now?"

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala`

**Interfaces:**
- Consumes: nothing new — pure function over primitives/existing types.
- Produces: `Miner.shouldSelfCommit(enabled: Boolean, alreadyCommitted: Boolean): Boolean` — a tiny pure predicate, unit-testable in isolation, consumed by `MinerImpl.maybeSelfCommit` in Task 3.

This mirrors the existing pattern in this same file: `Miner.foldHotStuffConflicts` and `Miner.clampFinalizedHeight` are both pure, `private[mining]`-or-public static functions on the `Miner` companion object, tested directly without spinning up a full `MinerImpl`. Do the same here before touching the stateful wiring.

- [ ] **Step 1: Write the failing test**

Create `node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala`:

```scala
package com.decentralchain.mining

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure-function tests for `Miner.shouldSelfCommit` — the in-process replacement decision for the
  * external auto-commit-generators/commit-generators-hotstuff GH Actions crons. See
  * docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md.
  */
class MinerSelfCommitSpecification extends AnyFreeSpec with Matchers {

  "shouldSelfCommit" - {
    "feature disabled => never self-commit, regardless of committee membership" in {
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = false) shouldBe false
      Miner.shouldSelfCommit(enabled = false, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, already committed => do not re-submit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = true) shouldBe false
    }

    "feature enabled, not yet committed => self-commit" in {
      Miner.shouldSelfCommit(enabled = true, alreadyCommitted = false) shouldBe true
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.mining.MinerSelfCommitSpecification"`
Expected: FAIL — `value shouldSelfCommit is not a member of object Miner`

- [ ] **Step 3: Implement the pure function**

Edit `node/src/main/scala/com/decentralchain/mining/Miner.scala`, add to the `object Miner` block (near `clampFinalizedHeight`, after line 602):

```scala
  /** Decision for the in-process self-commit-to-generation replacement of the external
    * auto-commit-generators/commit-generators-hotstuff GH Actions crons (see
    * docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md). Kept as a pure,
    * directly-testable predicate rather than inlined into `forgeBlock`'s already-dense stopReasons
    * chain, matching this file's existing convention for `foldHotStuffConflicts`/`clampFinalizedHeight`.
    */
  private[mining] def shouldSelfCommit(enabled: Boolean, alreadyCommitted: Boolean): Boolean =
    enabled && !alreadyCommitted
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.mining.MinerSelfCommitSpecification"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/mining/Miner.scala node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala
git commit -m "feat(miner): add pure shouldSelfCommit decision function"
```

---

### Task 3: Wire self-commit into `MinerImpl.forgeBlock`, off the hot path

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/mining/Miner.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala`

**Interfaces:**
- Consumes: `Miner.shouldSelfCommit` (Task 2), `blockchain.committedGenerators(period: GenerationPeriod): IndexedSeq[(Address, BlsPublicKey)]` (`state/Blockchain.scala:95`), `CommitToGenerationTransaction.mkPopSignature`/`.selfSigned` (`transaction/CommitToGenerationTransaction.scala:73-79,134-146`), `UtxPool.putIfNew(tx: Transaction, forceValidate: Boolean = false): TracedResult[ValidationError, Boolean]` (`utx/UtxPool.scala:18`), `wallet: Wallet` (already a `MinerImpl` constructor param, `Miner.scala:63`).
- Produces: `MinerImpl.maybeSelfCommit(account: KeyPair, blockchain: Blockchain, period: GenerationPeriod): Unit` — fire-and-forget, called from `forgeBlock`.

- [ ] **Step 1: Write the failing test — verify the method exists and is a no-op when disabled**

Append to `node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala` — this requires constructing a minimal `MinerImpl`. Check how `MinerBalanceOverflowTest.scala` or `MinerAccountScriptRestrictionsTest.scala` construct `MinerImpl` for a test harness first:

Run: `grep -n "new MinerImpl\|MinerImpl(" node/tests/src/test/scala/com/decentralchain/mining/MinerBalanceOverflowTest.scala node/tests/src/test/scala/com/decentralchain/mining/MinerAccountScriptRestrictionsTest.scala`

Use whatever helper/constructor pattern those two files already use to build a `MinerImpl` against an in-memory test blockchain (this codebase has existing test scaffolding for this — do not hand-roll a new one). Add a test in that same style:

```scala
  "maybeSelfCommit" - {
    "does nothing when selfCommitToGeneration is disabled, even if not committed" in {
      // Arrange a MinerImpl with settings.minerSettings.selfCommitToGeneration = false,
      // an account NOT present in blockchain.committedGenerators(period.next).
      // Act: call miner.maybeSelfCommit(account, blockchain, period) via the harness above.
      // Assert: utx pool size unchanged (no tx submitted) — use the same utx-pool-size
      // assertion style already used in MinerBalanceOverflowTest.scala for verifying
      // "no block was produced"/"no tx added" outcomes.
    }
  }
```

(Fill in the arrange/act/assert using the exact harness helper found by the grep above — do not invent a new test double for `Blockchain`/`UtxPool`; reuse what `MinerBalanceOverflowTest.scala` already sets up.)

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.mining.MinerSelfCommitSpecification"`
Expected: FAIL — `value maybeSelfCommit is not a member of MinerImpl`

- [ ] **Step 3: Implement `maybeSelfCommit` in `MinerImpl`**

Edit `node/src/main/scala/com/decentralchain/mining/Miner.scala`. Add the import at the top (near the other `com.decentralchain.transaction.*` import at line 22, already covers `CommitToGenerationTransaction` — confirm via `grep -n "import com.decentralchain.transaction" node/src/main/scala/com/decentralchain/mining/Miner.scala`, add an explicit import for `com.decentralchain.crypto.bls.BlsKeyPair` and `com.decentralchain.state.GenerationPeriod` if not already in scope):

```scala
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.state.GenerationPeriod
```

Add this method to `MinerImpl`, right after `forgeBlock` (after line 290, before the `tryCollectSelfWithGrace` comment block at line 292):

```scala
  /** In-process replacement for the external auto-commit-generators/commit-generators-hotstuff
    * GH Actions crons (docs/superpowers/plans/2026-09-12-inprocess-self-commit-generation.md).
    * Called from `forgeBlock` at a period boundary, where `committedGenerators(period.next)` is
    * already being read to build `committedGeneratorsHash` -- this reuses that same read rather
    * than a second blockchain query. Dispatched fire-and-forget on `appenderScheduler` (NOT
    * `minerScheduler`, which `forgeBlock` itself runs on -- see the executeOn call at line 486)
    * so a slow BLS sign or a UTX pool contention never delays block forging, which must stay fast.
    * Never constructs PoP bytes by hand -- goes through `CommitToGenerationTransaction.mkPopSignature`
    * exclusively, per the single-source-of-truth requirement documented at
    * `CommitToGenerationTransaction.scala:50-57`.
    */
  private def maybeSelfCommit(account: KeyPair, blockchain: Blockchain, period: GenerationPeriod): Unit = {
    val alreadyCommitted = blockchain.committedGenerators(period.next).exists(_._1 == account.toAddress)
    if (Miner.shouldSelfCommit(minerSettings.selfCommitToGeneration, alreadyCommitted)) {
      Task {
        val blsKeyPair = BlsKeyPair(account.privateKey)
        val chainId    = blockchain.settings.addressSchemeCharacter.toByte
        val signature  = CommitToGenerationTransaction.mkPopSignature(blsKeyPair, period.next.start, account.publicKey, chainId)
        CommitToGenerationTransaction.selfSigned(
          version = 1,
          sender = account,
          endorserPublicKey = blsKeyPair.publicKey,
          generationPeriodStart = period.next.start,
          timestamp = timeService.correctedTime(),
          feeInDcc = CommitToGenerationTransaction.DepositInDcclets,
          commitmentSignature = signature,
          chainId = chainId
        ) match {
          case Right(tx) =>
            utx.putIfNew(tx).resultE match {
              case Right(true)  => log.info(s"Self-committed ${account.toAddress} to generation period ${period.next}")
              case Right(false) => log.debug(s"Self-commit for ${account.toAddress} at period ${period.next}: already in UTX pool")
              case Left(err)    => log.warn(s"Self-commit for ${account.toAddress} at period ${period.next} rejected: $err")
            }
          case Left(err) =>
            log.warn(s"Failed to build self-commit tx for ${account.toAddress} at period ${period.next}: $err")
        }
      }.onErrorHandle(err => log.warn(s"Self-commit for ${account.toAddress} failed: ${err.getMessage}"))
        .runAsyncLogErr(using appenderScheduler)
      ()
    }
  }
```

Check the exact field name for chain id on `Blockchain`/`settings` — run `grep -n "addressSchemeCharacter\|chainId" node/src/main/scala/com/decentralchain/state/Blockchain.scala node/src/main/scala/com/decentralchain/settings/GenesisSettings.scala | head -10` and adjust the `chainId` line above to match whatever the codebase actually exposes (do not guess a name that doesn't compile — this is the one line in this step most likely to need a one-word fix against the real API).

Now wire the call into `forgeBlock`, inside the existing `committedGeneratorsHash` block at lines 242-253 — add the call right after `period` is bound (do not restructure the existing hash computation, just add a side-effecting line inside the same `.map`):

```scala
        committedGeneratorsHash                   = {
          blockchain.generationPeriodOf(newBlockHeight).filter(_.end == newBlockHeight).map { period =>
            maybeSelfCommit(account, blockchain, period)
            val validators = blockchain.committedGenerators(period.next).sortBy(_._1.toString)
            ByteStr(crypto.fastHash(validators.flatMap { case (addr, blsKey) => addr.bytes ++ blsKey.arr }.toArray))
          }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.mining.MinerSelfCommitSpecification"`
Expected: PASS

- [ ] **Step 5: Add a second test — enabled + not committed DOES submit**

Add to the same spec file, using the same harness:

```scala
    "submits a CommitToGenerationTransaction when enabled and not yet committed" in {
      // Arrange: selfCommitToGeneration = true, account absent from committedGenerators(period.next).
      // Act: call maybeSelfCommit, then block on the appenderScheduler's queue draining
      // (the harness for MinerBalanceOverflowTest already handles Task scheduling synchronously
      // for test determinism -- reuse that pattern, do not add Thread.sleep).
      // Assert: utx pool now contains exactly one CommitToGenerationTransaction for `account`.
    }
```

- [ ] **Step 6: Run full miner test suite**

Run: `sbt "node-tests/testOnly com.decentralchain.mining.*"`
Expected: PASS (all pre-existing Miner tests plus the two new ones)

- [ ] **Step 7: Commit**

```bash
git add node/src/main/scala/com/decentralchain/mining/Miner.scala node/tests/src/test/scala/com/decentralchain/mining/MinerSelfCommitSpecification.scala
git commit -m "feat(miner): self-commit to next generation period in-process, off the forging hot path"
```

---

### Task 4: Testnet soak — enable the flag on gen-0/gen-1/val-0, run alongside the external crons

**Files:**
- Modify: `Ecosystem/infra/terraform/testnet.tfvars` or the relevant `nodes.yaml`/Helm values (locate via `grep -rn "self-commit-to-generation\|SELF_COMMIT" Ecosystem/infra/clusters/testnet/`) — add the new config flag, set to `yes`, deployed to gen-0/gen-1/val-0 ONLY (not the main node, not mainnet/stagenet).
- No code changes in this task — this is a deploy + observe step.

**Interfaces:**
- Consumes: the shipped `self-commit-to-generation` config key from Task 1.
- Produces: nothing new — this task's deliverable is a soak observation, not code.

- [ ] **Step 1: Locate where per-node application.conf overrides are templated**

Run: `grep -rln "generation-period-length\|dcc.miner" Ecosystem/infra/clusters/testnet/`

- [ ] **Step 2: Add `self-commit-to-generation = yes` to gen-0/gen-1/val-0's config only**

Do NOT touch the main node's config in this step — the external crons (`auto-commit-generators.yml`, `commit-generators-hotstuff.yml`) still commit the main node during this soak, so if the new in-process path has a bug, only 3 of 4 committee seats are at risk, not all 4, and the external cron remains the safety net for all 4 throughout.

- [ ] **Step 3: Deploy and observe for one full generation period cycle (~50 min) plus a margin**

Run: `kubectl logs -n dcc dcc-gen-0-0 --since=1h | grep "Self-committed\|Self-commit"`
Expected: a `Self-committed ... to generation period [...]` line appears once per period boundary, with no corresponding external-cron commit for gen-0 needed (check the GH Actions run for `auto-commit-generators.yml` during this window — its gen-0 step should log "already committed (no-op)" instead of performing a fresh broadcast).

- [ ] **Step 4: Confirm no committee gap during the soak**

Run: `curl -s https://testnet-node.decentralchain.io/blocks/height/finalized` and `curl -s https://testnet-node.decentralchain.io/blocks/height` — the finality lag should stay bounded (not grow unbounded) across the soak window, matching the existing `Verify next-period committee` check already used in `auto-commit-generators.yml:290-346`.

- [ ] **Step 5: Document the soak result**

Write the observed outcome (pass/fail, any anomalies) as a dated entry appended to this plan file's bottom, under a new `## Soak Log` section — do not create a separate status doc.

---

### Task 5: Retire the external crons

**Files:**
- Modify (delete): `Ecosystem/infra/.github/workflows/auto-commit-generators.yml`
- Modify (delete): `Ecosystem/infra/.github/workflows/commit-generators-hotstuff.yml`
- Modify: `Ecosystem/infra/terraform/testnet.tfvars`/`nodes.yaml` — enable `self-commit-to-generation` on the main node too (the last node still relying on the external cron).

**Interfaces:**
- Consumes: a passing Task 4 soak result.
- Produces: nothing — this is a deletion/cleanup task.

- [ ] **Step 1: Enable self-commit on the main node**

Same config change as Task 4 Step 2, applied to the main node's config this time.

- [ ] **Step 2: Observe one more full period cycle with ALL FOUR nodes self-committing and BOTH external crons still installed but doing nothing**

Run: `kubectl logs ... | grep "Self-committed"` for all 4 identities; confirm both workflows' next scheduled runs log "already committed (no-op)" for every generator, every commit, for one full cycle.

- [ ] **Step 3: Delete both workflow files**

```bash
cd Ecosystem/infra
git rm .github/workflows/auto-commit-generators.yml .github/workflows/commit-generators-hotstuff.yml
git commit -m "chore: retire auto-commit-generators/commit-generators-hotstuff crons, superseded by in-process self-commit (node-scala)"
```

- [ ] **Step 4: Final confirmation — one more full period cycle with the workflows gone**

Run: `curl -s https://testnet-node.decentralchain.io/generators/at/<next-period-start>` — confirm the committee is non-empty with no external automation running at all.

---

## Self-Review Notes

- **Spec coverage:** config flag (Task 1) → pure decision fn (Task 2) → wiring off hot path (Task 3) → soak with external cron as safety net (Task 4) → full retirement (Task 5). Covers the "GO" verdict from the audit: in-process self-commit, gated, off the hot path, no hand-rolled PoP bytes.
- **Placeholder scan:** Task 3/Step 1 and Step 5 test bodies are intentionally left as arrange/act/assert scaffolding with an explicit instruction to grep the existing harness pattern first, because the exact `MinerImpl` test-construction helper in this codebase wasn't read in full during planning (only referenced by filename) — this is flagged, not hidden, and the very first sub-step is a `grep`/read command that resolves it before any code is written. This is the one acceptable exception to "no placeholders": the harness must be copied from a real existing file, not invented, and the plan directs the implementer to that file by name and to the exact grep to run.
- **Type consistency:** `Miner.shouldSelfCommit(enabled: Boolean, alreadyCommitted: Boolean): Boolean` (Task 2) is consumed identically in Task 3's `maybeSelfCommit`. `MinerSettings.selfCommitToGeneration: Boolean` (Task 1) flows into `minerSettings.selfCommitToGeneration` (Task 3) unchanged.
