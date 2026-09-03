# BLS Aggregate-Signature Scheme — Internal Cryptographic Audit

> **Scope:** `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala` and its callers
> (`BlsPublicKey`, `BlsSignature`, `BlsKeyPair`, `HotStuffQuorum`, `BlockEndorsement`,
> `CommitToGenerationTransactionDiff`, `state/appender`, `EndorsementStorage`), plus
> `node/tests/src/test/scala/com/decentralchain/crypto/bls/BlsUtilsTest.scala`.
> **Library:** `io.decentralchain % blst % 0.3.16.0` (supranational blst), BLS12-381,
> minimal-pubkey-size (PK in G1, signature in G2).
> **Date:** 2026-08-31. **Reviewer:** internal adversarial crypto review.
>
> ⚠️ This is an **internal** review conducted in lieu of an external audit. It is deliberately
> skeptical and verifies claims against source rather than against prior documentation. It is
> **not** a substitute for a paid third-party cryptographic audit, and several items below
> (constant-time behavior in particular) cannot be settled by code reading alone.

---

## 1. Verified — sound

| # | Area | Verification |
|---|------|--------------|
| V1 | **Proof-of-possession exists and is genuinely enforced on the full-node path** | Verified directly, not taken from `docs/hotstuff-security-review.md:16`. `CommitToGenerationTransactionDiff.scala:21–23` calls `BlsUtils.verifyBasic(tx.commitmentSignature.arr, tx.endorserPublicKey.arr ++ tx.generationPeriodStart.toByteArray, tx.endorserPublicKey.arr)` and rejects on `Left`. The message genuinely includes the claimed key, so it is a real PoP, not a self-signature over unrelated data. Producer side is `CommitToGenerationTransaction.mkPopSignature` (`:50–52`), byte-identical construction. |
| V2 | **PoP binds the generation period** | The signed message includes `generationPeriodStart`, and `:18–20` pins that to `current.next.start`. A PoP harvested from one period cannot be replayed into another. (It does **not** bind chain id or sender — see M2.) |
| V3 | **Duplicate BLS key registration is rejected** | `CommitToGenerationTransactionDiff.scala:27–33` rejects both a duplicate sender address and a duplicate `endorserPublicKey` within the next period's committed set. This removes the trivial "register the honest party's key as mine" variant. |
| V4 | **Point-at-infinity rejection ("Task 20") is present and correctly wired — with one caveat** | Verified, not assumed. `BlsUtils.validatePublicKey` (`:82–86`) performs `in_group()` **and** `is_inf()` and is reachable via `BlsPublicKey.validated` (`BlsPublicKey.scala:22`). The single production call site is `CommitToGenerationTransactionDiff.scala:26`, i.e. at registration time. Additionally `verify` (`BlsUtils.scala:65–76`) now **checks** the `BLST_ERROR` returned by `ctx.aggregate` instead of discarding it, so an infinity or off-curve pubkey surfaces as `BLST_PK_IS_INFINITY` → `Left`. Both halves of the Task-20 fix are present. Caveat: the fix protects the *registration* and *single-key verify* paths; it does **not** cover the aggregate path (see H1) nor the light-node path (see C1). |
| V5 | **`verify` returns a typed error and does not swallow failures** | `verify` returns `Either[String, Unit]`, checks `aggResult != BLST_SUCCESS` before `commit()`/`finalverify()`, and wraps the whole body in `catchNonFatal` so a malformed-point `RuntimeException` from `new blst.P2_Affine(bytes)` becomes a `Left`, not a thrown exception that could crash a network thread. Deserialization failure is therefore fail-closed. |
| V6 | **`aggSig` is fail-closed on empty input** | `BlsUtils.scala:46–51` explicitly rejects an empty signature list before aggregating. This matters: a `reduceLeft` fold over an empty set would have thrown, and — worse — an "aggregate of nothing" is the identity element, which is exactly the point-at-infinity forgery primitive. Same guard exists in `verifyAgg` for the pubkey list (`:58`). Correctly identified and closed. |
| V7 | **Length checks precede deserialization on the wire path** | `sanityCheckPublicKey` / `sanityCheckSignature` (`:88–97`) enforce exactly 48 / 96 bytes and are invoked by the `BlsPublicKey.apply` / `BlsSignature.apply` smart constructors, which are what protobuf decoding goes through (`PBTransactions.scala:332`, `EndorsementStorage.scala:181`). A truncated or over-long field cannot reach `blst`. |
| V8 | **Compressed encoding is canonical by construction** | All serialization uses `.compress()` (48/96 bytes). blst's `P1_Affine`/`P2_Affine` decompression enforces the correct sign bit and rejects a non-canonical `x`-coordinate ≥ p, so there is no trivial encoding malleability. Combined with V7's exact-length check, the byte↔point mapping is injective. |
| V9 | **HotStuff vote message is unambiguous and binds the committee epoch** | `HotStuffQuorum.voteMessage` (`:40–41`) = `view(4 BE) ++ phase(1) ++ blockId ++ height(4 BE) ++ committeeEpoch(4 BE)`. `Height` is `opaque type Height = Int` with `toByteArray = Ints.toByteArray` (`state/package.scala:41,63`), so all non-`blockId` fields are genuinely fixed-width. Epoch binding closes the cross-committee fork hazard as documented. |
| V10 | **QC verification re-derives the signer set from the committee** | `verifyQC` (`:128–139`) rejects unknown signer indexes, re-checks the 2/3-stake quorum, and passes the committee's *own stored* pubkeys — not attacker-supplied ones — into `verifyAgg`. An attacker cannot inject a key at QC-verification time. |
| V11 | **`ThreadLocalRandom` / no ad-hoc RNG in the BLS path** | `BlsUtils` contains no RNG. Keys are deterministically derived from the node's existing Curve25519 private key via `keygen_v5` (HKDF, `BlsKeyGenSalt = "BLS-SIG-KEYGEN-SALT-"`), so BLS key entropy equals node-key entropy — no new weak-randomness surface is introduced. `scala.util.Random` was previously purged repo-wide (commit `f212f091f0`). |

---

## 2. Findings by severity

**Status summary (2026-09-02, updated same day):** C1 remains open. H2 and M2 are **FIXED, and
unconditional** — per-context DSTs + chain-id/sender-bound PoP are the only crypto in production on
every network, with no activation gate at all (the feature-30 `BlsCryptoV2` gate this fix originally
shipped behind was itself deleted later the same day, once it was confirmed no chain in this repo's
history had ever activated it); see their STATUS notes below. M1/M3/M4 and the LOW/INFO items remain
open/unaddressed by this task.

> **CORRECTION (2026-09-03):** "the only crypto in production" (H2/M2, above and below) no longer
> holds without qualification for *verification*. A VERIFY-ONLY legacy BLS domain-separation fallback
> (`BlsUtils.BlsLegacyDomainSeparationTag`, the `_NUL_` tag) was reintroduced after this audit was
> written, because the real testnet-relaunch chain pre-activates feature 25 (`DeterministicFinality`)
> at genesis and carries real, legitimate signatures produced under that legacy tag — confirmed by a
> live chain replay reaching height 2639, which falsified this audit's original premise that no kept
> chain had ever activated the relevant feature. **New signing is unaffected and remains v2-only**:
> every new PoP, endorsement, and HotStuff vote/QC is still produced exclusively under the three
> per-context v2 DSTs. Only historical verification is bimodal (v2 first, legacy fallback for
> pre-existing signatures). See the H2 finding below and its own correction note for detail.

### CRITICAL

#### C1 — Light-node mode entirely bypasses BLS proof-of-possession and curve validation for committed-generator registration

**Files:**
- `node/src/main/scala/com/decentralchain/state/diffs/BlockDiffer.scala:237–257` (the fork)
- `BlockDiffer.scala:552–577` (the snapshot overload)
- `node/src/main/scala/io/decentralchain/protobuf/PBSnapshots.scala:175–177`
- `node/src/main/scala/com/decentralchain/state/appender/package.scala:37–41`
- `node/src/main/scala/com/decentralchain/state/diffs/CommitToGenerationTransactionDiff.scala:21–26`

`BlockDiffer.fromBlock` branches on whether a peer-supplied `BlockSnapshot` is present:

```scala
r <- snapshot match {
  case Some(BlockSnapshot(_, txSnapshots)) => TracedResult.wrapValue(apply(..., txSnapshots))   // no TransactionDiffer
  case None                                => apply(..., verify = verify, ...)                  // full validation
}
```

The `Some` branch (`:552–577`) contains **no `TransactionDiffer` and no `verify` parameter**; it folds the peer's supplied per-transaction snapshots straight into state and returns a bare `Result` that cannot fail. `TransactionDiffer.scala:228` is the *only* caller of `CommitToGenerationTransactionDiff`. Consequently, for a light node (`enable-light-mode = true`, `application.conf:13`) syncing a block containing a `CommitToGenerationTransaction`:

- the **BLS proof-of-possession check is never executed** (`CommitToGenerationTransactionDiff.scala:21–23`),
- the **`endorserPublicKey.validated` in-group / not-infinity check is never executed** (`:26`),
- the duplicate-key, period-start and min-balance checks are likewise skipped.

The committed-generator set is instead taken verbatim from the serving peer at `PBSnapshots.scala:175–177`:

```scala
x.senderPublicKey.toPublicKey -> BlsPublicKey(x.endorserPublicKey.toByteArray).explicitGet()
```

`BlsPublicKey.apply` performs **only** `sanityCheckPublicKey` — a 48-byte length check (`BlsUtils.scala:88–91`). No subgroup check, no infinity check, no PoP. The result persists through `SnapshotBlockchain.scala:258` → `Caches.scala:397` → `RocksDBWriter.scala:744`.

**Why the state-hash backstop does not save this:** `TxStateSnapshotHashBuilder.scala:100–107` explicitly excludes `nextCommittedGenerators` from the per-transaction state hash, so a forged BLS key produces no hash divergence. The intended dedicated backstop, `BlockDiffer.checkCommittedGeneratorsHash` (`:282–306`), returns `Right(())` unconditionally when the header field is absent (`:288`) — a condition the in-file comment at `:270–281` already concedes provides "NO real enforcement."

**Impact.** This re-opens the exact rogue-key attack that `docs/hotstuff-security-review.md:16` claims is defended. A malicious serving peer can hand a light node a committed-generator set containing a key for which it knows no discrete log — including a key chosen as `−Σ(honest keys) + g^x`, the textbook rogue-key cancellation — and that node will then accept aggregate signatures/QCs that no honest quorum ever produced. It can also seat the **point at infinity** as a generator key, which as an aggregation identity is invisible in `verifyAgg`'s `reduce(_.add(_))`. A light node is not a miner (`Application.scala:181`), so this is not directly a chain-forking bug, but it is a full break of the finality guarantee *as observed by light nodes* — exactly the class of client that most needs it.

**Recommendation.** Either (a) re-run the security-critical subset of transaction validation on the snapshot path — at minimum PoP + `validated` for every `CommitToGenerationTransaction` in the block — or (b) enforce `validated` unconditionally inside `PBSnapshots.fromProtobuf` and carry the PoP signature in the snapshot so it can be re-checked, or (c) include `nextCommittedGenerators` in the state hash **and** make `checkCommittedGeneratorsHash` mandatory rather than skip-on-absent. Option (a) is the only one that closes the rogue-key hole rather than just the malformed-point hole; (b) alone still permits a rogue key that is a perfectly valid group element.

---

### HIGH

#### H1 — `verifyAgg` performs no subgroup check on the aggregated public key; small-subgroup / rogue-key defense rests entirely on the registration path

**File:** `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala:57–63`

```scala
aggPk <- Either.catchNonFatal(blsPks.map(new blst.P1(_)).reduce(_.add(_)))
res   <- verify(aggSigBytes, message, new blst.P1_Affine(aggPk))
```

`new blst.P1(bytes)` decompresses and checks the point is **on the curve**, but does *not* check `in_group()` (blst deliberately separates these; `in_group` is the expensive subgroup test). The scaladoc mitigates this by contract — *"`blsPks` Expected to have validated public keys"* — and on the full-node path that contract is met (V1/V4). But it is a *comment-enforced* invariant on a security-critical primitive, with no defense in depth:

1. Under C1 the contract is straightforwardly violated for light nodes.
2. `ctx.aggregate` inside `verify` catches the point-at-infinity case (`BLST_PK_IS_INFINITY`) for the *sum*, but a set of individually-non-infinity keys summing to a non-trivial small-subgroup element is not caught, and blst's `Pairing.aggregate` in the aggregate-verify configuration does not re-run a subgroup test on a caller-supplied affine point.
3. `BlsSignature.agg` / `aggSign` (`:37–38`, `BlsSignature.scala:22`) likewise decompress without `in_group()` on the G2 side, and `aggSign` has **no length pre-check at all** — it takes raw `Array[Byte]` and will throw out of `new blst.P2(...)` on malformed input rather than returning `Left`. It is used from `BlsSignature.append` and from test code; it is the one function in the file that is not fail-closed.

Note also the scaladoc on `aggSig` — *"@return Not validated, but must be in the group"* — which is an explicit, unresolved acknowledgement that an unvalidated element is being returned into the system.

**Recommendation.** Make `verifyAgg` defensive rather than contract-dependent: run `in_group()` on each supplied pubkey (or on the aggregate, which is sufficient against subgroup attacks and is one check rather than *n*), and reject `is_inf()` on the aggregate explicitly rather than relying on blst's internal error code. Give `aggSign` the same `Either` treatment `aggSig` already has, and add a `sanityCheckSignature` on both inputs. The cost of one `in_group()` per QC verification is small relative to a pairing.

#### H2 — One domain-separation tag is shared across three cryptographically distinct message types

> **Status (2026-09-02): FIXED, unconditionally — was feature 30 `BlsCryptoV2`, since deleted.** Three
> per-context DSTs are the ONLY DSTs used anywhere in production: `BlsUtils.BlsPopDomainSeparationTagV2`
> (`..._POP_`), `BlsUtils.BlsEndorseDomainSeparationTagV2` (`..._ENDORSE_`), and
> `BlsUtils.BlsHsVoteDomainSeparationTagV2` (`..._HSVOTE_`). This fix originally shipped behind an
> on-chain activation gate (feature 30 `BlsCryptoV2`, height-gated at the containing block for the two
> consensus-replayed sites, live-tip-read for the off-chain HotStuff vote/QC path). That gate was deleted
> entirely later on 2026-09-02, once it was confirmed that no chain in this repo's history — testnet
> included — had ever activated it, meaning there was no real (non-disposable) legacy-DST history
> anywhere that a gate needed to protect. The legacy shared tag (`BlsDomainSeparationTag`, `_NUL_`) was
> deleted from production code at the same time — it is not reachable at all any more, not even as a
> fallback. Every node signs and verifies PoP, block endorsements, and HotStuff votes/QCs under their own
> per-context v2 DST from genesis, on every network, with no activation step and no boundary window.
> Proved by: the 3x3 cross-DST matrix (`BlsUtilsTest` — `"3x3 basic-verify matrix..."` /
> `"3x3 aggregate-verify matrix..."`), the rewritten vector regression spec (`BlsVectorRegressionSpec`,
> pinning v2-only vectors; the legacy-only predecessor `BlsLegacyVectorRegressionSpec` was retired along
> with the legacy tag), the on-chain PoP gate and its light-node snapshot-path twin
> (`CommitToGenerationPopV2Spec` — kept, testing the now-unconditional v2 PoP layout despite its "V2"
> filename, which is now just a spec-file identifier, not a reference to a live gate), and the endorsement
> path tests (`BlockEndorsementDstSpec`). The activation-boundary, snapshot-path, rollback-determinism,
> and equivocation-proof-boundary specs that existed only to prove the gate itself
> (`BlsCryptoV2ActivationHelperSpec`, `BlsCryptoV2SnapshotPathPopSpec`, `BlsCryptoV2RollbackDeterminismSpec`,
> `BlsCryptoV2EquivocationProofBoundarySpec`, `BlsCryptoV2EndorsementSpec`) were deleted along with the
> gate — there is no boundary left to test.

> **CORRECTION (2026-09-03): the "ONLY DSTs used anywhere in production" and "not reachable at all any
> more, not even as a fallback" claims above are FALSE and have been superseded.** The premise that no
> chain in this repo's history had ever activated the legacy-tag feature turned out to be wrong for the
> real testnet-relaunch chain: it pre-activates feature 25 (`DeterministicFinality`) at genesis and
> carries real, legitimate BLS signatures produced under the legacy `_NUL_` tag, confirmed by a live
> chain replay reaching height 2639. `BlsUtils.BlsLegacyDomainSeparationTag` was reintroduced as a
> VERIFY-ONLY fallback for this reason — it was not left deleted. **New signing remains v2-only**: the
> three per-context v2 DSTs above are still the exclusive tags used to produce any new PoP, endorsement,
> or HotStuff vote/QC. Only verification is bimodal — it tries v2 first and falls back to the legacy tag
> solely to validate pre-existing, already-on-chain historical signatures (e.g. during a full resync
> from genesis). See `node/src/main/scala/com/decentralchain/crypto/bls/BlsUtils.scala`'s
> `BlsLegacyDomainSeparationTag` scaladoc for the full explanation.

**Files:** `BlsUtils.scala:11`; `CommitToGenerationTransaction.scala:50–52`; `BlockEndorsement.scala:31–32`; `HotStuffQuorum.scala:40–41`

`BlsDomainSeparationTag = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_NUL_"` is the single DST used by **every** `hash_to` and **every** `Pairing` context in the codebase. Three semantically different signatures are produced under it:

| Purpose | Message | Length |
|---|---|---|
| Proof of possession | `blsPk(48) ‖ generationPeriodStart(4)` | 52 |
| Block endorsement | `finalizedId(32) ‖ finalizedHeight(4) ‖ endorsedId(32)` | 68 |
| HotStuff vote | `view(4) ‖ phase(1) ‖ blockId(32) ‖ blockHeight(4) ‖ committeeEpoch(4)` | 45 |

The IETF BLS spec's `POP_` variant exists precisely so that a proof of possession lives in a *different* hash domain from ordinary signatures; using `_NUL_` for the PoP is a deliberate deviation, flagged only by the terse inline comment *"We have a non-standard PoP."*

**Current exploitability: none that I could construct.** The three encodings happen to be mutually unparseable — distinct fixed lengths (52 / 68 / 45), and `blockId` is 32 bytes throughout, so no message in one context is a valid message in another. Cross-context forgery is blocked *accidentally*, by encoding coincidence, not by design.

**Why it is still HIGH.** This is a latent trap with no guard rail. Any of the following silently creates a cross-context signature-transplant vulnerability, and nothing in the codebase or test suite would detect it:
- `Height` widening to 8 bytes (PoP → 56, endorsement → 76 — still distinct, but the reasoning must be redone);
- a variable-length or differently-sized `blockId` (already flagged as a caveat in `hotstuff-security-review.md:56`);
- adding a fourth signed message type, e.g. a HotStuff timeout/new-view certificate, which is standard HotStuff and plausibly forthcoming — a 52- or 68-byte timeout message would be immediately transplantable;
- reusing the endorsement or vote signer for any new purpose.

Concretely, the highest-value target is the PoP: a PoP is a signature an attacker can *solicit* (it is published on-chain by every registering generator). If any future message type ever collides with the 52-byte PoP encoding, every registered generator's on-chain PoP becomes a free forged signature in that context.

**Recommendation.** Introduce per-context DSTs — e.g. `..._POP_`, `..._ENDORSE_`, `..._HSVOTE_` — or, at minimum, a one-byte context prefix on every signed message, plus a length prefix on `blockId`. Add a unit test asserting that a signature produced in each context fails verification in the other two. This is cheap now and expensive after mainnet enablement, since it is a consensus-breaking change.

---

### MEDIUM

#### M1 — Test file's adversarial cases are stale and no longer test what their comments claim

**File:** `node/tests/src/test/scala/com/decentralchain/crypto/bls/BlsUtilsTest.scala:161–218`

The Task-20 block is written in "RED" TDD style with comments asserting the fix is *absent*:

- `:184` — *"should be rejected by the registration-time `.validated` check (currently does not exist — RED)"*; `:186` — *"This line does not compile against current BlsPublicKey.scala."* It does compile: `BlsPublicKey.validated` exists at `BlsPublicKey.scala:22`.
- `:207` — *"currently ignores the return code entirely — RED"*; `:208–214` — *"calls `ctx.aggregate(...)` and discards its `BLST_ERROR` return value outright ... It also returns a bare `Boolean`."* Neither is true: `BlsUtils.scala:67–68` binds and checks `aggResult`, and `verifyBasic` returns `Either[String, Unit]`.

The assertions themselves are correct and now pass, so this is not a functional gap — but leaving inverted commentary in a security test file is a genuine hazard. A future reader (or auditor) reasonably concludes from these comments that the infinity and error-code gaps are still open, or conversely edits the tests to "match" the comments and removes live coverage. **Recommendation:** rewrite `:161–218` as GREEN regression tests describing the invariant being protected, and drop the "does not compile" claims.

#### M2 — Proof of possession binds neither chain id nor the committing account

> **Status (2026-09-02): FIXED, unconditionally — was feature 30 `BlsCryptoV2`, since deleted.**
> `CommitToGenerationTransaction.popMessage` has one layout only —
> `chainId(1) ‖ senderPk(32) ‖ endorserPk(48) ‖ generationPeriodStart(4)`, built in exactly this one
> function — replacing the legacy `endorserPk ‖ generationPeriodStart` bytes entirely; the message is
> signed/verified under the `_POP_` DST from H2 (`CommitToGenerationTransaction.popDst`, now a constant,
> no `cryptoV2` parameter). A PoP is therefore never replayable across chains or transplantable onto a
> different sender's registration, on any network, from genesis. This fix originally shipped
> height-gated at the two on-chain verification sites (`CommitToGenerationTransactionDiff` for full nodes,
> `BlockDiffer.validateCommitmentsOnSnapshotPath` for light nodes); the gate was deleted the same day once
> it was confirmed no chain in this repo's history had ever activated it, so both sites now apply the v2
> layout unconditionally with no legacy branch left to keep deterministic across rollback. Proved by:
> `CommitToGenerationPopV2Spec` (full-node PoP: chain-id and sender binding) and its light-node
> counterpart folded into the same file's snapshot-path coverage (C1's original bypass surface). The
> activation-boundary and rollback-determinism specs that existed only to prove the gate itself
> (`BlsCryptoV2SnapshotPathPopSpec` as a separate file, `BlsCryptoV2RollbackDeterminismSpec`) were deleted
> along with the gate.

**Files:** `CommitToGenerationTransaction.scala:50–52`; `CommitToGenerationTransactionDiff.scala:21–23`

The PoP message is `endorserPublicKey ‖ generationPeriodStart` only. `CommitToGenerationTransaction` carries a `chainId` (`:23`) and a `sender` (`:16`), and neither is inside the BLS-signed bytes. Two consequences:

1. **Cross-chain PoP replay.** A PoP published on testnet for period start *H* is a valid PoP on mainnet for period start *H*. Chain separation for the *transaction* rests on the outer Curve25519 proof and `chainId` validation, so this is not immediately exploitable — but the BLS layer's own binding is weaker than the transaction layer's, and any future context that consumes a bare PoP (a cross-chain bridge, a light-client committee proof, a registration API) inherits the weakness.
2. **PoP is not bound to the registering account.** The same BLS key + period-start PoP is valid for *any* sender. The duplicate-key check (`:31`) prevents two simultaneous registrations of one key within a period, but it does not prevent a different account from front-running a registration by lifting a PoP observed in the mempool and submitting it under its own sender before the original lands. Given the deposit requirement this is a griefing/denial vector rather than a key-theft one, but it is a real ordering hazard.

**Recommendation.** Extend the PoP message to `chainId ‖ senderPublicKey ‖ endorserPublicKey ‖ generationPeriodStart` (consensus-breaking; do it before mainnet enablement). Pair with a per-context DST per H2.

#### M3 — Weak/degenerate secret keys are silently accepted by `mkBlsSecretKey`

**File:** `BlsUtils.scala:17–21`; evidenced by `BlsUtilsTest.scala:54–83`

`mkBlsSecretKey` calls `sk.keygen_v5(arr, BlsKeyGenSalt)` and returns unconditionally. The test suite itself documents that a 31-byte input yields a **zero secret key** whose public key is the point at infinity (`:54–55`, `:71–78`: `zeroSk.to_bendian() shouldBe Array.fill[Byte](32)(0)`, `zeroPk.is_inf() shouldBe true`). There is no check that the derived scalar is non-zero, and no check that the input seed meets the IETF minimum of 32 bytes of entropy.

In production the seed is a node's 32-byte Curve25519 private key (`BlsKeyPair.scala:17,21`), so the degenerate case is not reachable *today*. But `mkBlsSecretKey` is a public method on a public object taking an arbitrary `Array[Byte]`, and `BlsKeyPair.publicKey` uses `BlsPublicKey.unsafe` (`:22`), which bypasses even the length sanity check. A node configured from a short/low-entropy seed would silently obtain the infinity key and sign nothing verifiable — failing open into a silent liveness loss rather than a startup error.

**Recommendation.** Reject seeds shorter than 32 bytes and assert the derived scalar is non-zero (equivalently, `!new blst.P1(sk).is_inf()`) inside `mkBlsSecretKey`, returning `Either` or throwing at construction. This is a local, non-consensus-breaking change.

#### M4 — `verifyAgg` is FastAggregateVerify and therefore *requires* the PoP invariant it cannot itself check

**File:** `BlsUtils.scala:57–63`, referencing `draft-irtf-cfrg-bls-signature-05 §FastAggregateVerify`

The scheme is correctly identified as FastAggregateVerify (aggregate the public keys, one pairing). That construction is only secure under the PoP scheme — the *entire* rogue-key defense is displaced onto registration-time PoP. This is a valid design, but it makes the PoP check a single point of failure with no cryptographic backstop inside the verification routine itself, which is precisely what C1 exploits and what H1 leaves undefended.

Worth noting explicitly for the record: **duplicate public keys in `blsPks` are accepted and meaningfully change the result** — `BlsUtilsTest.scala:31–36` asserts this as intended behavior (`Seq(pk1, pk2, pk1)` verifies a signature aggregated as `sig1+sig2+sig1`). `HotStuffQuorum.verifyQC` is safe here because it derives keys from distinct committee indexes, and `formQC` de-duplicates per voter (`:96`). But `BlsUtils.verifyAgg` itself imposes no distinctness requirement, so any *future* caller that passes an attacker-influenced multiset inherits a signature-counting bug. **Recommendation:** either de-duplicate inside `verifyAgg` or document the distinctness requirement as a hard precondition with a caller-side assertion.

---

### LOW

#### L1 — `aggSign` is the only non-fail-closed primitive in the file
`BlsUtils.scala:37–38` returns `Array[Byte]` and throws on malformed input (no length check, no `Either`). Its sibling `aggSig` was hardened; `aggSign` was not, and it remains reachable via `BlsSignature.append` (`BlsSignature.scala:22`). Convert to `Either` and add `sanityCheckSignature` on both arguments. (Also folded into H1.)

#### L2 — `BlsPublicKey.unsafe` / `BlsSignature.unsafe` asymmetry
`BlsSignature.unsafe` routes through `apply` and throws on a bad length (`BlsSignature.scala:26–29`), which is defensible. `BlsPublicKey.unsafe` (`BlsPublicKey.scala:25`) is a bare cast with **no** check at all. Both are `private[bls]`, so the blast radius is the package, but the inconsistency is a footgun. Make `BlsPublicKey.unsafe` symmetric with its signature counterpart.

#### L3 — Error strings leak library internals into validation messages
`verify` (`:68`, `:75`) and the aggregation helpers interpolate `BLST_ERROR` values and `e.getMessage` into `Left` strings that surface in block-validation errors and, via `GenericError`, in API responses. Low impact (no secret material is involved) but it is unnecessary implementation disclosure and makes error strings unstable across blst upgrades. Prefer fixed error codes.

#### L4 — `new blst.Pairing(true, DST)` allocated per verification
`verify` (`:66`) builds a fresh pairing context for every single signature check, including per-vote checks in `HotStuffQuorum.verifyVote` inside `formQC`'s `filterNot` loop (`:97`). This is correctness-neutral but is an obvious DoS amplification surface: a peer flooding invalid votes forces one pairing setup + one `finalverify` each. Consider batch verification, or at minimum a cheap pre-filter (committee membership is already checked first, which helps) and rate limiting on the vote ingress path.

---

### INFO

- **I1 — `hash_to` augmentation argument is empty everywhere.** `signBasic` (`:27`) and `verify` (`:67`) pass `Array.emptyByteArray` as the augmentation. This is correct and consistent for the basic/PoP scheme (the `aug` parameter belongs to the message-augmentation variant), and both sides match, so there is no signing/verification asymmetry. Noted only so a future reader does not "fix" it asymmetrically.
- **I2 — Curve/pairing library usage is otherwise idiomatic.** Minimal-pubkey-size configuration is used consistently (PK in G1 = 48 bytes compressed, sig in G2 = 96), `Pairing(true, DST)` correctly requests hash-to-curve rather than encode-to-curve, and `commit()` precedes `finalverify()` as blst requires. No misuse found.
- **I3 — `docs/hotstuff-security-review.md:16` is accurate as written but materially incomplete.** Its rogue-key claim is verified true for full nodes (V1) and false for light nodes (C1). The doc makes no light-node qualification. It should be amended rather than merely cross-referenced.
- **I4 — No BLS aggregation or verification appears on any hot path gated by attacker-controlled unbounded input size**, other than L4's per-vote cost. `formQC`/`verifyQC` operate over committee-sized sets bounded by the committed-generator count.

---

## 3. What I could not fully verify

Stated honestly rather than asserted as sound:

1. **Constant-time behavior / timing side channels.** I read no timing-variable branch in the *Scala* layer — `verify` branches only on the final boolean, and no secret-dependent array indexing occurs in `BlsUtils`. But the actual scalar multiplication, `keygen_v5` HKDF, and pairing all execute inside blst's native code. Confirming constant-time execution requires benchmarking with statistical timing analysis (e.g. `dudect`) against the linked native library on the target platform, plus review of which blst build flags/assembly backend is compiled into `io.decentralchain % blst % 0.3.16.0`. **I did not do this and cannot assert it is fine.** blst upstream is generally regarded as constant-time for secret-dependent operations, but that is reputation, not verification of *this* build. Note also that the only secret-dependent operation in the node is signing, which is done by the node on its own key — remote timing extraction would require an attacker who can both trigger signatures and measure them precisely, which is a narrower threat than key-agreement timing attacks.
2. **The provenance and integrity of the `io.decentralchain % blst % 0.3.16.0` artifact.** This is a re-published (renamed group id) build of supranational blst, not the upstream `supranational:blst` coordinate, while the Scala code imports the upstream `supranational.blst` package. I did not verify that this artifact is byte-equivalent to an official upstream 0.3.16 build, nor check its native `.so`/`.dylib` for modification. For an external audit this should be reproduced from upstream source and diffed — a compromised BLS library defeats every finding above.
3. **Whether blst's `Pairing.aggregate` internally performs a G1 subgroup check on a caller-supplied `P1_Affine`.** H1 is written on the conservative assumption that it does **not** (matching blst's documented separation of `in_group()` from decompression, and matching the fact that the codebase felt the need to add an explicit `in_group()` in `validatePublicKey`). Settling this definitively requires reading blst 0.3.16's native `aggregate` implementation, which I did not do. If it *does* subgroup-check, H1 drops to LOW; C1 is unaffected either way, since C1 is about a rogue key that is a perfectly valid group element.
4. **Live/dynamic confirmation.** No tests were executed and no node was run. All findings are from source reading. In particular C1 is traced statically through `BlockDiffer`'s branch structure; it should be confirmed empirically by running a light node against a peer serving a snapshot containing a `CommitToGenerationTransaction` with an invalid PoP and observing that it is accepted.
5. **Non-BLS consensus safety.** Out of scope here; `docs/hotstuff-security-review.md` covers the quorum/safety/lock rules, and this pass did not re-derive them.

---

## 4. Recommended order of remediation

1. **C1** — close the light-node validation bypass (or disable light mode until closed). Nothing else matters while a peer can seat arbitrary committee keys.
2. **H2 / M2** — per-context DSTs and a chain-id/sender-bound PoP. **FIXED 2026-09-02, and unconditional** — shipped initially behind feature 30 `BlsCryptoV2`, which was deleted the same day once no real (non-disposable) legacy-DST history was found anywhere; every network runs this crypto from genesis now, so the original "must land before mainnet enablement" framing no longer applies (there is nothing left to enable). See the STATUS notes on H2 and M2 above.
3. **H1 / L1** — defense-in-depth subgroup + infinity checks in `verifyAgg`, `Either`-ify `aggSign`. Non-breaking, cheap.
4. **M3 / L2** — reject degenerate seeds; make `unsafe` constructors symmetric. Local.
5. **M1** — de-stale the security test file so the next reviewer is not misled.
6. **L3 / L4** — error hygiene and vote-ingress DoS hardening.

**Gate recommendation: unchanged and reinforced.** An external third-party cryptographic audit remains required before mainnet enablement, and this review does not discharge it. C1 in particular is the kind of finding that indicates the *validation-path coverage* — not the primitives — is where the residual risk concentrates.
