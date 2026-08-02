# Feature 29 (working name `ConsensusFixes2026`) — Specification

> Consensus artifact. Reviewed BEFORE any code. Do not implement against this document
> until it has been signed off — validation-rule changes are consensus-critical: a node
> running old rules would build/accept a different chain than a node running new rules
> once real transactions start exercising the gap, which is a hard fork if not gated.

> **UPDATE (post-diagnosis): Items 1 and 2 (SC-575, SC-580) are CONFIRMED ALREADY FIXED in
> current production code — no feature scaffold or gated diff is needed for either.**
> Real Docker integration test evidence (`GrpcIssueReissueBurnAssetSuite`, both previously-
> `ignore`d tests now green):
> - **SC-575**: the invocation genuinely rejects with `State check failed. Reason: Asset
>   ... is already issued` when two Issue actions in one invocation produce the same asset
>   ID. The test itself was underfunded (default `invokeFee` insufficient for a 2-Issue
>   invocation, masking this behind an unrelated fee error) and its expected message was a
>   stale placeholder that never matched real production copy — both fixed in
>   `node-it/src/test/scala/com/decentralchain/it/asset/GrpcIssueReissueBurnAssetSuite.scala`.
> - **SC-580**: the invocation genuinely rejects with `INVALID_ARGUMENT: Asset is not
>   reissuable` when a second same-invocation reissue follows an earlier action that set
>   `reissuable = false`. The test's own body asserted success (`totalVolume` increase)
>   instead of checking for the error its own name describes — fixed to `assertGrpcError`,
>   matching the pattern already used by sibling tests in the same file.
>
> **Item 3 (SC-695) remains genuinely open** — see that section below; it needs its own
> separate design pass (an unspecified tx-version/script-version matrix plus an entirely
> unimplemented `extraFeePerStep` fee mechanism), independent of this outcome.
>
> Everything below this point is retained as the original spec (written before this
> diagnosis) for historical/audit context — it no longer describes work to be scheduled for
> Items 1–2.

## Why a new feature, not a fix to existing code

RideV6 (`BlockchainFeatures.RideV6`, id 17) is **already activated** on this chain (mainnet
pre-activates ids 1–17 at height 0, per `node/src/main/scala/com/decentralchain/settings/*`
genesis settings). Any validation-rule tightening below is a **behavior change relative to
already-active code** — if shipped unconditionally, a node running the new binary would
reject/accept transactions differently than one running the current binary, at the same
height, with no activation coordination. That is a silent fork. Each item below must
therefore be gated behind a **new** `BlockchainFeature`, dormant until testnet governance
activates it (out of scope for this document — see the plan's Task F3 Step 4).

**Feature id: 29.** Verified free at time of writing —
`grep -nE "BlockchainFeature\([0-9]+" node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala`
shows ids 1–28 assigned (18 = `ConsensusImprovements`, 25 = `DeterministicFinality`, 28 =
`ModernGroth16Verifier`, highest). **Re-run that grep at implementation time** — another
feature may have claimed 29 in the interim; a ticket number never overrides a real ID
collision check.

## Item 1 — SC-575: duplicate-nonce asset issue within one invocation must error

**Current behavior (verified, `node-it/src/test/scala/com/decentralchain/it/asset/GrpcIssueReissueBurnAssetSuite.scala:113-120`,
test currently `ignore`d):**
```scala
"Issue two identical assets with the same nonce (one invocation) should produce an error" ignore {
  /* SC-575 */
  val acc = createDapp(script(simpleNonreissuableAsset))
  assertGrpcError(
    invokeScript(acc, "issue2Assets"),
    "State check failed. Reason: Reason should be here"
  )
}
```
A single dApp invocation that calls `Issue` twice for what resolves to the **same asset ID**
(same issuer + same nonce, per the deterministic asset-ID derivation) currently succeeds
silently instead of failing state validation. The neighboring, currently-passing case
(`"...same nonce (different invocations) should not produce an error"`, same file,
lines 124-131) proves the DISTINCTION is invocation-scoped: two separate invocations
producing the same nonce is fine (each is its own state-check pass); two `Issue` actions
for the same nonce **inside one invocation's resulting diff** is the case that must reject.

**Rule (proposed, gated):** when constructing the composite state diff for a single
`InvokeScriptTransaction` (or `InvokeExpressionTransaction`), if two or more `Issue`
actions within that SAME invocation's action list resolve to the same computed asset ID,
reject with a state-check error. Exact error text: match the ignored test's expectation,
`"State check failed. Reason: Reason should be here"` is almost certainly a placeholder in
the test itself (the literal string reads like a TODO, not real prod copy) — **the real
error message needs to be decided as part of implementation**, not invented here; do not
ship a literal test-placeholder string as production output.

**Affected validation path:** wherever per-invocation `Issue` actions are folded into a
diff — `node/src/main/scala/com/decentralchain/state/diffs/invoke/InvokeDiffsCommon.scala`
is the file already handling issue/reissue diff logic (confirmed present via
`grep -rln isReissuable node/src/main/scala/com/decentralchain/state/diffs`); the exact
fold/accumulate site for `Issue` actions needs to be located and read in full before writing
code — this document does not claim to have found the precise line.

**Activation semantics:** pre-activation, duplicate-nonce-same-invocation issues continue to
succeed exactly as today (a paired "byte-identical to today" test is required per the plan's
Task F3 Step 3). Post-activation, they reject with the decided error.

**Replay implications:** any historical block containing this pattern (pre-activation, by
construction, since the pattern currently succeeds) must continue to validate/replay
successfully — the gate must never retroactively invalidate a block mined before the
feature's activation height. Standard `blockchain.isFeatureActivated(...)` height-gating
already provides this if wired correctly; call out explicitly in code review that the check
reads the height/activation state of the invocation's OWN height, not "is the feature active
now."

## Item 2 — SC-580: reissue-after-flip-to-non-reissuable within one invocation must error

**Current behavior (verified, same file, lines 163-171, test currently `ignore`d):**
```scala
"Reissuing after setting isReissuiable to falser inside one invocation should produce an error" ignore /* SC-580 */ {
  val acc     = createDapp(script(simpleReissuableAsset))
  val txIssue = issue(acc, method, simpleReissuableAsset, invocationCost(1))
  val assetId = validateIssuedAssets(acc, txIssue, simpleReissuableAsset, method = method)

  invokeScript(acc, "reissueAndReissue", assetId = assetId, count = 1000)

  sender.assetInfo(assetId).totalVolume should be(simpleReissuableAsset.quantity + 1000)
}
```
The invoked function `"reissueAndReissue"` (defined in the dApp script fixture this test
uses — read `simpleReissuableAsset`'s script source before implementing, not summarized
here) presumably calls `Reissue(assetId, amount, reissuable = false)` then a second
`Reissue` on the same asset within the SAME invocation. The currently-passing neighbor test
(`"Reissuing NFT asset should produce an error"`, lines 156-162) proves the general
"reissue a non-reissuable asset" check already exists and works CROSS-invocation/cross-tx —
this item is specifically about the flag flip and the second reissue being **in the same
invocation's action sequence**, where today's validation apparently checks the asset's
on-chain `isReissuable` state at invocation START, not the state as of each individual
action within the invocation's own action list.

**Rule (proposed, gated):** when folding multiple `Reissue` actions for the same asset
within one invocation's action list, each subsequent action must respect the
`reissuable` flag as most recently set by an EARLIER action in that same list, not only
the pre-invocation on-chain state. If an earlier action set `reissuable = false`, a later
action attempting to reissue the same asset in the same invocation must reject.

**Affected validation path:** same file as Item 1
(`InvokeDiffsCommon.scala` region handling reissue-action folding) — needs its own read
before implementation; likely the same or an adjacent fold step as Item 1's Issue-dedup
check, since both are "does this invocation's OWN action sequence violate a same-asset
invariant" checks. Worth implementing as siblings/reusing the same intra-invocation
per-asset accumulator structure if one doesn't already exist.

**Activation/replay:** same shape as Item 1 — pre-activation byte-identical, post-activation
per rule, height-gated so historical blocks (all pre-activation, by construction of "the bug
exists today") replay unaffected.

## Item 3 — SC-695: InvokeScriptTransaction version gating + extraFeePerStep — NOT FULLY SPECIFIED, NEEDS SEPARATE DESIGN

**This item is materially different in scope from Items 1/2 and should NOT be scaffolded
under the same feature id without further design work.** Evidence
(`node-it/src/test/scala/com/decentralchain/it/sync/smartcontract/InvokeScriptTransactionRideV5Suite.scala`,
5 `ignore`d tests, lines 98-160+) shows TWO distinct, currently-unimplemented mechanisms:

1. **Transaction-version-vs-dApp-script-version gating.** A RideV5 dApp should reject
   `InvokeScriptTransaction` versions V1/V2 (`"Can't invoke Ride V5 DApp via InvokeScriptTx
   V1"` / `"...V2"`, expecting HTTP 400 "State check failed") and accept V3
   (`"Can invoke Ride V5 DApp via InvokeScriptTx V3"`). **Verified: zero existing
   version-gating logic in `node/src/main/scala/com/decentralchain/transaction/smart/`**
   (grep for `TxVersion.V1/V2/V3` in that path returned nothing). This is a real, boundable
   rule — "reject invoke-tx version < N against a dApp whose script version requires ≥ N" —
   but the exact version/script-version compatibility MATRIX (which tx versions are valid
   against which RIDE script versions) is not derivable from the 3 tests read so far and
   needs the full test file plus whatever upstream RIDE version documentation exists before
   it can be specified precisely. Do not guess the matrix from 3 examples.

2. **`extraFeePerStep` — a fee mechanism that does not exist in this codebase at all.**
   (`"Can't invoke Ride V3 DApp via InvokeScriptTx V3 if extraFeePerStep is specified"`,
   with the test's own comment `"NOTE: extraFeePerStep calculation to be added in future"`.)
   **Verified: zero references to `extraFeePerStep` anywhere in `node/src/main`** — this is
   not a validation-rule change, it is an **unimplemented transaction field and fee
   calculation** that the ignored tests merely assert will one day be rejected under certain
   conditions. Speccing this properly requires: what field carries this value on the wire
   (a new `InvokeScriptTransaction` version field? a script directive?), how it composes
   with existing fee calculation (`FeeValidation`, referenced elsewhere in this codebase),
   and what "specified" vs "unspecified" means at the protocol level (optional field
   default? sentinel value?). None of that is answerable from the ignored test alone.

**Recommendation:** split SC-695 out of this feature id. Items 1/2 (SC-575/SC-580) are
well-bounded validation-rule tightenings appropriate for a single dormant feature flag.
SC-695 is at minimum two separate concerns (version-gating rule; a new fee mechanism) each
needing its own design pass — potentially its own feature id(s) once specified. Scaffolding
a `BlockchainFeature` entry for SC-695 today, before either sub-concern is actually
specified, would produce a flag gating nothing well-defined — exactly the kind of
premature/vibes-based consensus scaffolding this plan explicitly warns against. **Do not
implement Item 3 from this document.**

## Summary for Task F3 execution

- Scaffold `BlockchainFeature(29, ...)` covering **Items 1 and 2 only** (SC-575, SC-580).
  Suggested name: keep it accurate to scope — e.g. `IntraInvocationAssetActionValidation`
  rather than the plan's placeholder `ConsensusFixes2026`, since the id must describe what
  it actually gates, not a ticket batch that turned out to only be 2/3 of the original list.
- SC-695 is explicitly OUT of this feature's scope pending its own, separate design spec.
- Each of Items 1/2 needs a real read of `InvokeDiffsCommon.scala`'s current issue/reissue
  action-folding logic (this document points at the file, not the exact lines) and the
  `simpleReissuableAsset`/`simpleNonreissuableAsset` script fixtures before any code is
  written — that reading is implementation work, correctly deferred past this spec.
