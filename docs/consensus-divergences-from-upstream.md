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
