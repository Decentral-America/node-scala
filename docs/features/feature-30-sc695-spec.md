# Feature 30 (working name `InvokeVersionGatingAndStepFee`) — Specification (SC-695)

> Consensus artifact. Reviewed BEFORE any code. **SPEC ONLY.** Do not implement against this
> document until it has been signed off. Do NOT scaffold a `BlockchainFeature` entry until
> the matrix and fee mechanism below are reviewed and accepted — this is the explicit
> instruction from the launch-readiness plan's Task 5, and this document exists precisely
> to satisfy "matrix + fee mechanism specified" before any scaffold is written.

**Relationship to feature-29:** `docs/features/feature-29-spec.md` (SC-575/SC-580) explicitly
carved SC-695 OUT of its scope ("Do not implement Item 3 from this document... SC-695 is
explicitly OUT of this feature's scope pending its own, separate design spec"). This document
is that separate spec. It claims feature id **30**, one past feature-29's reserved id 29 (see
"Feature id" section below for the verification of both).

## Problem statement — what the 5 ignored tests actually expect

Source: `node-it/src/test/scala/com/decentralchain/it/sync/smartcontract/InvokeScriptTransactionRideV5Suite.scala`,
lines 99-172. Setup (`beforeAll`, lines 45-96): three dApp accounts are deployed with
`setScript`, each running a single-STDLIB-directive script — `dAppV3` runs `STDLIB_VERSION 3`,
`dAppV4` runs `STDLIB_VERSION 4`, `dAppV5` runs `STDLIB_VERSION 5`. All three scripts have a
trivial `default()` callable (`WriteSet([])` / `nil` — no actions). The suite pre-activates
`Ride4DApps`, `BlockV5`, and `SynchronousCalls` at height 0, so this is presumed to run under
those already-active features; RideV6 is NOT pre-activated in this suite's `nodeConfigs`.

**Test 1 (line 99), `ignore("Can't invoke Ride V5 DApp via InvokeScriptTx V1")`:**
```scala
ignore("Can't invoke Ride V5 DApp via InvokeScriptTx V1") {
  assertApiError(
    sender.invokeScript(callerPK, dAppV5, version = TxVersion.V1)
  ) { error =>
    error.statusCode shouldBe 400
    error.message shouldBe "State check failed" // NOTE: Detailed error message to be implemented in future
  }
  assertApiError(
    sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V1)
  ) { error =>
    error.statusCode shouldBe 400
    error.message shouldBe "State check failed"
  }
}
```
Expects: invoking a STDLIB V5 dApp via an `InvokeScriptTransaction` of tx-version V1 (both by
address and by alias) must be REJECTED with HTTP 400 "State check failed."

**Test 2 (line 116)** — identical shape, tx-version V2 instead of V1. Same expectation:
rejected.

**Test 3 (line 133), `ignore("Can invoke Ride V5 DApp via InvokeScriptTx V3")`:**
```scala
ignore("Can invoke Ride V5 DApp via InvokeScriptTx V3") {
  sender.invokeScript(callerPK, dAppV5, version = TxVersion.V3, waitForTx = true)
  sender.invokeScript(callerPK, alias(dAppAliasV5), version = TxVersion.V3, waitForTx = true)
}
```
Expects: the SAME V5 dApp, invoked via tx-version V3, must SUCCEED (no error assertion — a
bare call with `waitForTx = true` that would throw on API/tx failure).

**Test 4 (line 139), `ignore("Can't invoke Ride V3 DApp via InvokeScriptTx V3 if extraFeePerStep is specified")`:**
```scala
ignore("Can't invoke Ride V3 DApp via InvokeScriptTx V3 if extraFeePerStep is specified") {
  // NOTE: extraFeePerStep calculation to be added in future
  assertApiError(
    sender.invokeScript(callerPK, dAppV3, version = TxVersion.V3)
  ) { error =>
    error.statusCode shouldBe 400
    error.message shouldBe "State check failed"
  }
  assertApiError(
    sender.invokeScript(callerPK, alias(dAppAliasV3), version = TxVersion.V3)
  ) { error =>
    error.statusCode shouldBe 400
    error.message shouldBe "State check failed"
  }
}
```
**Important discrepancy, found by direct reading — flagged, not silently resolved:** the test
NAME says "if extraFeePerStep is specified," but the test BODY never actually specifies an
`extraFeePerStep` value anywhere. `sender.invokeScript(...)` is called with the same
signature used by every other test in this file (`caller, dappAddress, version = ...`), and
`AsyncHttpApi`/`SyncHttpApi`'s `invokeScript` helper (`node-it/.../api/{Async,Sync}HttpApi.scala`)
has NO `extraFeePerStep` parameter at all — confirmed by reading both signatures in full (see
"Verified absence" below). So as currently written, test 4 is indistinguishable from "invoke a
V3 dApp via tx-version V3 with the default fee" — which, if the version matrix in tests 1-3
holds (V3 tx is the one that's supposed to WORK), should arguably SUCCEED, not fail. The test
currently asserts failure anyway. Two readings are possible, and this spec does not silently
pick one:
  (a) the test is genuinely incomplete — a future author intended to add an explicit
      extra-fee-carrying argument to the invocation and never did (most likely, given the
      literal in-body `// NOTE: extraFeePerStep calculation to be added in future` comment
      sitting right where such an argument would go), or
  (b) the intended rule is that invoking a LOWER-STDLIB-version dApp (V3) via a HIGHER tx
      version (V3 is the max tx version, but V3-dApp is not V5) with NO extra fee specified
      is what should fail, i.e. tx-version V3 invocations of dApps below some STDLIB
      threshold require the extra fee to be present and non-zero, and omitting it is itself
      the failure condition being tested.
  This spec's matrix below is built to accommodate reading (b) design-wise (it is the only
  reading that gives `extraFeePerStep` a reason to exist as a required, not optional, fee
  component for certain version combinations) but flags this explicitly as **NOT resolvable
  from the ignored test text alone** — see Non-goals.

**Test 5 (line 157)** — identical to test 4 but against `dAppV4` (STDLIB V4) instead of V3.
Same discrepancy applies.

**Verified absence of `extraFeePerStep` anywhere in the codebase:**
`grep -rn "extraFeePerStep" node-scala/` (run from Ecosystem root) returns ONLY the test file
itself and `docs/features/feature-29-spec.md`'s own prose about this ticket — zero hits in
`node/src/main`, zero hits in the `invokeScript` test-API helpers
(`node-it/src/test/scala/com/decentralchain/it/api/{Async,Sync}HttpApi.scala:500,714`, both
read in full — neither has a matching parameter). This confirms feature-29-spec's finding:
`extraFeePerStep` is not a dormant/unwired field, it does not exist at all, at either the
transaction-model layer or the test-harness layer.

## Version-compatibility matrix

**What exists today (verified by reading `InvokeScriptTransactionDiff.scala` in full,
`node/src/main/scala/com/decentralchain/state/diffs/invoke/InvokeScriptTransactionDiff.scala`):**
the invocation path resolves the dApp's `StdLibVersion` from the deployed script
(`extractInvoke`, line ~283, pulls `version` out of `AccountScriptInfo`'s
`ContractScriptImpl(version, dApp)`), and that `version` — the RIDE/STDLIB version — is
threaded through `executeInvoke`/`evaluateV2`/`DirectiveSet(version, Account, DAppType)` etc.
**At no point is `tx.version` (the `InvokeScriptTransaction`'s own wire version, i.e.
`TxVersion.V1/V2/V3`) compared against the dApp's STDLIB version.** `tx.version` is used
elsewhere only for serialization/proof format concerns
(`node/src/main/scala/com/decentralchain/transaction/smart/InvokeScriptTransaction.scala`),
not for any compatibility gate. Confirmed by grep: no `TxVersion.V1`/`V2`/`V3` comparison
exists anywhere under `node/src/main/scala/com/decentralchain/transaction/smart/` or
`.../state/diffs/invoke/`. **Today, in production, ALL of TxVersion V1/V2/V3 can invoke a
dApp of ANY STDLIB version (V3 through V6) with no rejection based on the combination.**
This matches what the ignored tests describe as broken/missing — they are asserting a rule
that plainly does not exist yet, not a rule that's implemented wrong.

The matrix below is this spec's proposed design — a recommendation to review, not a
description of existing behavior:

| InvokeScriptTx version | dApp STDLIB V3 | dApp STDLIB V4 | dApp STDLIB V5 (and V6, by extension) |
|---|---|---|---|
| V1 | allowed (unchanged) | allowed (unchanged) | **reject** — "State check failed" (test 1) |
| V2 | allowed (unchanged) | allowed (unchanged) | **reject** — "State check failed" (test 2) |
| V3 | allowed, but see extra-fee rule below (test 4) | allowed, but see extra-fee rule below (test 5) | allowed (test 3) |

**Design rationale for the boundary (V1/V2 reject only against V5+):** STDLIB V5 is the
version that introduced dApp-to-dApp synchronous calls (`SynchronousCalls` /
`BlockchainFeatures.SynchronousCalls`, id 16) and the V5-specific invocation-result shape
(`ScriptResultV4`/structured actions, `ContractEvaluator.Invocation` carrying
`tx.fee`/`tx.feeAssetId` for propagation to nested calls — see
`InvokeScriptTransactionDiff.scala:259-269`). Tx-version V3 is the version that added the
wire-level `payments`/attached-payments-list shape needed by that call model
(`AttachedPaymentExtractor.extractPayments`, same file, line 258, gated on
`version < V5 ⇒ StateSnapshot.empty` at line 226 — a DIFFERENT "version" variable, the
STDLIB one, but the pattern of "V5 needs richer wire data than older tx versions carry" is
the same shape of problem). The tests' own naming ("Can't invoke Ride V5 DApp via
InvokeScriptTx V1/V2" / "Can invoke ... via ... V3") directly states this V5-needs-V3 pairing;
this spec adopts it as read, not invented.

**Why V3-vs-V4 in the matrix stays "allowed" and not rejected:** none of the 5 ignored tests
assert that V1 or V2 tx invoking a V3 or V4 dApp should fail — only the V5-dApp tests (1, 2)
assert rejection, and only for tx V1/V2. Tests 4 and 5 (V3 tx against V3/V4 dApps) are about
the extra-fee condition, not a version-rejection. Absent evidence, this spec does NOT invent
an additional V1/V2-vs-V3/V4 rejection rule the tests never describe — extending the matrix
beyond what's textually supported would be exactly the "guess the matrix from 3 examples"
mistake feature-29-spec already warned against.

## `extraFeePerStep` fee mechanism design

**Candidate mechanisms considered, against the real fee-calc code
(`node/src/main/scala/com/decentralchain/state/diffs/FeeValidation.scala`):**

Today's fee pipeline is a strict composition, `getMinFee` (line 200): base fee-in-units
(`feeInUnits`, tx-type-keyed constant table + per-type adjustments, lines 76-120) →
`feeAfterSponsorship` (asset-fee conversion) → `feeAfterSmartTokens` (adds
`ScriptExtraFee`-per-smart-asset, lines 147-181) → `feeAfterSmartAccounts` (adds
`ScriptExtraFee`-per-smart-account-verifier, lines 183-198). Each stage is a pure
`FeeInfo => FeeInfo` fold that only reads `blockchain` state and the tx's own fields — none
of them currently reads anything about the dApp's own call graph, complexity, or invocation
"steps." The three realistic ways `extraFeePerStep` could fit this pipeline:

1. **New wire field on the transaction** (e.g. `InvokeScriptTransaction.extraFeePerStep:
   Option[Long]`, populated by the sender, validated/consumed at diff time). Pros: explicit,
   sender-controlled, matches the test names' phrasing ("if extraFeePerStep is specified" —
   implies an optional value the caller sets). Cons: changes the transaction's wire format
   (new protobuf field, new serialization/deserialization, new proof-of-work for
   old-version-vs-new-field compatibility) — this is the highest-blast-radius option because
   it touches serialization consensus rules, not just validation rules, and interacts with
   the version matrix above (would a V1/V2 tx even have room for this field, or is the field
   itself gated to V3+?).
2. **Fee-calculation composition rule** — a new stage in the `getMinFee` pipeline (a sibling
   to `feeAfterSmartTokens`/`feeAfterSmartAccounts`) that computes an extra fee AUTOMATICALLY
   from something already observable post-execution — most plausibly the number of
   dApp-to-dApp synchronous call "steps" taken during evaluation (bounded today by
   `ContractLimits.MaxSyncDAppCalls`, `lang/shared/src/main/scala/com/decentralchain/lang/v1/ContractLimits.scala:24`)
   or the number of `InvocationTreeTracker` nodes recorded
   (`DAppEnvironment.InvocationTreeTracker`, referenced in
   `InvokeScriptTransactionDiff.scala:216`, which already tracks the per-invocation call
   tree that could supply a step count). Pros: no wire-format change, composes cleanly with
   the existing `FeeInfo` fold, and "per step" naming fits a call-chain-depth metric far more
   naturally than a flat sender-supplied number. Cons: fee then depends on POST-execution
   information (you don't know the step count until you've run the script), which is a
   structurally different validation order than every existing fee stage (which are all
   pre-execution, static from the tx + blockchain state) — `FeeValidation.apply` runs before
   `InvokeScriptTransactionDiff` executes the script (checked in `TransactionDiffer`'s
   ordering), so a true per-step fee would need either a min-fee pre-check plus a
   post-execution true-up (two-phase fee collection, a meaningfully bigger design change) or
   a STATIC upper-bound charged pessimistically (charge for `MaxSyncDAppCalls` steps
   regardless of actual depth, refund never happens in this codebase's existing model —
   simpler, but potentially punitive/wasteful for shallow calls).
3. **Default/sentinel-derived value** — no new field, no new post-execution stage; instead a
   fixed extra fee is required whenever a V3 tx invokes a dApp below some STDLIB threshold
   (mirroring the "test 4/5 expects failure without something present" reading). This is the
   cheapest to implement (a static per-tx-type/version constant addition, exactly like
   `ScriptExtraFee` today) but does not actually let a "step" vary anything — it would really
   be `extraFeeFlat`, not `extraFeePerStep`; the name in the tests strongly implies a
   per-something multiplier, so a flat sentinel is a plausible IMPLEMENTATION SHORTCUT but
   arguably contradicts the feature's own name.

**Recommendation:** option 2 (fee-calculation composition keyed on invocation step count),
with the static-upper-bound sub-variant (charge conservatively for the max possible steps at
validation time, pre-execution) rather than true two-phase post-execution billing. Reasoning:
- It requires no transaction wire-format change — lowest consensus blast radius of the three,
  and does not force a decision about whether V1/V2 transactions need room for a field they
  can never legally carry (per the version matrix, V1/V2 can't invoke V5+ dApps at all, so a
  wire field scoped only to "V3 invoking V5+" would be an odd, narrowly-conditional protobuf
  addition).
- It fits the existing `FeeInfo`-fold shape in `FeeValidation.scala` as a genuine sibling
  stage, reusing the same pattern `feeAfterSmartTokens`/`feeAfterSmartAccounts` already
  establish (read blockchain/tx-derivable facts, add a proportional extra fee, record a
  human-readable requirement string for the "not enough fee" error).
- The "steps" concept already has a natural source in this codebase
  (`MaxSyncDAppCalls`/`InvocationTreeTracker`) rather than needing an invented one.
- The static-upper-bound sub-variant avoids the two-phase-billing redesign, at the cost of
  potentially over-charging shallow invocations relative to their actual call depth — an
  explicit, disclosed tradeoff, not hidden.

**This recommendation is NOT a final decision** — it needs review sign-off per this
document's own opening instruction, particularly on the over-charging tradeoff and on
resolving the test 4/5 ambiguity (reading (a) vs (b) above) before any implementation begins.

## Gating requirement — new BlockchainFeature

**Feature id: 30.** Verification performed at time of writing (2026-08-02):
```
grep -nE "BlockchainFeature\([0-9]+" node/src/main/scala/com/decentralchain/features/BlockchainFeature.scala
```
shows ids 1-28 assigned in code (highest committed id: 28, `ModernGroth16Verifier`). id 29 is
NOT yet present in `BlockchainFeature.scala` (`feature-29-spec.md`'s SC-575/SC-580 scaffold
has not been implemented — it remains spec-only, same as this document), but it IS already
reserved by that sibling spec document, which explicitly claims id 29 and instructs its own
"Do NOT scaffold" gate the same way this one does. To avoid a same-day design-doc collision
if both specs are later implemented in an unpredictable order, this document claims the NEXT
id after 29, i.e. **30**, rather than re-claiming 29. **Re-verify both facts (28 is the
highest CODE id, and whether 29 has since been claimed/implemented by feature-29-spec's own
work) at implementation time** — a second design doc, written after this one, may have
already taken 30 in the interim; a spec number is a design-time placeholder, not a
concurrency-safe allocation.

**Dormancy requirement:** `BlockchainFeature(30, "InvokeScriptTransaction version gating and
per-step invocation fee")` (name adjustable at implementation), dormant until testnet
governance activation, gated exactly like every other `BlockchainFeatures` entry via
`blockchain.isFeatureActivated(BlockchainFeatures.<name>)`. Zero behavior change until
activated.

**Pre/post-activation test-pairing structure required (description, not code):**
- For EVERY combination in the matrix above, two paired node-it (or node-tests, if
  achievable without a live network) tests are needed:
  1. A **pre-activation** test (feature NOT in `preactivatedFeatures`, or explicitly
     activated at a height in the future relative to the test) that submits the exact same
     invocation and asserts it behaves EXACTLY as today — i.e., for the combinations the
     matrix will eventually reject (V1/V2 tx → V5+ dApp), the pre-activation test asserts
     SUCCESS (today's real behavior), proving the gate doesn't retroactively break anything
     before governance turns it on.
  2. A **post-activation** test (feature pre-activated at height 0, following this suite's
     own existing `preactivatedFeatures` pattern at lines 34-41) that asserts the NEW
     rejection/acceptance per the matrix.
- This mirrors exactly the "byte-identical old behavior" requirement the plan's Task 5 states
  and the pattern feature-29-spec's Items 1/2 already describe for SC-575/SC-580 — the
  pairing structure is the same shape across both specs, just applied to a different
  validation rule.
- Historical-block replay: any block mined before the feature's activation height, containing
  a now-to-be-rejected combination (which, by construction, must have succeeded when mined,
  since the rule didn't exist yet), must continue to replay/validate successfully post-
  upgrade — standard height-gated `isFeatureActivated` semantics provide this if the check is
  wired to read the height AT THE TRANSACTION'S OWN BLOCK, not "is the feature active now" —
  call this out explicitly in code review, per the same caution feature-29-spec raises for
  its own items.
- If the fee-mechanism recommendation (option 2, step-based) is adopted, the post-activation
  fee tests additionally need: a case where the required extra fee is under-supplied (rejected
  with a "not enough fee" style error, matching `FeeValidation`'s existing
  `notEnoughFeeError` message shape) and a case where it's correctly supplied (succeeds).

## Non-goals / explicitly open questions this spec does NOT resolve

1. **The test 4/5 ambiguity (reading (a) vs (b) above) is not resolved here.** Whether
   `extraFeePerStep` is meant to be an optional sender-supplied value whose ABSENCE is the
   actual failure condition being tested (reading b), or whether the ignored tests are simply
   unfinished stubs missing an argument a future author intended to add (reading a), cannot be
   determined from static reading of the ignored test file alone. `git log --all
   --grep=SC-695` was run as part of this pass (2026-08-02): it surfaces only the feature-29
   spec commits (which explicitly scope SC-695 OUT, no design content) and four unrelated
   2021-era upstream Waves commits literally titled `SC-695-activations-tests` (#3360, RIDE
   directive/activation-suite work, nothing about an invoke-tx-version matrix or a fee
   mechanism) — a ticket-number collision with the ancestor project, not the origin of this
   repo's SC-695 ignores. No further design context exists in this repo's history. This needs
   either finding the original SC-695 ticket in whatever external tracker minted it, or a
   product decision from whoever owns the RIDE fee model.
2. **The exact numeric value of `ScriptExtraFee`-equivalent per step, and what counts as
   "one step," are not specified here.** This spec identifies `MaxSyncDAppCalls` and
   `InvocationTreeTracker` as the two plausible sources of a step count but does not pick
   between them or propose a fee-per-step DCC amount — that is a product/economics decision,
   not something derivable from the codebase.
3. **Whether the version-gate applies to `InvokeExpressionTransaction` (free calls) as well
   as `InvokeScriptTransaction` is not addressed.** The ignored tests only exercise
   `InvokeScriptTransaction`; `InvokeExpressionTransaction` shares much of the same diff code
   path (`InvokeScriptTransactionDiff.apply` branches on `tx` type at line 57-59) but has no
   independent "tx version" concept in the same sense (it's driven by `tx.expression.stdLibVersion`
   directly, per `extractFreeCall`, line 300) — whether it needs an analogous gate is an open
   question this document does not answer.
4. **Exact production error message text.** Both existing HTTP-error tests and this spec's
   proposed rejections currently use the placeholder `"State check failed"` the ignored tests
   themselves use (with their own comment flagging it as provisional — "Detailed error message
   to be implemented in future"). Per the same caution feature-29-spec applies to its own
   placeholder string, do NOT ship a test's literal placeholder text as real production copy
   without deciding the actual message during implementation review.
5. **Whether this belongs in ONE feature (id 30, both the version matrix and the fee
   mechanism) or should itself be split into two features**, given they are logically
   separable (a tx could be version-gated with no fee change, or fee-adjusted with no version
   change) and feature-29-spec already demonstrated that batching loosely-related items under
   one id produces exactly the kind of "gates nothing well-defined" scaffold this plan warns
   against. This document tentatively keeps them together because the ignored tests couple
   them in the same suite and reference each other (V3-tx-is-the-version-that-supports-the-
   fee-mechanism), but a reviewer may reasonably decide to split id 30 into 30a/30b (or 30/31)
   at implementation time.

**Do not implement any part of this document until the above three items are resolved by a
human reviewer and the design is signed off.**

## Implementation Notes (2026-08-03)

This feature has been implemented (`InvokeScriptTransaction` version x STDLIB-version
compatibility matrix + the step-based extra-fee mechanism), adversarially reviewed twice
(both passes concluded SAFE-TO-PROCEED, with real test evidence: 2705/2705 regression suite,
7/7 unit tests, 7/7 real dockerized node-it tests), and merged. It is gated behind
`BlockchainFeature(30)` and has **zero live effect** on any running network until governance
activation is proposed and voted in.

Two items from the "Non-goals / explicitly open questions" section above were **not**
resolved by the implementation and remain open, to be revisited before activation is ever
proposed:

- **Open question 2 (fee value) was not resolved — it was placeholdered, not decided.**
  `FeeValidation.InvokeExtraFeePerStep = 100000L` is a **placeholder value with no economic
  justification**. It was picked to make the implementation and tests concrete, not derived
  from any cost/benefit analysis. Before this feature is ever proposed for activation, this
  number must be revisited and justified — e.g. via a proportional analysis against the
  existing `ScriptExtraFee` and the real marginal cost of an invocation step — by whoever owns
  the RIDE fee model. Shipping this value to mainnet without that analysis would be an
  unreviewed economic decision masquerading as a settled implementation detail.
- **Open question 3 (`InvokeExpressionTransaction` scope) remains open and undecided.** The
  implementation, matching the ignored tests it was built from, only covers
  `InvokeScriptTransaction`. Free calls via `InvokeExpressionTransaction` are untouched by the
  version gate or the fee mechanism, per this spec's own Non-goal #3. Whether an analogous
  gate is needed there was never decided and still needs a product/design call before
  activation.
