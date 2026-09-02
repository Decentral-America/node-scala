# BlsCryptoV2 — feature 30: per-context BLS domain separation + chain/sender-bound PoP (audit H2 + M2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close BLS-audit **H2** (one DST shared by three cryptographically distinct message types) and **M2** (PoP binds neither `chainId` nor the committing `sender`) behind a single new on-chain feature **30 "BLS domain separation & bound PoP"**, without invalidating any pre-activation history: every legacy signature that is on chain today must still re-verify byte-for-byte under the legacy DST forever, while every post-activation signature must be produced and verified under a per-context v2 DST — and the PoP message must bind `chainId ‖ senderPublicKey`.

**Architecture:**

- `BlsUtils` keeps `BlsDomainSeparationTag` (the legacy `..._NUL_` tag) **forever** and gains three v2 tags: `BlsPopDomainSeparationTagV2` (`..._POP_`), `BlsEndorseDomainSeparationTagV2` (`..._ENDORSE_`), `BlsHsVoteDomainSeparationTagV2` (`..._HSVOTE_`). `dst` becomes an explicit parameter on `signBasic`/`verifyBasic`/`verifyAgg`/`verify`, defaulted to the legacy tag **in `BlsUtils` only**.
- The `BlsSignature` / `BlsPublicKey` / `BlsKeyPair` wrappers take `dst` with **no default**, forcing every caller to name its context. This is the compiler-enforced guard rail H2 asks for.
- **Two ON-CHAIN contexts are height-gated on the CONTAINING BLOCK's height, never the live tip** (rollback determinism): PoP verification (`BlockDiffer.validateCommitmentsOnSnapshotPath` + `CommitToGenerationTransactionDiff`) and the aggregated endorsement + conflicting-endorsement verification in `state/appender/package.scala`.
- The duplicated PoP message construction (3 copies today) collapses into one SSOT: `CommitToGenerationTransaction.popMessage(chainId, sender, endorserPk, periodStart, cryptoV2)`. v2 layout = `chainId(1) ‖ senderPublicKey(32) ‖ endorserPublicKey(48) ‖ generationPeriodStart(4)` = 85 bytes; legacy layout = `endorserPublicKey(48) ‖ generationPeriodStart(4)` = 52 bytes, unchanged.
- **T2 HotStuff votes/QCs hard-switch** to the `_HSVOTE_` DST when the feature is enabled (HotStuff is off-chain, `dcc.hotstuff.enabled = false` on mainnet; no live vote is consensus-bearing) — **except** block-carried equivocation proofs, which are replayed by every node at the appender and therefore must be verified under the vote DST in force **at the containing block's height**, plus a boundary rule that rejects any proof whose containing block lies in the same generation period as feature 30's activation height.
- Fresh chains get `30 -> 1` in `preActivatedFeatures` (stagenet/mainnet presets + docker custom conf). The live testnet ships the code **unactivated** and activates by vote.

**Audit SSOT:** `docs/hotstuff-bls-crypto-audit-2026-08-31.md` §H2 (lines ~101–125) and §M2 (lines ~142–151). Read both before starting; the recommendations there are the acceptance criteria.

**Tech Stack:** Scala 3, sbt, ScalaTest (`FreeSpec`/`FlatSpec` via `com.decentralchain.test`), `supranational.blst` 0.3.16.0 (BLS12-381, minimal-pubkey-size), `node-testkit` `Domain`/`DomainPresets` harness.

## Global Constraints

- **The legacy DST is load-bearing forever.** Never delete, rename, or repurpose `BlsUtils.BlsDomainSeparationTag`. Every block already on testnet re-verifies under it during replay/rollback. Any change that makes a pre-activation PoP or aggregated endorsement fail is a chain-splitting regression, not a refactor.
- **Gate on the CONTAINING BLOCK's height, never `blockchain.height` read as "the live tip".** Verified call-site facts (do not re-derive):
  - `BlockDiffer.fromBlock` snapshot branch passes `blockchainWithNewBlock` (`BlockDiffer.scala:206,241`) → `.height` IS the new block's height.
  - `BlockDiffer.fromMicroBlockTraced` passes plain `blockchain` (`:358`) → `.height` IS the containing key block's height. Correct in both cases; the helper may read `blockchain.height` directly.
  - `CommitToGenerationTransactionDiff` receives the `SnapshotBlockchain` that already includes the new block (`TransactionDiffer.scala:228`, driven from `BlockDiffer.apply`'s `currBlockchain`), and already relies on that (`blockchain.currentGenerationPeriod`, `Height(blockchain.height)`) → `blockchain.height` IS the containing block's height.
  - `appender.validateFinalizationVoting` already computes `blockHeight = Height(blockchain.height + 1)` (`package.scala:410`) — reuse that value, do not recompute.
- No behaviour change of any kind below the activation height. Every task that touches a verification path must ship a legacy-path counterpart test proving pre-activation bytes still verify.
- Feature ids: **28 is burned** (do not reuse), 29 is `HotStuffEquivocationEvidence`. Use **30**.
- Off-chain paths (`BlockEndorser` signing, `EndorsementStorage` p2p verify, `NodeHotStuffEffects.signVote`, `HotStuffQuorum.verifyVote`/`formQC`/`verifyQC`) are NOT consensus-replayed. They switch by the live-tip flag; that is intentional and is why the boundary rule for block-carried proofs exists.
- Build/verify after every code task:
  `sbt "node/compile" "node-tests/testOnly com.decentralchain.crypto.bls.* com.decentralchain.consensus.hotstuff.* com.decentralchain.state.* com.decentralchain.finalization.* com.decentralchain.features.* com.decentralchain.mining.*"`
- Before any push/PR: local quality gate (scalafmt + scalafix + `-Werror` compile) per repo convention.
- Commits are **sole-authored by jourlez. No `Co-Authored-By` trailers, ever.**
- Branch off `dev` @ `36caa7edc1`, e.g. `feat/bls-crypto-v2`. Use a full clone if isolation is needed — **NOT** `git worktree` (sbt-git/JGit breaks under worktrees in this repo).

---

### Task 1: Feature 30 + `supportsBlsCryptoV2` helper

**Why first:** every later task's gate needs it, and it is independently testable with no crypto involved.

- [ ] **Step 1 (RED): registry + helper spec**

Append to `node/tests/src/test/scala/com/decentralchain/features/BlockchainFeaturesRegistrySpec.scala`:

```scala
  it should "register BLS domain separation & bound PoP as feature 30" in {
    BlockchainFeatures.feature(30) shouldBe Some(BlockchainFeatures.BlsCryptoV2)
    BlockchainFeatures.implemented should contain(30.toShort)
  }

  it should "not have resurrected the burned feature id 28" in {
    BlockchainFeatures.feature(28) shouldBe None
    BlockchainFeatures.implemented should not contain 28.toShort
  }
```

New file `node/tests/src/test/scala/com/decentralchain/state/BlsCryptoV2ActivationHelperSpec.scala`:

```scala
package com.decentralchain.state

import com.decentralchain.db.WithDomain
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.test.FreeSpec

/** `supportsBlsCryptoV2` must behave exactly like its two siblings (`supportsFinalizationVoting`,
  * `supportsHotStuffEquivocationEvidence`): an activation-height comparison against an EXPLICIT
  * height argument, so callers can ask about the containing block rather than the live tip.
  */
class BlsCryptoV2ActivationHelperSpec extends FreeSpec with WithDomain {
  private val activatesAt5 =
    DomainPresets.DeterministicFinality.setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> 5)

  private val never = DomainPresets.DeterministicFinality

  "supportsBlsCryptoV2" - {
    "false below the activation height, true at and above it" in withDomain(activatesAt5) { d =>
      d.blockchain.supportsBlsCryptoV2(4) shouldBe false
      d.blockchain.supportsBlsCryptoV2(5) shouldBe true
      d.blockchain.supportsBlsCryptoV2(6) shouldBe true
    }

    "false at every height when the feature is absent" in withDomain(never) { d =>
      d.blockchain.supportsBlsCryptoV2(1) shouldBe false
      d.blockchain.supportsBlsCryptoV2(Int.MaxValue) shouldBe false
    }
  }
}
```

Run: `sbt "node-tests/testOnly com.decentralchain.features.BlockchainFeaturesRegistrySpec com.decentralchain.state.BlsCryptoV2ActivationHelperSpec"` — expect compile failure (no `BlsCryptoV2`).

- [ ] **Step 2 (GREEN): register the feature**

In `node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala`, after the `HotStuffEquivocationEvidence` line (`:32`):

```scala
  val BlsCryptoV2                     = BlockchainFeature(30, "BLS domain separation & bound PoP")
```

and add `BlsCryptoV2` to the `dict` Seq after `HotStuffEquivocationEvidence` (`:67`). Add a comment above the id-30 line:

```scala
  // Id 28 is deliberately BURNED (never assigned on any DCC network) -- do not reuse it.
```

- [ ] **Step 3 (GREEN): the height helper**

In `node/src/main/scala/com/decentralchain/state/Blockchain.scala`, immediately after `supportsHotStuffEquivocationEvidence` (`:312–313`):

```scala
    /** Audit H2/M2. Mirrors [[supportsFinalizationVoting]] deliberately: the height is an EXPLICIT
      * parameter so every consensus-replayed caller can pass the CONTAINING BLOCK's height rather
      * than the live tip. Passing the tip here would make PoP / endorsement verification depend on
      * when a node happens to validate a block, which is a rollback-determinism bug (a block that
      * verified during initial append would fail on replay after a rollback across the activation
      * height, and vice versa). Off-chain-only callers (p2p endorsement gossip, HotStuff votes) may
      * legitimately use the default.
      */
    def supportsBlsCryptoV2(height: Int = blockchain.height): Boolean =
      blockchain.featureActivationHeight(BlockchainFeatures.BlsCryptoV2).exists(Height(height) >= _)
```

- [ ] **Step 4: verify + commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
sbt "node/compile" "node-tests/testOnly com.decentralchain.features.BlockchainFeaturesRegistrySpec com.decentralchain.state.BlsCryptoV2ActivationHelperSpec"
git add -A && git commit -m "feat(bls): add feature 30 BlsCryptoV2 + supportsBlsCryptoV2 height helper (audit H2/M2)"
```

---

### Task 2: `BlsUtils` DST threading (legacy default — every existing test stays green)

**Why:** make the DST an explicit, per-call value with zero behaviour change, before any caller moves.

- [ ] **Step 1 (RED): tag identity + cross-DST rejection matrix**

Append to `node/tests/src/test/scala/com/decentralchain/crypto/bls/BlsUtilsTest.scala`:

```scala
  // --- H2 (audit): per-context domain separation. The three v2 tags must be pairwise distinct AND
  // pairwise non-interchangeable at verification time. This is the 3x3 matrix the audit asks for:
  // a signature produced in each context must fail verification in the other two, and must fail
  // BY DOMAIN -- not because the messages happen to have different lengths (the accidental,
  // encoding-coincidence protection H2 calls a latent trap). We therefore sign THE SAME BYTES
  // under each tag, so length can play no part.
  "per-context DSTs (audit H2)" - {
    val v2Tags = Seq(
      "POP"    -> BlsUtils.BlsPopDomainSeparationTagV2,
      "ENDORSE" -> BlsUtils.BlsEndorseDomainSeparationTagV2,
      "HSVOTE"  -> BlsUtils.BlsHsVoteDomainSeparationTagV2
    )

    "the four tags are pairwise distinct" in {
      val all = BlsUtils.BlsDomainSeparationTag +: v2Tags.map(_._2)
      all.distinct.size shouldBe all.size
    }

    "each v2 tag keeps the legacy suite prefix so the ciphersuite is unchanged" in {
      v2Tags.foreach { case (_, t) => t should startWith("BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_") }
    }

    "3x3 basic-verify matrix: a signature verifies under its own DST and no other" in {
      val fixedMessage = "identical-bytes-in-every-context".getBytes(StandardCharsets.UTF_8)
      v2Tags.foreach { case (signLabel, signTag) =>
        val sig = BlsUtils.signBasic(privateKey1, fixedMessage, signTag)
        v2Tags.foreach { case (verifyLabel, verifyTag) =>
          val result = BlsUtils.verifyBasic(sig, fixedMessage, publicKey1, verifyTag)
          withClue(s"signed under $signLabel, verified under $verifyLabel: ") {
            if (signLabel == verifyLabel) result shouldBe a[Right[?, ?]]
            else result shouldBe a[Left[?, ?]]
          }
        }
        withClue(s"signed under $signLabel, verified under LEGACY: ") {
          BlsUtils.verifyBasic(sig, fixedMessage, publicKey1, BlsUtils.BlsDomainSeparationTag) shouldBe a[Left[?, ?]]
        }
      }
    }

    "3x3 aggregate-verify matrix: an aggregate verifies under its own DST and no other" in {
      val fixedMessage = "identical-bytes-in-every-context".getBytes(StandardCharsets.UTF_8)
      v2Tags.foreach { case (signLabel, signTag) =>
        val agg = BlsUtils
          .aggSig(Seq(BlsUtils.signBasic(privateKey1, fixedMessage, signTag), BlsUtils.signBasic(privateKey2, fixedMessage, signTag)))
          .value
        v2Tags.foreach { case (verifyLabel, verifyTag) =>
          val result = BlsUtils.verifyAgg(agg, fixedMessage, Seq(publicKey1, publicKey2), verifyTag)
          withClue(s"agg signed under $signLabel, verified under $verifyLabel: ") {
            if (signLabel == verifyLabel) result shouldBe a[Right[?, ?]]
            else result shouldBe a[Left[?, ?]]
          }
        }
      }
    }

    "the legacy tag remains the BlsUtils-level default, so pre-activation bytes keep verifying" in {
      val legacySig = BlsUtils.signBasic(privateKey1, message)
      BlsUtils.verifyBasic(legacySig, message, publicKey1) shouldBe a[Right[?, ?]]
      BlsUtils.verifyBasic(legacySig, message, publicKey1, BlsUtils.BlsDomainSeparationTag) shouldBe a[Right[?, ?]]
      BlsUtils.verifyBasic(legacySig, message, publicKey1, BlsUtils.BlsPopDomainSeparationTagV2) shouldBe a[Left[?, ?]]
    }
  }
```

Run: `sbt "node-tests/testOnly com.decentralchain.crypto.bls.BlsUtilsTest"` — expect compile failure.

- [ ] **Step 2 (GREEN): add the tags and thread `dst`**

In `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala`, replace the tag declaration at `:11` with:

```scala
  /** LEGACY domain-separation tag. Non-standard for a PoP (`_NUL_`, not `_POP_`) and shared across
    * all three signed message types -- exactly audit finding H2. It is kept HERE FOREVER and MUST
    * NOT be deleted, renamed, or repurposed: every PoP, endorsement, and aggregated endorsement
    * already on chain was produced under it, and block replay / rollback across the feature-30
    * activation height re-verifies those bytes. Removing it would be a chain split, not a cleanup.
    */
  val BlsDomainSeparationTag = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_NUL_"

  /** Per-context v2 tags (audit H2). Same BLS12-381 G2 ciphersuite, different hash domain per signed
    * message type, so a signature produced in one context is worthless in another BY DOMAIN rather
    * than by the accidental "the three encodings happen to have distinct lengths" reasoning H2
    * flags as a latent trap. Activated on chain by `BlockchainFeatures.BlsCryptoV2` (feature 30).
    */
  val BlsPopDomainSeparationTagV2     = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_"
  val BlsEndorseDomainSeparationTagV2 = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_ENDORSE_"
  val BlsHsVoteDomainSeparationTagV2  = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_HSVOTE_"
```

Then give the four primitives an explicit trailing `dst` parameter defaulted to the legacy tag (the default lives **only** here):

```scala
  def signBasic(sk: blst.SecretKey, message: Array[Byte], dst: String = BlsDomainSeparationTag): Array[Byte] =
    new blst.P2()
      .hash_to(message, dst, Array.emptyByteArray)
      .sign_with(sk)
      .compress()

  def verifyBasic(
      blsSigBytes: Array[Byte],
      message: Array[Byte],
      blsPkBytes: Array[Byte],
      dst: String = BlsDomainSeparationTag
  ): Either[String, Unit] =
    verify(blsSigBytes, message, new blst.P1_Affine(blsPkBytes), dst)

  def verifyAgg(
      aggSigBytes: Array[Byte],
      message: Array[Byte],
      blsPks: Iterable[Array[Byte]],
      dst: String = BlsDomainSeparationTag
  ): Either[String, Unit] = for {
    // ... body unchanged ...
    res <- verify(aggSigBytes, message, aggPkAffine, dst)
  } yield res

  private def verify(
      blsSigBytes: Array[Byte],
      message: Array[Byte],
      blsPkBytes: blst.P1_Affine,
      dst: String
  ): Either[String, Unit] = try {
    val ctx = new blst.Pairing(true, dst)
    // ... rest unchanged ...
```

Keep every existing H1/M4/L1/L3/L4 scaladoc block and every guard exactly as-is; this task adds a parameter and nothing else. `aggSign` / `aggSig` are DST-agnostic (pure point addition) and are NOT touched.

- [ ] **Step 3: verify the whole suite is still green (this is the real assertion of this task)**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.crypto.bls.* com.decentralchain.consensus.hotstuff.* com.decentralchain.state.* com.decentralchain.finalization.*"
git add -A && git commit -m "feat(bls): thread an explicit per-call DST through BlsUtils, add POP/ENDORSE/HSVOTE v2 tags (audit H2)"
```

---

### Task 3: wrappers take `dst` with NO default + migrate every caller

**Why:** the compiler is the guard rail. With no default on `BlsSignature`/`BlsPublicKey`/`BlsKeyPair`, a future fourth message type cannot silently inherit someone else's domain.

- [ ] **Step 1: make the wrapper signatures mandatory-`dst`**

`node/src/main/scala/com/decentralchain/crypto/bls/BlsSignature.scala`:

```scala
    /** `dst` is DELIBERATELY not defaulted (audit H2): every caller must name its signing context,
      * so adding a new signed message type is a compile error until its domain is chosen. Pass
      * `BlsUtils.BlsDomainSeparationTag` explicitly on pre-activation/legacy paths.
      */
    def verifyBasic(message: Array[Byte], blsPk: BlsPublicKey, dst: String): Either[String, Unit] =
      BlsUtils.verifyBasic(byteStr.arr, message, blsPk.arr, dst)

    def verifyAgg(message: Array[Byte], blsPks: Iterable[BlsPublicKey], dst: String): Either[String, Unit] =
      BlsUtils.verifyAgg(byteStr.arr, message, blsPks.map(_.arr), dst)
```

`node/src/main/scala/com/decentralchain/crypto/bls/BlsPublicKey.scala`:

```scala
    def verify(message: Array[Byte], signature: BlsSignature, dst: String): Boolean =
      BlsUtils.verifyBasic(signature.arr, message, arr, dst).isRight
```

`node/src/main/scala/com/decentralchain/crypto/bls/BlsKeyPair.scala` — on the trait and both implementations:

```scala
sealed trait BlsKeyPair {
  def publicKey: BlsPublicKey

  /** No default `dst` (audit H2) -- see BlsSignature.verifyBasic. */
  def sign(message: Array[Byte], dst: String): BlsSignature
  def verify(message: Array[Byte], signature: BlsSignature, dst: String): Boolean = publicKey.verify(message, signature, dst)
}
```

with `def sign(message: Array[Byte], dst: String): BlsSignature = BlsSignature.unsafe(ByteStr(BlsUtils.signBasic(sk, message, dst)))` in `unsafeFromSecretKey` and in `BlsSeedKeyPair`.

- [ ] **Step 2: migrate every caller, passing the LEGACY tag everywhere (pure mechanical, zero behaviour change)**

Complete caller inventory (verified by grep — do not discover these ad hoc):

| File | Site |
|---|---|
| `block/BlockEndorsement.scala:15` | `signatureValid` → `verifyBasic(..., BlsUtils.BlsDomainSeparationTag)` |
| `block/BlockEndorsement.scala:29` | `sign` → `kp.sign(mkMessage(...), BlsUtils.BlsDomainSeparationTag)` |
| `state/EndorsementStorage.scala:183` | `verifySig` → legacy tag |
| `state/BlockEndorser.scala:174` | via `BlockEndorsement.signed` (no direct change) |
| `state/appender/package.scala:463` | aggregated endorsement `verifyAgg` → legacy tag |
| `state/appender/package.scala:345` (`validateConflictingEndorsement`) | via `signatureValid` |
| `state/diffs/CommitToGenerationTransactionDiff.scala:22` | PoP `verifyBasic` → legacy tag |
| `state/diffs/BlockDiffer.scala:639` | PoP `verifyBasic` → legacy tag |
| `transaction/CommitToGenerationTransaction.scala:51` | `mkPopSignature` → `blsKeyPair.sign(msg, BlsUtils.BlsDomainSeparationTag)` |
| `consensus/hotstuff/HotStuffQuorum.scala:66,134` | `verifyVote` / `verifyQC` → legacy tag |
| `consensus/hotstuff/HotStuffEquivocationProof.scala:48` | `verifyOne` → legacy tag |
| `consensus/hotstuff/NodeHotStuffEffects.scala:61` | `signVote` → legacy tag |
| `utils/UtilApp.scala:381` | `verifyAgg` → legacy tag |

Test/testkit callers to migrate the same way: `node/testkit/.../TxHelpers.scala` (via `mkPopSignature`), `node/tests/.../finalization/BaseFinalizationSpec.scala` (`BlockEndorsement.sign`), `node/tests/.../state/EndorsementStorageSpec.scala:338`, `node/tests/.../crypto/bls/BlsUtilsTest.scala`, `node/tests/.../consensus/hotstuff/*` (any direct `kp.sign(msg)`), `node/tests/.../state/appender/HotStuffEquivocationValidationSpecification.scala:55`, `node/tests/.../mining/HotStuffEquivocationEvidenceE2ESpecification.scala`. Let the compiler enumerate them: `sbt "node/compile" "node-testkit/compile" "node-tests/Test/compile"` and fix each error by adding the legacy tag.

- [ ] **Step 3: verify + commit**

```bash
sbt "node/compile" "node-testkit/compile" "node-tests/Test/compile" "node-tests/testOnly com.decentralchain.crypto.bls.* com.decentralchain.consensus.hotstuff.* com.decentralchain.state.* com.decentralchain.finalization.* com.decentralchain.mining.*"
git add -A && git commit -m "refactor(bls): require an explicit DST on BlsSignature/BlsPublicKey/BlsKeyPair; migrate all callers to the legacy tag (audit H2)"
```

Expected: **all green, no test changed its assertions.** If anything fails, a caller got a non-legacy tag — fix the caller, never the assertion.

---

### Task 4: single-SSOT `popMessage` (legacy behaviour byte-identical)

- [ ] **Step 1 (RED): message-layout spec**

New file `node/tests/src/test/scala/com/decentralchain/transaction/CommitToGenerationPopMessageSpec.scala`:

```scala
package com.decentralchain.transaction

import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec

/** The PoP message is now built in exactly ONE place (audit M2 fix + de-duplication of the three
  * hand-rolled copies that used to live in `mkPopSignature`, `CommitToGenerationTransactionDiff`,
  * and `BlockDiffer.validateCommitmentsOnSnapshotPath`).
  */
class CommitToGenerationPopMessageSpec extends FlatSpec {
  private val sender     = TxHelpers.signer(7)
  private val endorserKp = BlsKeyPair(TxHelpers.signer(8).privateKey)
  private val start      = Height(3000)
  private val chainId    = 'T'.toByte

  "popMessage" should "reproduce the legacy layout byte-for-byte when cryptoV2 = false" in {
    val legacy = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start, cryptoV2 = false)
    legacy shouldBe (endorserKp.publicKey.arr ++ start.toByteArray)
    legacy.length shouldBe 52
  }

  it should "bind chainId and sender when cryptoV2 = true (audit M2)" in {
    val v2 = CommitToGenerationTransaction.popMessage(chainId, sender.publicKey, endorserKp.publicKey, start, cryptoV2 = true)
    v2 shouldBe (Array(chainId) ++ sender.publicKey.arr ++ endorserKp.publicKey.arr ++ start.toByteArray)
    v2.length shouldBe 85
  }

  it should "differ across chain ids and across senders under v2, and NOT under legacy" in {
    def v2(cid: Byte, s: com.decentralchain.account.PublicKey) =
      CommitToGenerationTransaction.popMessage(cid, s, endorserKp.publicKey, start, cryptoV2 = true).toSeq
    def legacy(cid: Byte, s: com.decentralchain.account.PublicKey) =
      CommitToGenerationTransaction.popMessage(cid, s, endorserKp.publicKey, start, cryptoV2 = false).toSeq

    v2('T'.toByte, sender.publicKey) should not be v2('W'.toByte, sender.publicKey)
    v2('T'.toByte, sender.publicKey) should not be v2('T'.toByte, TxHelpers.signer(9).publicKey)
    // This equality IS the M2 finding, pinned so the legacy path can never drift:
    legacy('T'.toByte, sender.publicKey) shouldBe legacy('W'.toByte, TxHelpers.signer(9).publicKey)
  }
}
```

- [ ] **Step 2 (GREEN): the SSOT + wire it into all three sites**

In `node/src/main/scala/com/decentralchain/transaction/CommitToGenerationTransaction.scala`, replace `mkPopSignature` (`:50–53`) with:

```scala
  /** The single source of truth for the bytes a proof of possession covers. Previously constructed
    * by hand in THREE places (`mkPopSignature`, `CommitToGenerationTransactionDiff`,
    * `BlockDiffer.validateCommitmentsOnSnapshotPath`); a divergence between any two of them is a
    * consensus split, so there is now exactly one implementation.
    *
    * `cryptoV2 = false` reproduces the legacy layout `endorserPk(48) ‖ periodStart(4)` byte-for-byte
    * -- it MUST keep doing so forever, because that is what every PoP already on chain signed.
    *
    * `cryptoV2 = true` is the audit-M2 layout `chainId(1) ‖ senderPk(32) ‖ endorserPk(48) ‖
    * periodStart(4)`, which binds the PoP to BOTH the network and the registering account: a PoP
    * harvested from testnet is no longer a valid mainnet PoP, and a PoP lifted out of the mempool
    * cannot be resubmitted under a different sender to front-run the original registration.
    * Verification pairs this with the `_POP_` DST (H2), so a v2 PoP is also unusable in the
    * endorsement or HotStuff-vote contexts.
    */
  def popMessage(
      chainId: Byte,
      sender: PublicKey,
      endorserPublicKey: BlsPublicKey,
      generationPeriodStart: Height,
      cryptoV2: Boolean
  ): Array[Byte] =
    if (cryptoV2) Array(chainId) ++ sender.arr ++ endorserPublicKey.arr ++ generationPeriodStart.toByteArray
    else endorserPublicKey.arr ++ generationPeriodStart.toByteArray

  /** The DST a PoP is produced/verified under, for the given era. */
  def popDst(cryptoV2: Boolean): String =
    if (cryptoV2) BlsUtils.BlsPopDomainSeparationTagV2 else BlsUtils.BlsDomainSeparationTag

  def mkPopSignature(
      blsKeyPair: BlsKeyPair,
      generationPeriodStart: Height,
      sender: PublicKey,
      chainId: Byte,
      cryptoV2: Boolean
  ): BlsSignature =
    blsKeyPair.sign(popMessage(chainId, sender, blsKeyPair.publicKey, generationPeriodStart, cryptoV2), popDst(cryptoV2))
```

Add `import com.decentralchain.crypto.bls.BlsUtils` to the file.

Then rewrite both verification sites to call the SSOT with `cryptoV2 = false` for now (the gate arrives in Tasks 5/6 — keep this task purely a refactor):

- `CommitToGenerationTransactionDiff.scala:21–23`:

```scala
      _ <- Either.raiseUnless(
        BlsUtils
          .verifyBasic(
            tx.commitmentSignature.arr,
            CommitToGenerationTransaction.popMessage(tx.chainId, tx.sender, tx.endorserPublicKey, tx.generationPeriodStart, cryptoV2 = false),
            tx.endorserPublicKey.arr,
            CommitToGenerationTransaction.popDst(cryptoV2 = false)
          )
          .isRight
      )(GenericError("Invalid commitment signature"))
```

- `BlockDiffer.scala:637–646`: the same substitution inside `validateCommitmentsOnSnapshotPath`.

- [ ] **Step 3: update producers**

`node/testkit/src/main/scala/com/decentralchain/transaction/TxHelpers.scala:494` → `CommitToGenerationTransaction.mkPopSignature(endorserKp, generationPeriodStart, sender.publicKey, chainId, cryptoV2 = false)`. Add a `cryptoV2: Boolean = false` parameter to both `commitToGeneration` and `commitToGenerationWithEndorserKey` and thread it through — Task 5's specs need to mint v2 PoPs. Then let the compiler find any other `mkPopSignature` caller (`sbt "node/compile" "node-testkit/compile" "node-tests/Test/compile" "node-it/Test/compile"`).

- [ ] **Step 4: verify + commit**

```bash
sbt "node/compile" "node-testkit/compile" "node-tests/testOnly com.decentralchain.transaction.* com.decentralchain.state.* com.decentralchain.finalization.*"
git add -A && git commit -m "refactor(bls): collapse PoP message construction into CommitToGenerationTransaction.popMessage (audit M2 prep)"
```

Expected: green with **zero** assertion changes — this task must not alter a single byte on any path.

---

### Task 5: height-gated PoP verification (both on-chain sites)

- [ ] **Step 1 (RED): activation-boundary + M2 adversarial spec**

New file `node/tests/src/test/scala/com/decentralchain/state/diffs/CommitToGenerationPopV2Spec.scala`. Cover, using `DomainPresets.DeterministicFinality.setFeaturesHeight(BlockchainFeatures.BlsCryptoV2 -> H)` and `TxHelpers.commitToGeneration(..., cryptoV2 = …)`:

1. `H-1` (pre-activation): a **legacy** PoP is accepted; a **v2** PoP is rejected (`"Invalid commitment signature"`).
2. `H` and `H+1` (post-activation): a **v2** PoP is accepted; a **legacy** PoP is rejected.
3. **M2-a cross-chain:** build the tx with `chainId = 'W'` but mint the PoP for `chainId = 'T'` (and vice versa) under v2 → rejected. The same pair under the **legacy** path → accepted (this pins the finding so the fix stays load-bearing).
4. **M2-b mempool lift:** mint a v2 PoP for sender A, then submit a transaction with the same `endorserPublicKey`/`generationPeriodStart` but `sender = B` → rejected. Same construction under legacy → accepted.
5. **Cross-context transplant fails BY DOMAIN, not length:** take a v2 PoP and verify it as an endorsement (`BlsUtils.verifyBasic(sig, samePopBytes, pk, BlsEndorseDomainSeparationTagV2)`) → `Left`, asserting on the same byte array so length cannot be the discriminator.

Add a companion `node/tests/src/test/scala/com/decentralchain/state/BlsCryptoV2SnapshotPathPopSpec.scala` mirroring cases 1–2 through the **light-node snapshot path** (`BlockDiffer.validateCommitmentsOnSnapshotPath`) — model it on the existing light-node snapshot tests in `node/tests/src/test/scala/com/decentralchain/state/LightNodeTest.scala` (see its `:244` rogue-key comment for the harness shape). Both PoP sites must be gated; guarding only one moves the attack, exactly as `BlockDiffer`'s own scaladoc argues.

- [ ] **Step 2 (GREEN): gate both sites on the containing block's height**

`CommitToGenerationTransactionDiff.scala` — replace the `cryptoV2 = false` literals:

```scala
      // Gate on the height of the block that CONTAINS this transaction. `blockchain` here is the
      // SnapshotBlockchain that already includes the new block (TransactionDiffer is driven from
      // BlockDiffer.apply's currBlockchain), which is why the surrounding code already treats
      // `blockchain.height`/`currentGenerationPeriod` as "this block". Reading a live tip instead
      // would break rollback determinism across the activation height.
      cryptoV2 = blockchain.supportsBlsCryptoV2(blockchain.height)
      _ <- Either.raiseUnless(
        BlsUtils
          .verifyBasic(
            tx.commitmentSignature.arr,
            CommitToGenerationTransaction.popMessage(tx.chainId, tx.sender, tx.endorserPublicKey, tx.generationPeriodStart, cryptoV2),
            tx.endorserPublicKey.arr,
            CommitToGenerationTransaction.popDst(cryptoV2)
          )
          .isRight
      )(GenericError("Invalid commitment signature"))
```

`BlockDiffer.validateCommitmentsOnSnapshotPath` — hoist the flag once outside `loop` (it is constant for the block) and use it identically:

```scala
      val cryptoV2 = blockchain.supportsBlsCryptoV2(blockchain.height)
```

Extend the method's scaladoc with a short paragraph: on the key-block path `blockchain` is `blockchainWithNewBlock`, on the microblock path it is the containing key block's chain — in both cases `.height` IS the containing block's height, which is the only rollback-deterministic gate source.

- [ ] **Step 3: verify + commit**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.state.diffs.CommitToGenerationPopV2Spec com.decentralchain.state.BlsCryptoV2SnapshotPathPopSpec com.decentralchain.state.* com.decentralchain.finalization.*"
git add -A && git commit -m "feat(bls): height-gate PoP verification on feature 30; PoP binds chainId+sender post-activation (audit M2)"
```

---

### Task 6: height-gated endorsement verification (aggregated + conflicting)

- [ ] **Step 1 (RED): endorsement-era spec**

New file `node/tests/src/test/scala/com/decentralchain/finalization/BlsCryptoV2EndorsementSpec.scala`, extending `BaseFinalizationSpec`. Add a `dst`-aware variant of the existing `FinalizationVoting.signed` / `mkConflictEndorsement` helpers (parameterize them on `cryptoV2: Boolean` in `BaseFinalizationSpec` rather than duplicating), then assert:

1. Pre-activation block: aggregated endorsement signed under the **legacy** ENDORSE bytes/DST → block accepted; signed under **v2** → rejected.
2. Post-activation block: **v2** accepted, **legacy** rejected.
3. Same two cases for a **conflicting** endorsement (`validateConflictingEndorsement` → `BlockEndorsement.signatureValid`), which is on-chain too and is gated on the same block height.
4. Cross-context: an aggregated endorsement's bytes signed under the `_POP_` DST → rejected post-activation.

- [ ] **Step 2 (GREEN): thread the era into both appender endorsement checks**

In `node/src/main/scala/com/decentralchain/state/appender/package.scala`:

- `validateFinalizationVoting` already computes `blockHeight = Height(blockchain.height + 1)` (`:410`). Immediately after it add:

```scala
          // Feature-30 era for THIS block. Deliberately derived from the containing block's height,
          // never the live tip: an endorsement's DST must be a pure function of the block that
          // carries it, or a rollback across the activation height would re-validate the same block
          // under a different domain (audit H2).
          endorsementDst = if (blockchain.supportsBlsCryptoV2(blockHeight)) BlsUtils.BlsEndorseDomainSeparationTagV2
                           else BlsUtils.BlsDomainSeparationTag
```

- pass `endorsementDst` as the new `verifyAgg` argument at `:463–467`;
- pass it into `validateConflictingEndorsement` as a new parameter and on to `conflictingEndorsement.signatureValid(blsPublicKey, endorsementDst)`;
- `BlockEndorsement.signatureValid` gains a `dst: String` parameter (no default) and forwards it.

- [ ] **Step 3 (GREEN): producer + p2p sides switch on the live tip (off-chain, intentional)**

- `BlockEndorsement.sign` / `signed` gain `cryptoV2: Boolean` (no default) and select the DST via a new `BlockEndorsement.dst(cryptoV2)` helper.
- `BlockEndorser.InMemory` (`:174`) passes `blockchain.supportsBlsCryptoV2(votingHeight)` — it is signing an endorsement destined for the block at `votingHeight`, so this is the same "containing block" notion, not a bare tip read.
- `EndorsementStorage.verifySig` (`:183`) must use the era of the block the endorsement targets. `EndorsementFilter` already carries `finalizedHeight`/`endorsedId`; add a `cryptoV2: Boolean` field to `EndorsementFilter`, set by `BlockEndorser` from the same `votingHeight` it uses for signing, and read it in `verifySig`. This keeps signer and verifier on one era for a given voting round instead of two independent tip reads that can straddle the boundary.

- [ ] **Step 4: verify + commit**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.finalization.* com.decentralchain.state.*"
git add -A && git commit -m "feat(bls): height-gate aggregated and conflicting endorsement verification on feature 30 (audit H2)"
```

---

### Task 7: HotStuff vote/QC hard switch to the HSVOTE DST

- [ ] **Step 1 (RED): quorum-level spec**

Append to `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffQuorumSpecification.scala` (or a new `HotStuffVoteDstSpecification.scala`):

1. `verifyVote` / `verifyQC` with `cryptoV2 = true` accept votes signed under `_HSVOTE_` and reject legacy-signed votes.
2. With `cryptoV2 = false`, the mirror image (legacy accepted, `_HSVOTE_` rejected).
3. `formQC` over `_HSVOTE_` votes then `verifyQC(..., cryptoV2 = true)` → `Right`; `verifyQC(..., cryptoV2 = false)` on the same QC → `Left`.
4. Transplant: a v2 **PoP** signature offered as a vote signature over the same bytes → `Left` under `_HSVOTE_`.

- [ ] **Step 2 (GREEN): thread `cryptoV2` through the pure quorum layer**

`HotStuffQuorum.scala`: add `def voteDst(cryptoV2: Boolean): String = if (cryptoV2) BlsUtils.BlsHsVoteDomainSeparationTagV2 else BlsUtils.BlsDomainSeparationTag`, then add a `cryptoV2: Boolean` parameter (no default — `HotStuffQuorum` is pure and safety-critical; a default here is exactly the trap H2 describes) to `verifyVote`, `formQC`, and `verifyQC`, using `voteDst(cryptoV2)` on every `verifyBasic`/`verifyAgg`. `voteMessage` is unchanged: the domain, not the message, carries the context.

- [ ] **Step 3 (GREEN): wire the flag from the shell**

- `HotStuffEngine`'s `HotStuffState` gains `cryptoV2: Boolean` (it already carries `committee` and `committeeEpoch`; this is the same kind of ambient consensus parameter) and passes it to `verifyQC` at `:64` and `:96`.
- `HotStuffVotePool.onVote` (`:115`, `:143`, `:160`) takes `cryptoV2: Boolean` alongside `liveCommittee`.
- `HotStuffCoordinator.Enabled` gains a `cryptoV2: () => Boolean` provider (same shape as the existing `committeeEpochOf`/`tipHeight`/`maxTargetLag` providers), used at `castVotes` (`:410`) and for every pool/engine call.
- `NodeHotStuffEffects.signVote` gains `dst: String`; the coordinator passes `HotStuffQuorum.voteDst(cryptoV2())`.
- `Application.scala` supplies `cryptoV2 = () => blockchainUpdater.supportsBlsCryptoV2()` (live tip is correct here — votes are off-chain and never replayed).

- [ ] **Step 4: verify + commit**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.*"
git add -A && git commit -m "feat(hotstuff): hard-switch vote/QC signing and verification to the HSVOTE DST under feature 30 (audit H2)"
```

---

### Task 8: equivocation-proof DST + generation-period boundary rule

**This is the one place a hard switch would be wrong.** A proof is signed off-chain by whoever detected it, then carried inside a block and re-verified deterministically by every node forever. Its DST must therefore come from the **containing block's height**, and a proof minted during the activation period — where honest replicas may legitimately hold different `supportsBlsCryptoV2()` tip answers while signing — must be refused outright rather than adjudicated.

- [ ] **Step 1 (RED): proof-boundary spec**

New file `node/tests/src/test/scala/com/decentralchain/state/appender/BlsCryptoV2EquivocationProofBoundarySpec.scala`, modelled on `HotStuffEquivocationValidationSpecification` (same `withCommittedCommittee` harness, `generationPeriodLength = 2`):

1. Containing block **below** activation, proof signed under the **legacy** vote DST → accepted; the same proof signed under `_HSVOTE_` → rejected.
2. Containing block in a generation period **strictly after** the activation period, proof signed under `_HSVOTE_` → accepted; legacy-signed → rejected.
3. **Boundary rule:** containing block in the **same generation period as the activation height**, proof signed under **either** DST → rejected, with an error mentioning the activation period.
4. Also assert the boundary rule is a no-op cost when feature 30 activates at or before feature 29's activation height (i.e. no legitimate proof is ever refused on a chain where both features are pre-activated at height 1) — the fresh-chain configuration of Task 10.

- [ ] **Step 2 (GREEN): `signaturesValid` takes a DST**

`HotStuffEquivocationProof.signaturesValid(blsKeyOf, dst: String)` (no default), forwarding `dst` into `verifyOne`'s `BlsUtils.verifyBasic`. Document that the DST is supplied by the caller because it is a function of the containing block, not of the proof.

- [ ] **Step 3 (GREEN): appender-side era + boundary rule**

In `validateHotStuffEquivocationProofs` (`node/src/main/scala/com/decentralchain/state/appender/package.scala:353`), which already receives `blockHeight: Int` and `blockGenerationPeriodIndex: Int`, add after the existing feature-29 gate:

```scala
        // Boundary rule (audit H2). A block-carried proof's vote DST is a function of the CONTAINING
        // block's height, so it is deterministic on replay. But the proof's SIGNER chose its DST
        // off-chain from its own live tip, and during the generation period that contains feature
        // 30's activation height two honest replicas can legitimately disagree about that tip. We
        // refuse every proof carried by a block in the activation period rather than try to accept
        // either DST there -- accepting both would hand an attacker a free cross-domain oracle for
        // exactly one period, and accepting one would make honest evidence unusable at random.
        // Cost is ZERO whenever feature 30 activates at or before feature 29 (e.g. any chain where
        // both are pre-activated at height 1): no proof can exist in that period at all.
        _ <- blockchain.featureActivationHeight(BlockchainFeatures.BlsCryptoV2) match {
          case Some(activationHeight) =>
            blockchain
              .generationPeriodOf(activationHeight)
              .map(_.index)
              .fold(Either.unit[String]) { activationPeriodIndex =>
                Either.raiseWhen(blockGenerationPeriodIndex == activationPeriodIndex)(
                  s"HotStuff equivocation proofs are not accepted in the BlsCryptoV2 activation period " +
                    s"(period $activationPeriodIndex, activation height $activationHeight)"
                )
              }
          case None => Either.unit
        }
        proofDst = HotStuffQuorum.voteDst(blockchain.supportsBlsCryptoV2(blockHeight))
```

and change the per-proof check to `proof.signaturesValid(i => commitedGenerators.lift(i).map(_._2), proofDst)`.

- [ ] **Step 4: verify + commit**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.state.appender.* com.decentralchain.consensus.hotstuff.* com.decentralchain.mining.HotStuffEquivocationEvidenceE2ESpecification"
git add -A && git commit -m "feat(hotstuff): verify block-carried equivocation proofs under the containing block's vote DST; refuse proofs in the activation period (audit H2)"
```

---

### Task 9: legacy-vector regression, rollback determinism, and the full matrix

**Why last among the code tasks:** these are the tests that would catch a regression introduced by any of Tasks 2–8, so they must run against the finished behaviour.

- [ ] **Step 1: capture or synthesize legacy vectors**

Preferred: pull a real pre-activation `CommitToGenerationTransaction` and a real `FinalizationVoting.aggregatedEndorsement` off the live testnet, e.g.

```bash
curl -s 'http://localhost:6869/blocks/at/<height>' | jq '.transactions[] | select(.type==20)'
curl -s 'http://localhost:6869/blocks/headers/at/<height>' | jq '.finalizationVoting'
```

(Adjust host/port to the running node; the endpoint set is whatever `/blocks/at` exposes on this build.) If no node is reachable, synthesize instead: mint a PoP and an aggregated endorsement under the legacy tag with fixed, hard-coded seeds, print the Base64 bytes once, and paste them into the spec as literals. **Either way the bytes must be literals in the test file** — a vector regenerated at runtime from current code cannot detect the regression it exists to catch.

- [ ] **Step 2: the legacy-vector spec**

New file `node/tests/src/test/scala/com/decentralchain/crypto/bls/BlsLegacyVectorRegressionSpec.scala`: pinned Base64 `(pk, message, signature)` triples for (a) a legacy PoP and (b) a legacy aggregated endorsement over a known signer set, asserting for each that verification under `BlsDomainSeparationTag` is `Right` and under each v2 tag is `Left`. Head the file with a comment stating that these bytes exist on chain, that the file must never be regenerated to "fix" a failure, and where they came from (block height + tx id, or the synthesis seeds).

- [ ] **Step 3: the rollback-determinism spec**

New file `node/tests/src/test/scala/com/decentralchain/state/BlsCryptoV2RollbackDeterminismSpec.scala`:

1. Activate feature 30 at height `H`. Append a legacy-PoP commitment block at `H-1` and a v2-PoP commitment block at `H+1`.
2. `d.rollbackTo(H - 2)`; re-append both blocks in order. Both must succeed again, with identical resulting state hashes — proving the gate is a function of block height, not of when validation ran.
3. Negative control: after the rollback, re-append the `H-1` block's transaction inside a block at `H+1` → must be rejected (its legacy PoP is invalid in the v2 era). This is what proves the gate is actually height-derived rather than accidentally per-transaction.

- [ ] **Step 4: verify + commit**

```bash
sbt "node-tests/testOnly com.decentralchain.crypto.bls.* com.decentralchain.state.BlsCryptoV2RollbackDeterminismSpec"
git add -A && git commit -m "test(bls): pin legacy DST vectors and prove rollback determinism across the feature-30 boundary (audit H2/M2)"
```

- [ ] **Step 5: full-suite gate**

```bash
sbt "node/compile" "node-testkit/compile" "node-tests/test"
```

Everything green before Task 10.

---

### Task 10: fresh-chain configuration + documentation

- [ ] **Step 1: pre-activate 30 on fresh chains**

`node/src/main/scala/com/decentralchain/settings/BlockchainSettings.scala`:

- `FunctionalitySettings.STAGENET` (`:176`): its `preActivatedFeatures` is `(1 to 13).map(_.toShort -> 0).toMap` — change to `((1 to 13).map(_.toShort -> 0) :+ (BlockchainFeatures.BlsCryptoV2.id -> 1)).toMap`.
- `FunctionalitySettings.MAINNET`: it currently declares no `preActivatedFeatures`; add `preActivatedFeatures = Map(BlockchainFeatures.BlsCryptoV2.id -> 1)`.
- `TESTNET`: **unchanged** — the live testnet ships this unactivated and activates by vote.

`docker/private/decentralchain.custom.conf` (`:46` area, where `25 = 0` lives): add `30 = 1` so fresh private/dev chains get the v2 crypto from genesis. Do the same in `node/src/main/resources/network-defaults.conf`'s `devnet` `pre-activated-features` block if that chain is expected to be regenerated.

Add a spec asserting the presets: `node/tests/src/test/scala/com/decentralchain/settings/BlsCryptoV2PreActivationSpec.scala` — `FunctionalitySettings.MAINNET.preActivatedFeatures.get(30.toShort) shouldBe Some(1)`, same for `STAGENET`, and `FunctionalitySettings.TESTNET.preActivatedFeatures.get(30.toShort) shouldBe None`.

- [ ] **Step 2: `application.conf` comment**

Under `dcc.hotstuff` in `node/src/main/resources/application.conf` (near the `enabled`/`slashing-enabled` comments), add:

```
    # BLS domain separation (feature 30, audit H2/M2): HotStuff vote/QC signatures switch to the
    # _HSVOTE_ domain-separation tag once feature 30 activates on this chain. There is no config
    # knob -- the switch is driven purely by on-chain activation, so all peers agree. Block-carried
    # equivocation proofs are verified under the DST in force at the CONTAINING block's height, and
    # proofs are refused outright in the generation period containing the activation height.
```

- [ ] **Step 3: audit doc status**

In `docs/hotstuff-bls-crypto-audit-2026-08-31.md`, mark **H2** and **M2** as fixed in place (do not delete the findings — the reasoning is the SSOT for the tests). For each, append a `**Status (2026-09-02): FIXED — feature 30 `BlsCryptoV2`.**` paragraph naming: the three v2 tags, `popMessage`'s v2 layout, the two height-gated on-chain sites, the HotStuff hard switch, the equivocation-proof boundary rule, and the specs that prove each. Also update the §"Findings by severity" summary line and item 2 of the closing list (`:213`), which currently says both are open and must land before mainnet enablement.

- [ ] **Step 4: audit-readiness doc**

In `docs/hotstuff-audit-readiness.md`:

- Threat table: **T1** (rogue-key forgery) — note the PoP now binds `chainId ‖ sender` and lives in its own `_POP_` domain post-feature-30. **T8** (cross-period replay) — note that cross-CHAIN and cross-SENDER replay are now closed too, and that "framing note = finding #4" is superseded by per-context DSTs.
- §5 code surface: add `crypto/bls/BlsUtils.scala` (DST SSOT, **highest** priority for a crypto auditor) and `CommitToGenerationTransaction.popMessage` (PoP message SSOT).
- §7 residual risk: record what feature 30 does **not** close — the legacy DST remains reachable forever for pre-activation history (by design), and the off-chain endorsement/vote paths switch on a live-tip read rather than a block height (safe because they are not consensus-replayed; the block-carried proof path is the one that needed the boundary rule).
- §8 enable-gate checklist: add "feature 30 activated on the target network" as a prerequisite for `dcc.hotstuff.authoritative = true` on mainnet.

- [ ] **Step 5: verify + commit**

```bash
sbt "node/compile" "node-tests/testOnly com.decentralchain.settings.* com.decentralchain.features.*"
git add -A && git commit -m "feat(bls): pre-activate feature 30 on fresh chains; record H2/M2 as fixed in the audit docs"
```

---

## Definition of done

- `sbt "node-tests/test"` fully green; `sbt "node/compile"` clean under `-Werror`.
- The 3x3 cross-DST matrix, pinned legacy vectors, activation-boundary, rollback-determinism, equivocation-proof-boundary, and both M2 adversarial specs (each with its legacy-path counterpart) all present and passing.
- No wrapper (`BlsSignature`, `BlsPublicKey`, `BlsKeyPair`) has a defaulted `dst`; `BlsUtils` is the only place a legacy default exists.
- PoP bytes are constructed in exactly one function.
- H2 and M2 marked FIXED in `docs/hotstuff-bls-crypto-audit-2026-08-31.md`, with the audit-readiness doc updated.
