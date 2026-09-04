# Deliberate consensus-behavior divergences from upstream Waves

This is a decision record for behavior differences between DCC's `node-scala`
and upstream Waves that are **intentional**, not bugs and not sync gaps. It
exists so a future upstream-sync pass doesn't "fix" these by accident.

`docs/mainnet-upgrade-validation.md` (referenced by older code comments in
`Caches.scala` and `TxStateSnapshotHashBuilder.scala`) does not exist in this
working tree — checked via `git log --all --oneline -- '**/mainnet-upgrade-validation.md'`,
which shows it existed in this repo's history (e.g. commit `a3a8a87aa3`) but
is not present on this branch. This document replaces it as the live
cross-reference target; the two source comments have been updated to point
here instead of to the missing file.

## 1. `crypto/package.scala` — `checkWeakPk` default

- **Upstream default:** `false` (unchanged since the parameter was introduced
  in 2021).
- **DCC default:** `true`.
- **Effect:** DCC rejects microblock / `MicroBlockInv` signatures signed by a
  known-weak (blacklisted) public key. A stock Waves node accepts them.
- **Decision:** stays `true`. This is a deliberate hardening choice, not an
  oversight — rejecting weak-key signatures is strictly safer and has no
  known compatibility cost (weak keys are, by construction, keys nobody
  legitimate should be signing with). Do not "fix" this to match upstream's
  `false` default in a future sync pass.

## 2. `MassTransferTxSerializer.scala` — stricter parse bound

- **Upstream:** `require(entryCount >= 0 && buf.remaining() > entryCount, ...)`
  — only checks that at least 1 byte remains per claimed entry.
- **DCC:** `require(entryCount >= 0 && buf.remaining() >= entryCount.toLong * minBytesPerEntry, ...)`
  where `minBytesPerEntry = 9` (the minimum possible serialized size of one
  transfer entry: 1-byte alias/address discriminator + at least 1-byte chain
  id/length fields + an 8-byte `Long` amount, per the inline comment at
  `MassTransferTxSerializer.scala:66`).
- **Decision:** deliberate DoS hardening, keep as-is. Upstream's bound lets a
  malicious/malformed `entryCount` claim far more entries than the buffer
  could possibly contain (each entry needs at least 9 bytes, not 1),
  potentially driving a large, wasted `Vector.fill(entryCount)(readTransfer(buf))`
  allocation/parse attempt before the per-entry read logic itself fails.
  DCC's bound fails fast at the size-sanity-check stage instead. Not a
  consensus-relevant divergence (this is a pure format-sanity check on
  externally-supplied bytes, not a state-hash input), but tracked here since
  it is a real, deliberate parse-behavior difference from upstream.

## 3. `TxStateSnapshotHashBuilder.scala` / `database/Caches.scala` — committed-generators excluded from the state hash

This is **not a gap**, it's the fix for a real, confirmed upstream Waves bug.
Full mechanism, evidence, and authorship trail are documented in
`CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §2-3 and §4 (top-level repo
reference doc, not in this clone) — summarized here for anyone who only has
this repo checked out:

- Waves-NG builds blocks incrementally via microblocks. When a microblock is
  orphaned (discarded due to a small fork), the in-memory "committed
  generators" list does not roll back with it. Two nodes that converge on the
  identical final chain can each be carrying a different version of that
  list, depending on whether they happened to observe the now-discarded
  microblock before it was orphaned.
- Upstream Waves folds that list into the block's state hash
  (`Caches.scala`'s `addCommittedGeneratorBalances` / `addNextCommittedGenerator`,
  introduced in Waves' own `c26947df1a` / `a6a7877def`, still present
  unmodified in Waves' current codebase). Since the list can differ between
  two nodes that agree on everything else, so does their computed state hash
  for the same block — a liveness/DoS-class divergence risk (not an
  access-control or fund-safety one).
- DCC's fix: `nextCommittedGenerators` and `CommittedGeneratorBalances` are
  intentionally excluded from both the per-TX state hash
  (`TxStateSnapshotHashBuilder.scala`) and the cache layer (`Caches.scala`).
  The validator set is instead committed cryptographically at period
  boundaries via a separate `committedGeneratorsHash` carried in the block
  header, which is not subject to the same mid-period microblock-orphan
  rollback gap.
- **Decision:** keep the exclusion. Do not reintroduce
  `nextCommittedGenerators`/`CommittedGeneratorBalances` into the state hash
  in any future sync pass that tries to match upstream's `Caches.scala`
  byte-for-byte — doing so would reintroduce the divergence bug into DCC.
- This has not been reported upstream to Waves as of this writing (see
  `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §4, "Not yet done").

## 4. Height-3325 fee-carry exclusion (`c903a9b9b7`, PR #53) — investigated, no fix needed

Folds in the investigation originally scoped as a separate task (upstream
sync plan's Task 25.7 code portion): whether the `CommitToGenerationTransaction`
fee-carry exclusion needed an activation-height gate (a would-be "feature
29"). It does not.

- The exclusion itself (excluding `CommitToGenerationTransaction` fees from
  the normal NG 60% fee-carry-forward) is correct and DCC-authored — it has
  no upstream Waves equivalent to diverge from.
- It was shipped without an activation gate, which caused a real, confirmed,
  singular divergence at testnet height 3325 (block 3324's
  `CommitToGenerationTransaction`, mined before the rule existed, gets
  handled differently once the rule ships) — full root-cause trail in
  `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §2.
- **No gate was built.** Neither Waves mainnet (`DeterministicFinality`
  feature 25 has never activated there) nor DCC's real legacy mainnet (no
  `CommitToGenerationTransaction` references exist in that codebase at all)
  has any history for this rule to conflict with. Testnet is the only chain
  that ever ran pre-fix code against real history, and testnet is
  disposable. The resolution is operational (wipe and relaunch testnet from
  genesis under the current, already-correct code), not a code change — see
  `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §3 for the full argument and the
  mainnet-migration caveat (this conclusion is specific to
  `CommitToGenerationTransaction` and does not generalize to other
  consensus-relevant DCC changes touching functionality legacy mainnet's
  real history did use).
- **Decision:** documented, not fixed, matching
  `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §6's feature table ("29 — Not
  built — superseded by wipe-testnet conclusion").

## 5. `CancelLeasesToDisabledAliases` network-filter inversion (`95fc1cd4f8`) — fixed, not a divergence

Not a deliberate divergence — recorded here because the fix touches the
shared `PatchOnFeature` base class. `95fc1cd4f8` ("purge Waves chain
bytes") changed `CancelLeasesToDisabledAliases`'s network set from `Set('W')`
(Waves-mainnet-only) to `Set.empty`, intending to disable this
Waves-mainnet-specific historical lease cleanup entirely for DCC (a clean
chain with no such historical leases to clean up). `PatchOnFeature`'s old
`networks.isEmpty || networks.contains(...)` guard meant an empty set
inverted that intent to "applies to every network" instead of "applies to
none" — this would throw the first time any node crossed the
SynchronousCalls (feature 16) activation height on stagenet (`'S'`) or
testnet (`'!'`), since no `CancelLeasesToDisabledAliases-S.json`/`-!.json`
patch-data resource exists for those chain IDs. Fixed in
`DiffPatchFactory.scala`: `PatchOnFeature.isDefinedAt` now treats an empty
`networks` set as "applies to no network." Verified this is the only
`PatchOnFeature` consumer in the codebase (`CancelAllLeases`/
`CancelInvalidLeaseIn`/`CancelLeaseOverflow` all extend the differently-
shaped `PatchAtHeight`, unaffected), so the semantic change is safe.
See `CONSENSUS-BUG-INVESTIGATION-REFERENCE.md` §5.

## 6. Committee-data live-cache consumers — exhaustive classification (2026-08-31)

Follow-up to `7dfe0a8cd8`/`eb1fce2ccd` (§ above references, HotStuff's committee
provider bug). Those commits fixed one instance and spot-checked two more; this
is the systematic sweep classifying **every** remaining reader of the live,
in-memory `NgState.finalizationState.generatorSet` field (reachable either via
the `Blockchain.currentGeneratorSet`/`NG.currentGeneratorSet` accessor, or by
reading `ng.finalizationState.generatorSet` / `ngState.finalizationState.generatorSet`
directly) as either:

- **(a) safe** — synchronous, single use within the same block-processing
  call, where "the live value" legitimately means "right now" (matches T0's
  `validateFinalizationVoting` pattern), or
- **(b) unsafe** — cached and/or reused across later, unrelated events
  (matches HotStuff's original bug pattern).

The safe, non-live alternative is `Blockchain.BlockchainExt.currentCommittedGeneratorSet`
(`state/Blockchain.scala`, added in `eb1fce2ccd`), which reconstructs the
committee from the persisted, period-keyed `committedGenerators` checkpoint
plus a direct balance read, instead of the live cache.

**Full site list** (`git grep -n "currentGeneratorSet\b" node/src/main/scala/`
plus a direct-field grep for `ng(State)?\.finalizationState\.generatorSet`,
since two sites read the field without going through the named accessor):

| Site | Classification | Reason |
|---|---|---|
| `Application.scala:233` | N/A — doc comment | Explicitly documents *not* using the live field; this is the fix from `7dfe0a8cd8`/`eb1fce2ccd`, not a consumer. |
| `NG.scala:32` | N/A — trait definition | Declares the accessor; not a consumer. |
| `BlockchainUpdaterImpl.scala:943-944` | N/A — impl definition | Implements the accessor by reading `ngState.map(_.finalizationState.generatorSet)`; not itself a consumer, it's what every other row here ultimately calls through (except the two rows that read the field directly). |
| `CommonGeneratorsApi.scala:49` | **(b) unsafe by pattern, deliberately left as-is** | Feeds a balance fallback for `GeneratorsApiRoute`'s `/generators/at/{height}` response, read and cached into the API response for the current height. Re-investigated independently for this task (not just trusting the prior verdict): `GeneratorsApiRouteSpec` asserts the endpoint reports the **stable, period-committed deposit balance** (`initBalance - depositAndFee`), not a live spendable balance that drifts as a miner earns block rewards mid-period. Swapping to `currentCommittedGeneratorSet` (whose `balance` field is `blockchain.balance(address)`, a live read) would report a *different, drifting* number and break that invariant — confirmed by re-reading `GeneratorsApiRouteSpec`'s literal expected-JSON balance assertions, which fix the exact deposit amount, not a moving one. No new test was needed since the existing spec already proves the swap is wrong; kept as-is. |
| `MicroblockAppender.scala:54` (`voteSelf`) | **(b) unsafe by pattern, deliberately left as-is** | Same underlying field, same balance-stability concern per `eb1fce2ccd`'s investigation: `voteSelf` needs the committee's current per-round view, and a naive swap to a live-`balance()`-based set has the identical drift problem as `CommonGeneratorsApi` above (no dedicated balance-stability test exists for this call site specifically, but it feeds the same `GeneratorSet` type into the same endorsement-round machinery `CommonGeneratorsApi` also touches, so the same argument applies). Re-verified reasoning holds; not changed. |
| `BlockchainUpdaterImpl.scala:606` (`validateFinalizationVoting` call site, inside `processBlock`) | **(a) safe** | Reads `ng.finalizationState.generatorSet` once, synchronously, to validate the *block being appended right now* in the same call — not stored anywhere or reused for a later, unrelated event. Matches the T0 pattern exactly. |
| `BlockchainUpdaterImpl.scala:411` (persisting a forged block) | **(a) safe** | Reads `referencedFinalizationState.generatorSet`, where `referencedFinalizationState = ng.finalizationStateFor(block.header.reference)` (line 399) — already the *reference-scoped* historical state, not `ng.finalizationState`'s unconditional latest. This is itself a prior deliberate fix (see the inline "Task D fix" comment at lines 390-398): using the unconditional latest here let a discarded microblock's generator-set advance leak into a chain that no longer contains it. Synchronous, single use, correctly scoped — safe, and already documented in-place. |
| `SnapshotBlockchain.scala:288` | **(a) safe** | Reads `ngState.finalizationState.generatorSet` once, synchronously, at `SnapshotBlockchain` construction time, to seed that snapshot's own generator-set field for the duration of one synchronous validation/diff pass. Not cached across later events — a new `SnapshotBlockchain` is constructed fresh each time one is needed. |

**Verdict: no code change required.** Every real consumer was already
correctly classified by the prior investigation (`eb1fce2ccd`'s commit
message) or is a pre-existing, already-fixed, correctly-scoped safe read
(`BlockchainUpdaterImpl.scala:411`'s "Task D fix"). This sweep is broader than
the original 5-call-site estimate suggested it needed to be — that estimate
predates this session's `Application.scala` fix, which already removed
HotStuff's own (unsafe) consumption of the field, so the real, current count
of *consumers* (excluding the accessor's own trait/impl definitions and the
doc-comment) is smaller: 5 real consumer call sites, 2 correctly left as-is
and 3 already safe. No new test was added because no new fix was applied;
`GeneratorsApiRouteSpec` remains the regression guard for the one invariant
(balance stability) an incorrect future "fix" here would most likely break.

Two call sites that look similar but are **not** part of this field's
consumer set, checked and ruled out explicitly so a future sweep doesn't
re-flag them: `appender/package.scala`'s `data.generatorSet` /
`ExtensionAppender.scala:115`'s `x.generatorSet` are a `BlockAppendData`-carried
value computed fresh from PoS-selector `validGenerators` logic
(`package.scala:47-72`), not a read of the live finalization-state cache at
all — different data source, same field name.

Also checked: `node/testkit/.../ForwardingBlockchainUpdaterImpl.scala:68`
forwards `currentGeneratorSet` to a wrapped `delegate` as part of a bulk,
mechanical forwarding-method list (a test-fixture wrapper) — it has no logic
of its own and isn't a real consumer.

## 7. Feature 26 (`AdjustedBlockRewardDistribution`) — reward distribution ported faithfully; DAO/XTN split is inert by design

Ported from upstream Waves `f1bedddb2e` ("Adjusted block reward distribution
(#4086)") as an operator-approved, real monetary-policy change — **not** a
bookkeeping default. Four hunks, all ported byte-for-byte against upstream's
logic (naming differences only: `Dcc`/`dcc` in place of `Wave`/`waves`):

- `state/BlockRewardCalculator.scala` — adds `RewardDistribution`,
  `DefaultDistribution` (the pre-26 2/2/2 split at the 6-DCC-equivalent
  reward) and `AdjustedDistribution` (the post-26 10/8/2 split at
  `AdjustedFullReward = 20 * Constants.UnitsInDcc`), and switches
  `getBlockRewardShares` to pick between them by
  `featureActivationHeight(AdjustedBlockRewardDistribution)`.
- `state/Blockchain.scala` — `blockRewardBoost` now returns `1`
  unconditionally once feature 26 is active, superseding feature 23's
  `BoostBlockReward` 10x multiplier (DCC's `blockRewardBoostPeriod`: 300,000
  mainnet / 2,000 testnet).
- `state/BlockchainUpdaterImpl.scala` — `computeNextReward` force-sets the
  voted block reward to `AdjustedFullReward` (20 DCC-equivalent) exactly once,
  at the activation height; voting continues from that new baseline
  afterwards (the reward stays votable, it is not fixed at 20 forever).
- `state/diffs/EthereumTransactionDiff.scala` /
  `state/diffs/ExchangeTransactionDiff.scala` — the existing
  `DeterministicFinality` (feature 25) validation gates are widened to
  `DeterministicFinality || AdjustedBlockRewardDistribution`. This is a
  no-op on any chain where 25 is already active (true for every DCC preset
  that can reach 26), ported only for byte-for-byte parity with upstream.

**Real, intentional effect on DCC:** once feature 26 activates, DCC's block
reward resets from its current 6-DCC-equivalent (with a live 10x boost period
from feature 23) to 20-DCC-equivalent, and the 10x boost retires permanently.
This is the monetary-policy change the operator approved (decision recorded
2026-09-02); it is not a side effect of a mechanical port.

**Why the DAO/XTN buyback split (hunk a) is inert on DCC today:** the ported
`RewardDistribution` split table sends the DAO address 10 DCC-equivalent and
the XTN buyback address 2 DCC-equivalent once the reward is at or above the
20-DCC `AdjustedFullReward`. But `FunctionalitySettings.MAINNET`,
`.TESTNET`, and `.STAGENET` all set `daoAddress = None` and
`xtnBuybackAddress = None` (see the inline comments at
`BlockchainSettings.scala:145-147`, `:166-168`, and `:185-187`: "DCC: Waves
DAO/XTN buyback addresses removed — these protocols don't exist on DCC").
`getBlockRewardShares`'s `daoAddress.fold(0L)(...)` /
`xtnBuybackAddress.fold(0L)(...)` guards mean every DAO/XTN share folds to
`0` on all three presets, and **100% of the 20-unit reward goes to the
miner** — no DAO/buyback split actually happens in production. The split
table itself is ported as-is (faithful parity with upstream, and it is
exercised by real, non-trivial values in `DomainPresets.BlockRewardDistribution`,
the test preset that does set both addresses), but its production behavior
on DCC is, by design, "miner keeps everything." This is deliberate, not an
oversight: DCC has no DAO or XTN-buyback protocol, so there is nothing for
that split to pay out to. A future sync pass should not "fix" this by
inventing DCC DAO/buyback addresses — that would be a separate,
much larger policy decision, not a consensus-parity bug.

**Activation path (not completed in this change):** feature 26 is
deliberately **not** added to any preset's `preActivatedFeatures` map — it
activates strictly by vote, the same way every other votable DCC feature
does. The per-node opt-in mechanism is `decentralchain.features.supported`
(a `List[Short]` of feature ids, `node/src/main/resources/application.conf`
under the `features` block, backed by `FeaturesSettings` in
`settings/FeaturesSettings.scala`), counted against
`functionality.feature-check-blocks-period` /
`functionality.blocks-for-feature-activation` (chain-level voting-window and
threshold settings, e.g. `custom-defaults.conf`/`network-defaults.conf`).
Getting the operator's approved change to actually run on the relaunched
testnet requires adding `26` to `supported` on enough of testnet's nodes to
clear the activation threshold — that is infra-repo config
(`infra/node-config/testnet/dcc.conf` and the `infra/clusters/testnet/apps/nodes.yaml`
blocks, a sibling repo to this one, not touched by this change) and is
explicitly deferred to the relaunch runbook task, not silently dropped.
