# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-06-30. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-06-30)

| Item | Status |
|------|--------|
| Chain height | 9724+, advancing ~30-60s/block |
| Main node (Newark 66.228.55.154) | ✅ Healthy — v1.6.3-be2dcfc0, all extensions running |
| gen-0 (LKE 172.105.64.89:6863) | ✅ Mining |
| gen-1 (LKE 172.105.64.89:6864) | ✅ Mining |
| val-0 (LKE 172.105.64.89:6865) | ✅ Synced |
| blockchain-postgres-sync | ✅ Healthy, syncing (fbece975a, type-19 enabled) |
| matcher | ✅ Healthy (port 6886) |
| T0 DeterministicFinality | ✅ Active |
| T2 HotStuff | ✅ ACTIVE — lag=0, round-timeout=1200ms, soak PASSED |
| CurGens | 3 — main + gen-0 + gen-1 |
| NextGens | 3 — all committed |

### Plugin JARs (`/opt/dcc/plugins/testnet/`)
| File | Source | Purpose |
|------|--------|---------|
| `ext.jar` (184KB) | `Ecosystem/matcher` @ `0767d246` | Registers BlockchainUpdates + DEXExtension; NodeBlockchainApiGrpcService |
| `grpc.jar` (4.6MB) | `Ecosystem/matcher` @ `0767d246` | DccBlockchainApiGrpc stubs + DEX gRPC + 14-field Block$Header |

Both JARs built from source, committed to `infra/plugins/testnet/`, deployed by `update-node-image.yml`.
**Classpath:** `lib/plugins/*:lib/*` — plugins first so extension `application.conf` registers both extensions. 14-field `Block$Header` in `grpc.jar` (matcher updated to match protobuf-schemas 1.6.3).

---

## Open Items Before Mainnet

### Item 1 — Remove `[HotStuffDiag]` debug log ✅ DONE

### Item 2 — BPS CommitToGeneration storage (type-19) ✅ DEPLOYED
Image `DecentralChain` @ `fbece975a` deployed to testnet. Running healthy, 237+ blocks synced.
- Added `txs_19` table (migration `20260628000000`)
- Fixed gRPC duplicate-block bug: dedup + upsert in `insert_blocks_or_microblocks` (`pg.rs`)
- Fixed `Loader.scala` root cause: tracks actual RocksDB key height to prevent re-seek into gaps
- BPS reset procedure: `fix-extension-height.yml` (drop-recreate DB + run migrations + start)

### Item 3 — `committedGeneratorsHash` in block headers ✅ DEPLOYED
Steps A+B+C implemented. Active in current node image. Old blocks return `None` (accepted, backward-compatible).

### Item 4 — T2 soak ✅ PASSED (2026-06-30)
All 4 failure scenarios verified at round-timeout=1200ms:
- **gen-0 down**: T2 maintained lag=0 (main+gen-1 quorum)
- **gen-1 down**: T2 maintained lag=0 (main+gen-0 quorum)
- **both down**: FairPoS continued (+3 blocks), T2 paused (no quorum)
- **both restored**: T2 self-healed to lag=0 within 3 min

### Item 5 — round-timeout-ms tuned ✅ DONE
5000ms → 1200ms (p99 ~1000ms + 20% margin). Deployed to main node and gen nodes via Flux.

---

## All Credentials — KeeWeb Backup Location

**File:** `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/KEEWEB_BACKUP.md`
**Check here FIRST for any credential issue.**

Key entries:
- **MATCHER_SEED mnemonic:** `tomorrow bleak cram rival inherit river genuine unknown guitar sister slot scale flip animal grit`
- **MATCHER_ACCOUNT_PASSWORD:** `+cUVQtSQTU+KRzP+Q1m+b5fgtHUSiNsWb4xAZp/ArVI=`
- **MATCHER_ADDRESS:** `31T5QNR7coVipCQuvcXyz4yjdqq3MR5K974`
- **POSTGRES_PASSWORD:** `NyaDHU8cuEesdXLnhGNZpMrgunevidu6gDR0QFQfoag=`
- **Main node wallet seed (Base58):** `PCK4Fstm8w9CcR1YmQdAPjUCBLUwyETzRci2Ypo93xXGgqVb2HJUh9Gq4R16`
- **gen-0 seed (mnemonic):** `pizza walk tourist speed dress wagon link property answer sell drum random loop high paper`
- **gen-1 seed (mnemonic):** `love earth taxi into alone reopen common blade curtain rookie result depart left ensure state`

---

## Infrastructure State

### Main Node (Newark 66.228.55.154)
- **Container:** `node-scala-testnet`, `--network host`, `--restart unless-stopped`
- **Image:** `ghcr.io/decentral-america/node-scala:node-scala-testnet-latest`
- **Config on VPS:** `/opt/dcc/config/node-testnet/dcc.conf`
  - `known-peers = ["172.105.64.89:6863", "172.105.64.89:6864"]` — main initiates to gen nodes
  - `enable-blacklisting = no`
  - `suspension-residence-time = 300s`
  - `hotstuff { enabled = true; round-timeout-ms = 1200 }`

### Gen Nodes (LKE Frankfurt 172.105.64.89)
- **Image:** `ghcr.io/decentral-america/node-scala:node-scala-testnet-latest`
- **Config:** `infra/clusters/testnet/apps/nodes.yaml` via Flux GitOps
  - `known-peers = ["66.228.55.154:6868"]`
  - `enable-blacklisting = no`
  - `suspension-residence-time = 300s`
  - `hotstuff { enabled = true; round-timeout-ms = 1200 }`
- **gen-0 P2P port:** 6863, **REST port:** 6869
- **gen-1 P2P port:** 6864, **REST port:** 6870 (NOT 6869)

### blockchain-postgres-sync
- **Database:** `bps_testnet` on VPS postgres
- **Image:** `ghcr.io/decentral-america/blockchain-postgres-sync:fbece975a0074868d20dc476324a0fa0587f2e70`
- **Type-19 fix:** ✅ deployed — `txs_19` table, dedup upsert fix, Loader.scala root-cause fix

### Matcher
- **Config dir (CORRECT):** `/opt/dcc/config/matcher-testnet/local.conf`
  ```hocon
  dcc.dex {
    address-scheme-character = "!"
    account-storage.type = in-mem
  }
  ```
- **CRITICAL:** The compose mounts `/opt/dcc/config/matcher-testnet → /var/lib/decentralchain-dex/config:ro`. Writing `local.conf` to the data dir (`/opt/dcc/data/matcher-testnet/config/`) is silently ignored.

---

## Automation

| Automation | Schedule | Workflow | Purpose |
|-----------|----------|----------|---------|
| Auto-commit generators | Every 35 min | `auto-commit-generators.yml` | Keep all 3 generators committed for next period |

---

## Operations Reference

### Deploy new node image (NO chain wipe)
```bash
# Use this for all image updates and config changes
gh workflow run update-node-image.yml --repo Decentral-America/infra
```

### Deploy updated dcc.conf (NO chain wipe)
```bash
# Copies dcc.conf from repo to VPS and restarts node
gh workflow run deploy-node-config.yml --repo Decentral-America/infra
```

### Commit generators for next period
```bash
gh workflow run auto-commit-generators.yml --repo Decentral-America/infra
# OR the manual workflow:
gh workflow run commit-generators-hotstuff.yml --repo Decentral-America/infra
```

### Fix BPS after node restart
```bash
# BPS crashes after node restart — just restart it:
docker start blockchain-postgres-sync-testnet
# Automated via matcher-fix-app-conf.yml which also handles BPS

# If BPS fails with wrong height after chain reset, truncate tables:
sudo -u postgres psql -d bps_testnet -c "
DO \$\$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public'
  LOOP EXECUTE 'TRUNCATE '||r.tablename||' CASCADE'; END LOOP;
END; \$\$;"
docker start blockchain-postgres-sync-testnet
```

### Fix matcher
```bash
sudo tee /opt/dcc/config/matcher-testnet/local.conf > /dev/null <<EOF
dcc.dex {
  address-scheme-character = "!"
  account-storage.type = in-mem
}
EOF
docker restart matcher-testnet
```

### Check T2 HotStuff finality
```bash
curl http://localhost:6869/blockchain/finality
# Expected healthy state:
#   hotStuffFinalizedHeight lag < 10 blocks — T2 at tip
#   hotStuffFinalizedHeight lag > 50 blocks for 10 min — ALERT: T2 stalled, check generators
#   finalizedHeight (T0) lag < 200 blocks — normal (T0 advances in batches)
#   finalizedHeight (T0) lag > 200 blocks for 30 min — ALERT: T0 stalled, generators missing
# Quorum: 2/3 of ~80M total. Each node ~26.7M. Any 2-of-3 = 53.4M > 53.3M threshold.
# round-timeout-ms = 1200 (p99 ~1000ms + 20%)
```

### Verify generators committed for current period
```bash
gh workflow run peer-check.yml --repo Decentral-America/infra
# Look for CurGens >= 2 AND NextGens >= 2
# NextGens < 2 means T2 WILL stop after current period ends (every ~50 min)
# auto-commit-generators.yml runs every 17-35 min (dual schedule for redundancy)

# Emergency manual commit:
gh workflow run auto-commit-generators.yml --repo Decentral-America/infra
```

### Deploy monitoring config (Prometheus alerts)
```bash
gh workflow run deploy-monitoring.yml --repo Decentral-America/infra
# Alerts configured in monitoring/alerts.yml:
#   BlockProductionStalled — no block in 5 min (CRITICAL)
#   T2FinalizationStalled — lag >50 blocks for 10 min (HIGH)
#   T2GeneratorsNotCommitted — NextGens <2 for 15 min (HIGH)
#   NodePeersLow — <1 peer for 5 min (HIGH)
#   T0FinalizationStalled — T0 lag >200 blocks for 30 min (MEDIUM)
```

---

## What NOT to Do

- ❌ **`restart-host-network.yml` WIPES chain data** — use `update-node-image.yml` instead for image updates
- ❌ **`peer-watchdog.yml` SIGKILLs the node** — it's an emergency tool, not routine automation. Causes BPS crash and chain reset
- ❌ **Do not use bridge mode for main node** — causes TCP connectivity failure from LKE
- ❌ **Do not write matcher `local.conf` to `/opt/dcc/data/matcher-testnet/config/`** — shadowed by config mount
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit (1200/hr)
- ❌ **Do not check credentials from memory** — always read KEEWEB_BACKUP.md first

---

## ⚠️ Security: API Keys in Git History
Node REST API keys are in git history of `infra` repo (commits Jun 25-27). Keys must be **rotated before mainnet**:
1. Generate new API keys
2. Hash them: `sha256(key)` base58-encoded
3. Update `api-key-hash` in `dcc.conf` for each node via `deploy-node-config.yml`
4. Update GitHub Actions secrets: `MAIN_NODE_REST_API_KEY`, `GEN_0_NODE_REST_API_KEY`, `GEN_1_NODE_REST_API_KEY`, `VAL_0_NODE_REST_API_KEY`

---

## Bugs Fixed (2026-06-27)

### Bug 1: RC#2 — Blacklist cycle
`enable-blacklisting = no` in all node configs.

### Bug 2: PeerKey race condition
Both sides in `known-peers` → simultaneous connections → duplicate detection → channel lost → no sync.
**Fix:** `known-peers = []` on main node. Gen nodes initiate only. *(Note: main node now has gen IPs added back for T2 — PeerKey risk mitigated by `enable-blacklisting = no` + 300s suspension.)*

### Bug 3: MessageCodec.isNewMsgsSupported (commit d0b24c55)
Version `(0,0,0)` treated as old-msgs → `GetSignatures` dropped silently.

### Bug 4: BlockIdSeqSpec.maxLength (commit cc88d9417b)
13004 too small for 32-byte hash IDs. Fixed to 99,004.

### Bug 5: InvalidStateHash after CommitToGeneration (commit e6f9e76cc8)
`fromBlockTraced` used `maybePrevBlock.transactionData × 3/5` for carry fee in pre-sponsorship path. Miner used `blockchain.carryFee(None)`. Diverged when competitor-block commit reset `rocksdb.carryFee = 0`. **Fix:** Unified to `blockchain.carryFee(None)` in both paths.

### Bug 6: HotStuff engine never activated (commits a153d6f142, fb0bfbe)
Two root causes:
1. `dcc.conf` is primary config — `hotstuff.enabled` defaults to `false` unless explicitly set there. **Fix:** Add `hotstuff { enabled = true; round-timeout-ms = 1200 }` to `dcc.conf` on VPS.
2. Miner's `appendTask` never fired `BlockApplied` to HotStuff engine — only P2P-received blocks did. **Fix:** `Miner.setHotStuffEngine()` wired from `Application.scala`.

---

## T2 HotStuff Reference

### REST API ports per node
| Node | P2P | REST | API key (KEEWEB) |
|------|-----|------|-----------------|
| main node | 6868 | 6869 | MAIN_NODE_REST_API_KEY |
| gen-0 (LKE) | 6863 | 6869 | GEN_0_NODE_REST_API_KEY |
| gen-1 (LKE) | 6864 | **6870** | GEN_1_NODE_REST_API_KEY |
| val-0 (LKE) | 6865 | 6869 | VAL_0_NODE_REST_API_KEY |

### CommitToGeneration via node REST API
```bash
# Each node signs for itself — BLS auto-derived, period start auto-filled
curl -X POST http://localhost:6869/transactions/sign \
  -H "X-API-Key: KEY" -H "Content-Type: application/json" \
  -d '{"type":19,"sender":"ADDRESS"}'
# Then broadcast the result to /transactions/broadcast
```
- Gen-0: `kubectl port-forward dcc-gen-0-0 -n dcc 16869:6869`
- Gen-1: `kubectl port-forward dcc-gen-1-0 -n dcc 16870:6870` (port 6870 inside pod)

### Key numbers
- Generation period: 100 blocks ≈ 50 minutes
- Quorum threshold: 2/3 of total committed balance
- Main: ~26.7M DCC, gen-0: ~26.7M DCC, gen-1: ~26.7M DCC (total ~80M, equal share)
- Any 2-of-3 = ~53.4M > 53.3M threshold → QC possible with any majority
- Round timeout: 5000ms
