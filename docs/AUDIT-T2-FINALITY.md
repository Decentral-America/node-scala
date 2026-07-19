# DCC T2 Finality — Security & Consensus Audit

**Date:** 2026-07-18 · **Branch:** `feat/endorsement-rebroadcast` · **Scope:** deterministic-finality (feature 25) consensus path + endorsement-rebroadcast fix, generation-deposit economics, public RPC/edge exposure, P2P resilience.
**Method:** four parallel read-only auditor passes over `node-scala` (+ `Ecosystem/infra`), plus a sustained multi-node finality soak. No production behavior was changed; test-side finality assertions were corrected (see §5).

---

## 0. Executive summary

The **authoritative finality path is consensus-safe.** The core cryptography, the 2/3 stake-weighted quorum math, the committee/period logic, and the deposit accounting are all correct and internally consistent. The endorsement-rebroadcast fix is sound (it only replays already-signed messages; no equivocation). The prior open question about committed-generator state-hash sections is **resolved as intended design.**

The material work before mainnet is **not** consensus correctness — it is **operational hardening** (edge defense-in-depth, P2P eclipse/DoS resistance, not shipping with blacklisting disabled) and **clearly separating the advisory HotStuff API signal from the authoritative finality** so external integrators are not misled.

### The one structural thing to understand

There are **two** finality mechanisms and they are **not** equally trusted:

| | **T0 — DeterministicFinality (feature 25)** | **T2 — HotStuff overlay** |
|---|---|---|
| Role | **Authoritative** | **Advisory only** |
| What it drives | `finalizedHeight`, reversion protection (via `lastBlockIds`) | Only `GET /blockchain/finality` (`hotStuffFinalizedHeight`) |
| Gates block application / fork-choice / rollback? | Yes (indirectly, at sync layer) | **No** — sole consumer is `FinalityApiRoute` |
| Touched by the rebroadcast fix? | **Yes** | No |
| Audit verdict | **Safe** | Safe *as an advisory signal*; lacks BFT lock rule (Findings C1/C2) |

Our node-it finality suites and the soak exercise the **T0 authoritative path** (`/blocks/height/finalized`), so the T0→T2 milestone proof stands. The HotStuff-overlay findings are scoped to the advisory API — no on-chain fork risk — but must be fixed or clearly documented before anyone consumes `hotStuffFinalizedHeight` as a finality guarantee.

---

## 1. Consensus safety (T0 authoritative)  — SOUND

Verified with **no exploitable defect**:

- **Crypto / rogue-key: clean.** Aggregate BLS verification (`FastAggregateVerify`, single common message) is rogue-key-safe *because proof-of-possession is enforced at registration*: `CommitToGenerationTransactionDiff` requires `endorserPublicKey.validated` (subgroup/infinity checks) + a `commitmentSignature` over `pubkey‖periodStart`. Every key later aggregated originates from `committedGenerators`, so all carry a PoP. The endorsed message re-derives `finalizedId` from the validator's own chain, preventing cross-fork endorsement reuse.
- **No double-finalization / 2/3 math: clean.** Finalization is recomputed deterministically in `FinalizationState.isParentFinalized` over the on-chain committed set, using integer-safe `endorsed*3 ≥ total*2` on **BigInt** balances with identical generator indexing on aggregator and validators. `validateFinalizationVoting` rejects duplicate indices, over-max endorsers, miner self-endorsement, insufficient-balance/conflicting endorsers.
- **Committee / period correctness: clean.** Caches coherently updated on append, pruned, and cleared on rollback; duplicate address or BLS key per period rejected; empty committee falls back to classic PoS.
- **Determinism (`committedGeneratorsHash`): clean.** Miner and validator compute an identical, address-sorted hash; transaction ordering does not affect it.

**Endorsement-rebroadcast fix:** correct and safety-preserving — it replays bit-identical, already-signed endorsements while the same voting is live, gated on `height == votingHeight`, and the aggregator dedups repeats. Flood risk negligible (3s cadence, duplicates not re-gossiped).

### Findings (HotStuff overlay — advisory scope)

- **C1 — HIGH (advisory scope): HotStuff overlay has no BFT safety rule.** `onBlockApplied` starts a fresh round for whatever block was applied (including post-reorg), with no persisted `lastVotedHeight`/`lockedQC`. On a fork it can vote for conflicting blocks at the same height, so two Commit QCs can form → different nodes report different `hotStuffFinalizedBlock`. **Bounded to the advisory API** (no on-chain fork), but misleads integrators. *Fix: persist voted-height/locked-QC, sign at most one Prepare per height, withdraw HotStuff finality on reorg.*
- **C2 — MEDIUM: `HotStuffFinalityTracker` never invalidates on reorg** (only replaces on strictly-greater height) → can permanently serve an orphaned block as finalized via the API. *Fix: subscribe to rollback events; verify the recorded blockId is still on-chain before serving.*
- **C3 — LOW: rebroadcast lost-update race.** `vote()` (appender thread) and `rebroadcast()` (3s scheduler) race on `@volatile pending`; the scheduler's check-and-clear can clobber a fresh vote → a rotated aggregator may miss this node's vote for one height. **Liveness only, self-heals next vote; never a safety issue.** *Fix: guard `pending` with an `AtomicReference.updateAndGet` / `synchronized` so check-and-clear is atomic.*
- **C4 — LOW (hardening): `HotStuffQC.verify` tolerates duplicate signer indices** and uses `Long` threshold math (vs T0's `BigInt`). Not exploitable (threshold filters distinct generators; aggregate BLS still needs real keys). *Fix: reject duplicate indices; mirror BigInt math.*

---

## 2. Generation-deposit economics — SOUND (prior open question RESOLVED)

The 100 DCC deposit is a **computed virtual lock**, not moved value: `generationDeposit(addr)` is *derived* from committee membership and subtracted from effective/spendable balance; the 100 DCC always stays in stored `balance` (only the fee is debited). It **self-releases** at `period.next.start` when membership expires, and is **real-burned** (with supply reduction) only on conflict slashing. Live path and historical `balanceSnapshots` path agree.

- **No double-spend / double-count**: spends below the lock are rejected ("trying to spend a deposit"); the lock lowers generating balance so it can't satisfy the mining threshold.
- **`generatingBalanceAfterDeposit ≥ minMiningBalance` check: correct, non-bypassable** (runtime eligibility is re-evaluated every block anyway).
- **Slashing timing is safe** — the burn is applied one block before the lock releases; no window to spend ahead of a slash.

**RESOLVED — flagged open question:** the committed-generator state-hash sections reading **empty is INTENDED.** The financial state hash deliberately excludes committee/deposit data because `CommitToGenerationTransaction` lands at different block positions on competing chains, so hashing it would diverge the cumulative hash and (with feature 21) permanently block chain switches. **Do NOT re-include committee data in the state hash.** Committee integrity is instead secured by the header `committedGeneratorsHash`, checked at period boundaries.

### Findings
- **E1 — LOW/MEDIUM: `committedGeneratorsHash` is optional/skippable.** A producer can omit it and validation passes unconditionally (backward-compat branch), weakening the exact chain-switch-safety property the state-hash exclusion relies on. *Fix: make it mandatory at period-boundary heights once all producers are upgraded (gate on activation height).*
- **E2 — LOW: no committee-size cap; anti-spam rests on capital, not the 0.01 DCC fee.** A well-capitalized attacker (~1100 DCC/slot, deposit returned at period end) can bloat the O(committee) per-block scan. *Fix: add `maxCommittedGenerators` per period and/or scale commit fee with committee size.*

---

## 3. Public RPC / edge exposure — node auth SOUND; edge is defense-in-depth-thin

**Sound today:** the node's api-key auth is constant-time, fail-closed, and strongly hashed (`Keccak256(Blake2b256)`); every dangerous endpoint (`/debug/*` incl. rollback, `/wallet/seed`, `/addresses`, `/transactions/sign`, `/node/stop`, mutating `/peers`) requires the key; the node REST port is bound localhost-only (Docker and k8s) and reachable only via Caddy; the production testnet Caddyfile has rate-limiting, allowlisted CORS, and security headers; **no plaintext API keys survive in current git history** (history rewritten, secrets SOPS-encrypted).

### Findings
- **R1 — MEDIUM/HIGH (defense-in-depth): no edge path denylist.** All dangerous endpoints are protected *only* by the node's api-key; a single node misconfig (blank/mistyped `api-key-hash`) would expose rollback/wallet/etc. directly. *Fix: add an edge denylist (404) for `/debug/*`, `/wallet/*`, `/node/stop`, mutating `/peers`, `/addresses*`, `/transactions/sign*` before the catch-all proxy.*
- **R2 — MEDIUM: bootstrap placeholder Caddyfile** proxies the node with `ACAO: *`, no rate-limit, no path filter until `update-caddy.yml` runs. *Fix: mirror denylist + basic rate-limit, or don't publish `NODE_DOMAIN` until the real config is pushed.*
- **R3 — MEDIUM (mainnet launch-blocker): the hardened Caddy config is testnet-only** (`workflow_dispatch` choices = `[testnet]`). *Fix: parameterize for mainnet before launch.*
- **R4 — LOW/MEDIUM: `/utils/script/*` unauthenticated CPU-DoS** (compile/evaluate RIDE), only bounded by a shared 300/min/IP zone. *Fix: dedicated tighter rate-limit zone (~20–30/min).*
- **R5 — INFO (launch check): verify the API key was rotated after the Jun 25–27 exposure** and the old key no longer authenticates (history scrubbing does not invalidate a leaked key; only a hash change on the deployed node does).

---

## 4. P2P resilience — finality SAFETY sound; LIVENESS exposed

**Finality safety is sound at the P2P layer:** fast-finality height advances only via a Commit path that first passes `qc.verify` **and** `qc.meetsThreshold` against committed validators, so an **eclipsed node cannot be fed a fake finalized height** — a forged QC needs 2/3 of validator BLS keys. Chain separation (handshake `applicationName` includes chain-id), bounded/timed handshake, per-IP connection cap, per-message size caps, and verified-DB anti-poisoning are all in place.

The exposure is **liveness / availability**, and it is amplified by the `enable-blacklisting=no` testnet workaround.

### Findings
- **P1 — HIGH: eclipse via sybil gossip + no peer diversity.** Unauthenticated `KnownPeers` (≤1000/msg) fill the candidate pool; outbound selection has no subnet/ASN diversity and the persistence cache is unbounded. Mitigated *only* if `known-peers` is populated with diverse seeds (default is empty). *Fix: subnet/ASN bucketing, cap the persistence pool, reserve outbound slots for known-peers/validators, rate-limit candidate intake.*
- **P2 — HIGH as deployed / MEDIUM design: `enable-blacklisting=no` removes the main abuse throttle.** Malformed-frame / invalid-handshake peers are closed but not penalized, enabling a cheap reconnect-loop CPU/connection DoS. *Fix: do NOT ship mainnet with blacklisting off; instead whitelist known-peers/validators from blacklisting and add suspension backoff to the close paths.*
- **P3 — MEDIUM: 100 MB max frame buffered per connection before per-message caps apply** → slow-loris memory DoS (~10 GB across max inbound). *Fix: lower global `MaxFrameLength` to the largest legitimate message, or gate large snapshot frames post-handshake.*
- **P4 — MEDIUM: unauthenticated gossip enables DB poisoning + outbound-connection amplification; no P2P rate limits.** *Fix: rate-limit `GetPeers`/intake; cap gossiped list size; drop reserved ranges on public deployments.*
- **P5 — LOW: handshake accepts any protocol version** (no floor). *Fix: enforce a minimum version for mainnet.*

---

## 5. Sustained finality soak — methodology & a corrected invariant

A new endurance suite (`NNodesRotatingFinalizationSoakTestSuite`) runs 3 forging generators, **re-commits all three every generation period** (the real validator loop — mainnet does this via the `auto-commit-generators` cron), drives round-robin transactions so the aggregator rotates, and asserts finality health across ~30 period rollovers.

**The soak surfaced a latent test-correctness issue** (which the short 3-minute suite shared but never hit): the reported `finalizedHeight` is **chain-tip-relative**, derived by `FinalizationState.isParentFinalized` from the current tip's aggregated endorsements. Under NG, a liquid-block replacement (same parent, different miner) can carry fewer endorsements, so the derived finalized height **dips by a block or two, then re-advances** as the still-live (rebroadcast) endorsements are re-aggregated. This is **expected, self-healing tip jitter** — the chain guarantees irreversibility only below `max(finalized, height − maxRollback)` (`maxRollback = 100`), **not** a hard ratchet at the very tip. A strict "finalized never decreases" assertion is therefore wrong and would flake on any tip reorg.

Both suites now assert the **correct, code-grounded invariant**:
- **No DEEP reversion** — finalized never drops more than a small bound (16 blocks) below its high-water mark (a real safety failure = un-finalizing a semi-buried block).
- **No STALL** — lag (`height − finalized`) stays under 250 (the FinalizationStalled alert threshold).
- **Net PROGRESS + RECOVERY** — the high-water mark keeps climbing and finality recovers to within tip-jitter distance of it by the end.

> *Soak result: see the run log; the first (pre-fix) run proved ~11 min of healthy finality (steady lag = 2 across 5 period rollovers) before tripping the old over-strict assertion on a single-block self-healing dip.*

---

## 6. Remediation status

Worked the punch-list to completion where it could be implemented and verified now; the rest is deferred with an explicit reason (none is "forgotten"). Node changes are on `node-scala` `feat/endorsement-rebroadcast`; edge changes on `infra` `feat/edge-hardening`.

### ✅ Implemented & verified (compile + unit tests green; finality re-verified via node-it)

| ID | Change | Where |
|----|--------|-------|
| **P2** | Exempt known-peers (validators/seeds) from blacklist **and** suspension → mainnet can run with `enable-blacklisting=yes` safely (fixes the RC#2 root cause without the unsafe workaround) | `PeerDatabaseImpl` |
| **P1** | Cap the verified-peer pool + prefer outbound candidates in unrepresented /16 subnets (anti-eclipse) | `PeerDatabaseImpl` |
| **P4** | Cap the `KnownPeers` gossip list on send to the wire limit + rate-limit `GetPeers` | `PeerSynchronizer`, `BasicMessagesRepo` |
| **C3** | Atomic (`AtomicReference` + CAS) rebroadcast `pending` check-and-clear — no lost-update dropping this node's votes | `BlockEndorser` |
| **C4** | Reject duplicate QC signer indices; BigInt 2/3 threshold math | `HotStuffQC` |
| **C2** | API reports HotStuff finality only if the block is still canonical (no orphaned "finalized"); `hotStuffFinalityIsAdvisory=true` | `FinalityApiRoute` |
| **E2** | Cap committed-generator set via its own `maxCommittedGenerators` setting — **on by default (1000)**, decoupled from `maxValidEndorsers`; bounds the per-block scan attack | `CommitToGenerationTransactionDiff`, `BlockchainSettings` |
| **R1** | Edge hard-404 denylist for `/debug/*`, `/wallet/*`, `/node/stop`, `/addresses/seed/*`, `POST /transactions/sign*`, mutating `/peers` & `/addresses` (defense-in-depth) | `infra update-caddy.yml` |
| **R4** | Dedicated tight rate-limit (30/min) for unauthenticated `/utils/script/*` CPU endpoints | `infra update-caddy.yml` |
| **E1** | Gated mandatory `committedGeneratorsHash` at period boundaries — new setting `enforceCommittedGeneratorsHashFromHeight` (default 0 = off; enforcement condition matches the miner's emit condition so no conforming producer is rejected). Code-complete & safe-by-default; mainnet sets it at genesis | `BlockchainSettings`, `BlockDiffer` |
| **P5** | Config-gated handshake version floor — new `network.min-supported-app-version` (default 0 = accept any = current behavior). Operators can refuse downgraded/incompatible peers on mainnet without any default-behavior change | `NetworkSettings`, `HandshakeHandler`, `NetworkServer` |

### 📋 Runbooks provided (require real mainnet infra / cluster access — cannot be code)

| ID | Item | Deliverable |
|----|------|-------------|
| **R3** | Mainnet edge (Caddy) config | **No mainnet infra exists** (only `testnet` tfvars/cluster/secrets). Provisioning checklist + safe workflow-parameterization steps in `infra/RUNBOOK-mainnet-edge-and-key-rotation.md`. Half-wiring an untestable production workflow was judged higher-risk than documenting the exact turn-on steps. |
| **R5** | Verify REST API key rotated post-exposure | Requires deployed-cluster access to confirm the old key no longer authenticates. Concrete rotation + verification procedure in the same runbook. |
| **P1-ops** | Ship every node a diverse `known-peers` seed list | Per-deployment ops decision; the code now *uses* diversity — seed selection is operational. |

### ◑ Full fix gated on an external dependency — but the safe increment + unblock path is shipped

| ID | Full fix blocked by | Shipped now (everything short of the external dependency) |
|----|---------------------|----------------------------------------------------------|
| **C1** | Full HotStuff BFT lock needs a **pacemaker/view-change redesign** (view = height today, so a naive lock stalls finality on reorg) + **external consensus audit** before it can be trusted. | (a) **Rollback reset** — `HotStuffFinalityTracker.rollbackTo` + `HotStuffRollbackTrigger` via `BlockchainUpdateTriggers.onRollback`: a reorg can no longer leave a stale/orphaned advisory record (with C2's canonical-only read filter). Safe, no lock, unit-tested. (b) **Design spec** `docs/hotstuff-pacemaker-design.md` — full view-change design + safety/liveness invariant checklist so the audit is a review, not research. |
| **P3** | Safely lowering `MaxFrameLength` needs the **true max single-block snapshot size** — only obtainable from production/staging telemetry (snapshots deliberately use the full frame; normal blocks are 1 MB). | **Snapshot-size telemetry** — `dcc.snapshot.{block,microblock}-response.bytes` histograms on send + receive. After a few days on testnet, set the cap to observed-max × margin with data. Turns P3 from a guess into a measurement. |

**What is already good:** authoritative T0 finality safety, BLS/PoP crypto, deposit accounting, committee/period determinism, node api-key auth, localhost-only REST binding, SOPS secret management, and the testnet edge config.
