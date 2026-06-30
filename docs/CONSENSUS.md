# DCC Consensus Upgrade Plan

> Last updated: June 30, 2026  
> **T0: ✅ Testnet active** — T0 finalizedHeight advancing, all 3 generators committed, auto-commit every 35min. Mainnet: ❌ not yet activated.  
> **T1: ✅ Implemented** — FillReceipt code complete and deployed on testnet matcher.  
> **T2: ✅ Active on testnet** — T2 lag=0 (finalizing at chain tip). round-timeout=1200ms (tuned 2026-06-30). Soak PASSED (all 4 failure scenarios). HotStuff engine fires on both miner and P2P paths. Mainnet: ❌ requires T0 mainnet-stable ≥60 days first.  
> **Blockers before mainnet**: see Approval Checklist below.  
> Covers: T0 activation → T1 matcher → T2 HotStuff

---

## Current State

DCC runs **FairPoS V2** (block production) + **LPoS** (economic model) with Waves-NG microblocks.

| Property | Value |
|---|---|
| Block production | FairPoS V2 — anyone with ≥1,000 DCC can forge |
| Average block time | ~60s (genesis-locked on mainnet) |
| Microblock visibility | ~3s |
| Settlement throughput | ~100 ExchangeTransactions/sec |
| Finality | **Cryptographic on testnet** (gap=2, BLS endorsements) · Probabilistic on mainnet |
| Mainnet nodes | ~50 |

> **Testnet** is running v1.6.3 with feature 25 active, `generation-period-length=100`, `delay-delta=8`, `average-block-delay=30s`. Both LKE generators committed for period 101. First cryptographic finality expected at height ~101.

**Architecture verdict**: Correct. DCC's off-chain CLOB + on-chain settlement is architecturally identical to dYdX v4. Two things are missing: a pre-confirmation signal and cryptographic finality.

---

## Key Discovery

Waves Platform shipped **DeterministicFinality** (feature 25) on December 18, 2025. DCC forked Waves on February 26, 2026. The complete implementation has been in `Ecosystem/node-scala` since day one — BLS signature aggregation, `CommitToGenerationTransaction`, generator set management, conflict detection, finalization voting, REST API, and full test suite. It is gated behind one config line that has never been added.

**T0 is not a build task. It is an activation task.**

---

## FairPoS Parameters (Critical — Do Not Get Wrong)

### Correct testnet genesis config
```
min-block-time        = 5s
delay-delta           = 8      ← not 4 (wider band = less responsive, counterintuitive)
average-block-delay   = 15s
initial-base-target   = 1656   ← not 48
```

### Correct mainnet hard fork config (block ~2,550,000)
```
min-block-time        = 5s
delay-delta           = 8
# average-block-delay stays 60s — genesis-locked, cannot change without full chain restart
# C1 = 70000, C2 = 5e17 — never touch, empirically calibrated
```

### Why delay-delta=4 is wrong
Higher `delay-delta` = narrower dead-band = **more** responsive. The name is counterintuitive.  
- `delta=4`: dead-band [34s, 86s] = 52s wide — less responsive  
- `delta=8`: dead-band [38s, 82s] = 44s wide — more responsive (FairPoS V2 default)

The LKE node baseTarget mismatch incident was caused by changing `delay-delta` from 8 to 4 on June 20.

---

## Architecture: Layered Finality

```
FairPoS          → block production, never halts, no threshold needed
  └── T0 layer  → ~30s cryptographic finality (needs 2/3 of committed generators)
       └── T2   → ~500ms finality (needs 2/3 for fast rounds, falls back to T0)
```

Each layer is additive, not a replacement. If T2 fast rounds fail, chain falls back to T0 timing. If T0 endorsements fail, chain falls back to FairPoS probabilistic safety. **The chain never halts.**

### Three distinct roles
| Role | Requirement | Action |
|---|---|---|
| Leasor | Any DCC | Leases stake to a generator |
| Generator | ≥1,000 DCC effective balance | Forges blocks automatically when FairPoS selects them |
| Endorser/Validator | Generator + submitted `CommitToGenerationTransaction` | Signs finality endorsements per generation period |

All endorsers are generators. Not all generators become endorsers — it is opt-in per period.

---

## Phase 0 — Activate DeterministicFinality (Feature 25)

**Timeline**: Days (testnet) → Weeks (mainnet after soak)  
**Effort**: Config change + node rebuild + generator coordination  
**New code**: Zero

### Steps
1. Add `25 = <activation_height>` to `pre-activated-features` in `decentralchain-testnet.conf`
2. Rebuild node Docker image and deploy to LKE testnet nodes
3. Each testnet generator submits a `CommitToGenerationTransaction` with their BLS public key
4. Monitor `GET /finality/status` — watch for `endorsed >= 2/3 of committed stake`
5. Observe first finalized block in logs: `Reached for height X, endorsed=Y, total=Z`
6. Run 2-week soak. If stable, notify mainnet node operators to upgrade to v1.6.3 and vote for feature 25

```hocon
# decentralchain-testnet.conf
pre-activated-features {
  1 = 0
  # ... existing 1-16 ...
  25 = 21000   # DeterministicFinality + RIDE V9
}
```

### What feature 25 does
- Validators commit to a generation period via `CommitToGenerationTransaction` (includes BLS public key)
- Each block, committed generators that are also the block forger or active endorsers sign an endorsement
- Endorsements are BLS-aggregated into `FinalizationVoting` embedded in block headers
- When `endorsedBalance * 3 >= totalBalance * 2` (≥2/3 of committed stake) → block is **finalized**
- Conflict validators (equivocators) are excluded from stake weight permanently

### Result after Phase 0
| | Before | After |
|---|---|---|
| Cryptographic finality | Never | ~120s (reducible) |
| Chain halt risk | None | None |
| Validator model | Open | Open (per-period opt-in) |
| New code | — | Zero |

---

## Phase 0.5 — Block Time Optimization

**Timeline**: Alongside Phase 0 on testnet, mainnet hard fork with community notice  
**Effort**: Config + genesis tuning + 4-week stability validation

T0 finality speed is bounded by block time — faster blocks means endorsements accumulate faster. With `average-block-delay = 15s` and T0 active, finality drops from ~120s to ~30s.

Testnet already has `min-block-time = 5s`. Target `average-block-delay = 15s` in testnet genesis. Observe `baseTarget` convergence and block production stability before proposing mainnet change.

**Result**: T0 finality ~30s with tuned block times.

---

## Phase 1 (T1) — Matcher Pre-Confirmation

**Status**: ✅ Implemented  
**Timeline**: 4–6 weeks (completed)  
**Effort**: Matcher repo only  
**Chain changes**: Zero  
**Dependency**: Independent of T0

The matcher knows a trade happened the moment it matches two orders. It currently waits for the ExchangeTransaction to land in a microblock (~3s) before traders find out. T1 closes this gap.

### What was built

**FillReceipt** (`dex/src/main/scala/com/decentralchain/dex/model/FillReceipt.scala`):
```scala
case class FillReceipt(
  orderId1:      ByteStr,   // buyer order id (canonical ordering)
  orderId2:      ByteStr,   // seller order id
  amountFilled:  Long,
  priceExecuted: Long,
  feeBuyer:      Long,
  feeSeller:     Long,
  matcherPk:     PublicKey,
  timestamp:     Long,
  signature:     ByteStr    // Ed25519 over canonical bytes
)
```
Ed25519 signature over `orderId1 ++ orderId2 ++ amountFilled ++ priceExecuted ++ timestamp` (all big-endian).  
`isValid` verifies the signature against `matcherPk`. Buyer order is always `orderId1` regardless of which was submitted vs counter.

**FillReceiptStore** (`dex/src/main/scala/com/decentralchain/dex/model/FillReceiptStore.scala`):
- Thread-safe `ConcurrentHashMap` keyed by both order IDs
- Configurable `maxSize` (default 100,000 keys) with oldest-first eviction
- Configurable `ttlSeconds` (default 600 = 10 minutes) with lazy TTL expiry on read and bulk `evictExpired()`
- Kamon metrics: `matcher.receipt.store.size` gauge, `matcher.receipt.hits` counter, `matcher.receipt.misses` counter, `matcher.receipt.latency_ms` histogram

**Receipt endpoint**: `GET /matcher/orders/{orderId}/receipt`
- Route: `dex/src/main/scala/com/decentralchain/dex/api/http/routes/v0/FillReceiptRoute.scala`
- Returns `200 application/json` with the full `FillReceipt` JSON on hit
- Returns `404` with `{"error": "receipt not found..."}` on miss
- Returns `400` with `{"error": "invalid order id"}` for non-Base58 input
- Instrumented with `withMetricsAndTraces("getOrderFillReceipt")` for Kamon HTTP timing

**Integration**: `OrderEventsCoordinatorActor` signs and stores a receipt on every `Events.OrderExecuted` **before** broadcasting the `ExchangeTransaction`. `Application` wires `FillReceiptStore` and `matcherKeyPair` into the actor and exposes the route.

**Tests**:
- `FillReceiptSpec` — signing, verification, canonical byte determinism, buyer/seller ordering, JSON fields
- `FillReceiptStoreSpec` — put/get by both IDs, unknown ID, max-size eviction, TTL=0 instant expiry, `evictExpired`, multi-receipt isolation, size accounting

### Receipt endpoint specification

```
GET /matcher/orders/{orderId}/receipt

Path parameter:
  orderId  Base58-encoded order ID (buyer or seller)

Success (200):
{
  "orderId1":      "base58...",  // buyer order id
  "orderId2":      "base58...",  // seller order id
  "amountFilled":  1000000,
  "priceExecuted": 800000000,
  "feeBuyer":      300000,
  "feeSeller":     300000,
  "matcherPk":     "base58...",
  "timestamp":     1719187200000,
  "signature":     "base58..."
}

Error (400):  {"error": "invalid order id"}
Error (404):  {"error": "receipt not found — order not yet filled or receipt expired"}
```

### Result after Phase 0+0.5+1
| Milestone | Now | After |
|---|---|---|
| Fill receipt to trader | ~3s | **<500ms** |
| Cryptographic finality | Never | ~30s |
| vs Hyperliquid (884ms perceived) | Slower | **Faster** |

---

## Phase 2 (T2) — HotStuff on Committed Generators

**Timeline**: Months — after T0 stable on mainnet for 60+ days  
**Status**: ✅ Implemented and active on testnet (`hotstuff.enabled = true`)  
**Prerequisite**: T0 in production, BLS infrastructure proven

### Why CommitToGenerationTransaction solves HotStuff's validator set problem

HotStuff normally requires a fixed permanent validator set — the centralization concern. DCC avoids this:

- `CommitToGenerationTransaction` registers validators **per generation period** (10,000 blocks on mainnet, ~7 days at 60s)
- Each period the set recalculates from whoever committed
- No validator is permanently in or out
- Economic barrier: same as FairPoS (≥1,000 DCC)

The generation period commitment IS the rotating committee. T2 builds directly on it.

### What was built

1. **3-round HotStuff voting** (Prepare → Pre-Commit → Commit) with BLS-aggregated Quorum Certificates
   - Reuses `blst-java` BLS infrastructure from T0 — QCs are aggregated BLS signatures over block hashes
   - Same key registration via `CommitToGenerationTransaction` and `GeneratorSet`

2. **Validator set**: committed generators from `CommitToGenerationTransaction` — no new registration mechanism

3. **Round timeout fallback**: 1200ms per round (p99 ~1000ms + 20% margin, tuned 2026-06-30); on timeout → T0 handles finality, chain never halts

4. **Leader rotation**: block forger = HotStuff leader for that block's voting round (FairPoS schedule)

5. **4-week testnet soak** required before enabling in production

### Codebase reference

| Component | Location | Status |
|---|---|---|
| Round ADT | `consensus/hotstuff/HotStuffRound.scala` | ✅ |
| Vote + BLS signing | `consensus/hotstuff/HotStuffVote.scala` | ✅ |
| Quorum Certificate | `consensus/hotstuff/HotStuffQC.scala` | ✅ |
| Vote accumulator | `consensus/hotstuff/HotStuffVoteCollector.scala` | ✅ |
| Finality tracker | `consensus/hotstuff/HotStuffFinalityTracker.scala` | ✅ |
| Engine (Akka actor) | `consensus/hotstuff/HotStuffEngine.scala` | ✅ |
| P2P message specs | `network/BasicMessagesRepo.scala` (codes 39, 40) | ✅ |
| Settings | `settings/HotStuffSettings.scala` | ✅ |
| Config | `node/decentralchain-{testnet,mainnet}.conf` | ✅ disabled |
| Tests | `tests/.../consensus/hotstuff/` | ✅ 3 suites |

**To enable after T0 is mainnet-stable ≥60 days:**
```hocon
dcc.hotstuff {
  enabled = true
  round-timeout-ms = 1200
}
```

### Scaling properties at 50 nodes

With BLS aggregation, message complexity is O(n) not O(n²):
- Leader broadcasts proposal to n validators
- n validators return BLS signatures  
- Leader aggregates into single QC
- Total: ~150 messages × 3 rounds = ~450 messages per block

At 50 nodes this is trivial. Subcommittees only become necessary at 500+ validators. DCC has 10-15x headroom before that matters.

### Failure modes

| Situation | Result |
|---|---|
| ≥2/3 committed, rounds complete fast | ~500ms finality (T2) |
| ≥2/3 committed, rounds too slow | ~30s finality (T0 fallback) |
| <2/3 committed | FairPoS probabilistic (no halt) |
| All validators offline | FairPoS still produces blocks |

### Generation period lengths

| Network | `generationPeriodLength` | Duration at 60s blocks | Duration at 15s blocks |
|---|---|---|---|
| Mainnet | 10,000 blocks | ~7 days | ~42 hours |
| Testnet | 3,000 blocks | ~50 hours | ~12.5 hours |

Validators commit for the upcoming period. The HotStuff committee rotates at every period boundary.

### Result after T2
| | Value |
|---|---|
| True finality | ~500ms |
| Validator model | Rotating per-period, not permanent |
| Chain halt risk | Never |
| vs Sei v2 / Sui | Matched |

---

## T3 — Future Ceiling (Mysticeti DAG-BFT)

**Only relevant if T2 gets saturated. Not on the current roadmap.**

If DCC reaches 50,000+ TPS demand and T2's linear HotStuff becomes a bottleneck, the next step is **Mysticeti uncertified DAG-BFT** — the protocol Sui uses in production (~400ms, ~5-20K TPS) and IOTA ported wholesale in 2025.

Key innovation: the **Direct Decision Rule** eliminates quorum certificates entirely. A block commits when 2f+1 witnesses in round R+1 reference it AND those witnesses are referenced by 2f+1 blocks in R+2. Achieves the theoretical BFT minimum of 3 message delays with zero extra rounds.

IOTA Rebased proves it ports to non-Sui chains. Sei Giga (Autobahn) is building a hybrid version. The DCC path would be: T2 in production → observe throughput ceiling → port Mysticeti when ceiling is real, not theoretical. Timeline if needed: 2-3 years.

---

## Industry Position

| Chain | True Finality | Validator Model | Note |
|---|---|---|---|
| Sei v2 | ~380ms | Fixed 60 | Native CLOB at protocol level |
| Sui Mysticeti | ~400ms | Fixed ~108 | Best decentralized design |
| Hyperliquid | ~884ms (real) | 24, ~81% foundation stake | Centralized by design |
| dYdX v4 | ~1s | Fixed ~60 | **Architecturally identical to DCC** |
| **DCC after T0+T1** | ~30s | **Open** | **<500ms perceived — beats Hyperliquid** |
| **DCC after T2** | **~500ms** | **Rotating per-period** | **Matches fastest decentralized chains** |
| DCC today | Never | Open | ~3s perceived (microblock) |

> Solana's 400ms slot time is widely misreported as finality. Actual TowerBFT cryptographic finality: 12.8 seconds.

---

## Physics Limits

BFT finality requires a minimum of 3 message-delay rounds. The floor is network round-trip time.

| Validator geography | RTT | Minimum BFT finality |
|---|---|---|
| Co-located (same DC) | ~5ms | ~15ms (Hyperliquid's range) |
| Regional (2-3 DCs) | ~50ms | ~150ms |
| Global (100+ nodes) | ~100ms | ~300ms |

400ms is the real decentralized frontier. Sub-200ms with genuinely decentralized global validators requires geographic stake concentration.

---

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| No generators commit via CommitToGenerationTransaction | Low | Chain continues via FairPoS, no regression. Coordinate with operators in advance. |
| T1 receipt delivered, ExchangeTransaction fails | Low | Persist pending fills before delivering receipts. Retry queue. Alert on exhaustion. |
| Block time reduction causes baseTarget oscillation | Medium | 4-week testnet stability test before mainnet. Keep delay-delta=8. |
| T2 rounds time out repeatedly at near-threshold participation | Medium | Tune timeout to measured round-trip times. T0 fallback guarantees no halt. |
| Mainnet hard fork coordination | Medium | 8-week advance notice. Monitor node version distribution via P2P handshakes. |
| Jackson 2.22.0 vs upstream 2.21.1 regression | None | Intentional DCC divergence. Documented in build.sbt. Verify on each upstream sync. |

---

## What Changed From the Original Design

The original T0 plan was Casper FFG built from scratch. During research we discovered that Waves had already built DeterministicFinality (their own custom finality protocol, not identical to Casper FFG but achieving the same goal) and DCC had it in the codebase from the fork. The plan updated accordingly:

| Original | Actual |
|---|---|
| Build Casper FFG finality gadget | Activate feature 25 (already built) |
| Design BLS infrastructure | Already in codebase via `blst-java` |
| Design validator registration | `CommitToGenerationTransaction` already exists |
| Months of T0 work | Days of T0 work (config + deploy) |
| T2 needs new BLS work | T2 reuses T0's BLS infrastructure directly |

---

## Testnet Status (June 30, 2026)

| Node | Address | DCC | Status |
|---|---|---|---|
| Main (Newark 66.228.55.154) | `31RPEKcz71a3hdxt8z7qLhTpRMuRV2kUyr6` | ~26.7M | ✅ Mining ~33% of blocks |
| gen-0 (LKE Frankfurt) | `31PmKNdHAU5sZbtg8TrzKh8WfE7E8xBc9WD` | ~26.7M | ✅ Mining ~33% of blocks |
| gen-1 (LKE Frankfurt) | `31dLhqhGoGVhtkf5msWFmgZn1ErrVR6b9qV` | ~26.7M | ✅ Mining ~33% of blocks |
| val-0 (LKE Frankfurt) | — | 0 | ✅ Synced |
| Matcher | `2eEUvypDSivnzPiLrbYEW39SM8yMZ1aq4eJuiKfs4sEY` | — | ✅ Healthy on :6886 |

**Node version**: v1.6.3 (commit `be2dcfc0`) — feature 25 active, committedGeneratorsHash deployed  
**Block time**: ~30s average, min 5s  
**T0 finality**: `finalizedHeight` ≈ `height − 100`, advancing continuously  
**T2 HotStuff**: `hotStuffFinalizedHeight` finalizing at chain tip (lag=0). Active with all 3 validators. round-timeout-ms=1200 (tuned 2026-06-30, soak PASSED).  
**Auto-commit**: `auto-commit-generators.yml` runs every 35 min — keeps all 3 generators committed for the next period.  
**Gen period length**: 100 blocks ≈ 50 min  
**Known-peers**: Main node now connects to gen-0 (`:6863`) and gen-1 (`:6864`) for persistent HotStuff connectivity.

### CommitToGenerationTransaction — FIXED (commits `d352f5fb9f`, `44b93a06c5`, `e6f9e76cc8`)

**Root cause found**: `BlockDiffer.fromBlockTraced` computed `prevStateHash` from `maybePrevBlock.header.stateHash` (previous KEY BLOCK's stored hash, excluding microblocks). But the miner computed `prevStateHash` from `blockchain.lastStateHash(Some(reference))` (accumulated microblock chain hash). These diverge when any microblock exists — which they do after a `CommitToGenerationTransaction` lands in a microblock.

**Fix**: Changed `BlockDiffer.fromBlockTraced` to use `blockchain.lastStateHash(Some(block.header.reference))` — same computation as the miner. Both now use the microblock-accumulated hash.

**Testnet config**: `generation-period-length = 100` — finality every ~50 minutes (100 blocks × 30s) instead of ~8 hours.

---

## Approval Checklist

### Code — DONE ✅
- [x] Activate feature 25 on testnet — active from height 0
- [x] Set testnet `average-block-delay = 30s`, `delay-delta = 8`, `initial-base-target = 218`
- [x] T1 matcher pre-confirmation — implemented (FillReceipt, FillReceiptStore, GET /matcher/orders/{id}/receipt, Kamon metrics, tests)
- [x] Fixed `InvalidStateHash` bugs (commits d352f5fb9f, 44b93a06c5, e6f9e76cc8)
- [x] Set `generation-period-length = 100` — T0 finality every ~50 min on testnet
- [x] All 3 generators committed via `CommitToGenerationTransaction` — auto-renewed every 35 min
- [x] T2 HotStuff BFT engine implemented — 3-round protocol, BLS QCs, P2P msgs 39/40, Kamon metrics, 3 test suites
- [x] T2 active on testnet — `hotstuff.enabled = true`, `round-timeout-ms = 1200` in `dcc.conf`
- [x] Fixed miner path — `Miner.setHotStuffEngine()` ensures locally-mined blocks also trigger HotStuff rounds
- [x] `GET /blockchain/finality` with `hotStuffFinalizedHeight` advancing. First QC at height 2343.
- [x] Auto-commit cron — `auto-commit-generators.yml` every 35 min
- [x] BPS updated — `CommitToGenerationTransaction` no longer crashes consumer (skip without error)
- [x] `suspension-residence-time = 300s` — connections survive HotStuff round window
- [x] Main node connects to gen-0/gen-1 via `known-peers` for persistent HotStuff P2P

### Code — DONE ✅
- [x] **Remove `[HotStuffDiag]` debug log** — ✅ Removed (not present in codebase).
- [x] **BPS type-19 storage** — ✅ Code complete (DecentralChain `2f35c45a`). BPS deploy pending (tables truncated, ready).
- [x] **`committedGeneratorsHash` in block headers** — ✅ Deployed (node-scala `be2dcfc0`, protobuf-schemas 1.6.3, Steps B+C complete).

### Operational — REMAINING ❌ (must complete before mainnet)
- [x] **T2 testnet soak — PASSED (2026-06-30)** — all 4 failure scenarios verified at round-timeout=1200ms:
  - gen-0 down: T2 maintained lag=0 (main+gen-1 quorum)
  - gen-1 down: T2 maintained lag=0 (main+gen-0 quorum)
  - both down: FairPoS continued, T2 paused (no quorum, no halt)
  - both restored: T2 self-healed to lag=0 within 3 min
  - round-timeout-ms tuned 5000ms → 1200ms (p99 ~1000ms + 20% margin)
- [ ] **Mainnet T0 activation** — 8-week advance notice to ~50 node operators. Upgrade to v1.6.3 + vote feature 25.
- [ ] **After T0 mainnet-stable ≥60 days** — enable `hotstuff.enabled = true` in mainnet `dcc.conf`
- [ ] **Stagenet validation run** — per `mainnet-upgrade-validation.md`: legacy → modern node handoff at 10k blocks, verify no chain splits

---

## Codebase Reference

| Component | Location | Status |
|---|---|---|
| DeterministicFinality (feature 25) | `node-scala/node/src/main/scala/com/decentralchain/block/FinalizationVoting.scala` | ✅ Complete |
| Finalization state machine | `node-scala/node/src/main/scala/com/decentralchain/state/FinalizationState.scala` | ✅ Complete |
| Endorsement storage | `node-scala/node/src/main/scala/com/decentralchain/state/EndorsementStorage.scala` | ✅ Complete |
| BLS library | `packages/jvm/blst/` | ✅ Complete |
| CommitToGenerationTransaction | `node-scala/node/src/.../transaction/CommitToGenerationTransaction.scala` | ✅ Complete |
| HotStuff engine | `node-scala/node/src/main/scala/com/decentralchain/consensus/hotstuff/` | ✅ Active on testnet |
| Testnet config | `node-scala/node/decentralchain-testnet.conf` | ✅ `hotstuff.enabled=true`, feature 25 active |
| Mainnet config | `node-scala/node/decentralchain-mainnet.conf` | ❌ Feature 25 not yet activated |
| VPS dcc.conf | `/opt/dcc/config/node-testnet/dcc.conf` | ✅ `hotstuff.enabled=true`, `round-timeout-ms=1200` |
| Matcher FillReceipt | `Ecosystem/matcher/dex/src/main/scala/.../model/FillReceipt.scala` | ✅ Complete, deployed on testnet |
| Block proto | `DecentralChain/packages/sdk/protobuf-schemas/proto/dcc/block.proto` | ✅ Field 14 `committed_generators_hash` added (1.6.3) |
| BPS type-19 | `DecentralChain/apps/blockchain-postgres-sync/src/lib/consumer/` | ✅ Code fixed (`2f35c45a`) — deploy pending |
| Debug log | `node-scala/.../consensus/hotstuff/HotStuffEngine.scala:61` | ✅ Removed |
| mainnet-upgrade-validation.md | `node-scala/docs/mainnet-upgrade-validation.md` | Steps A+B+C ✅ all done |

---

*DCC Consensus Upgrade Plan · Last updated June 30, 2026*
