# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-06-29. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-06-29)

| Item | Status |
|------|--------|
| Chain height | 1 — CI rebuild in progress (see Active Incident below) |
| Main node (Newark 66.228.55.154) | ⚠️ Healthy but not mining — awaiting new image from CI |
| gen-0 (LKE 172.105.64.89:6863) | ⚠️ Diverged chain — needs resync after new image deploys |
| gen-1 (LKE 172.105.64.89:6864) | ⚠️ Old chain — needs resync after new image deploys |
| val-0 (LKE 172.105.64.89:6865) | Connected |
| blockchain-postgres-sync | ❌ Crashing — start height > chain height (fixable after chain starts) |
| matcher | ⚠️ Running but chain not advancing |
| T0 DeterministicFinality | ⏸ Paused (chain at genesis) |
| T2 HotStuff | ⏸ Paused (chain at genesis) |

### Active Incident (2026-06-29)
**Root cause:** `committed_generators_hash` field 14 was added to monorepo `dcc/block.proto` (commit c38dd3c). CI built node-scala with 14-field `Block.Header`. Runtime JAR is 1.6.2 (13 fields). Result: `NoSuchMethodError: Block$Header.copy$default$14()` on first block forge → miner crashes silently → chain stuck at genesis forever.

**Fix applied:**
1. Reverted field 14 from monorepo `dcc/block.proto` (DecentralChain commit b55392351)
2. Pushed to node-scala to trigger CI rebuild with 13-field proto
3. After CI: run `update-node-image.yml` → `resync-gen-nodes.yml` → `auto-commit-generators.yml`

**Recovery sequence once CI passes:**
```bash
gh workflow run update-node-image.yml --repo Decentral-America/infra
# wait ~30s for node to restart and start mining
gh workflow run resync-gen-nodes.yml --repo Decentral-America/infra -f network=testnet -f confirm=WIPE
# wait 5 min for gen nodes to sync
gh workflow run auto-commit-generators.yml --repo Decentral-America/infra
```

---

## Open Items Before Mainnet

### Item 1 — Remove `[HotStuffDiag]` debug log
**File:** `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEngine.scala:61`
**What:** `log.info(s"[HotStuffDiag] onBlockApplied...")` fires on every block. Debug artifact from T2 diagnosis.
**Fix:** Delete one line. CI rebuild → `update-node-image.yml`.

### Item 2 — BPS CommitToGeneration storage (type-19 miscategorized)
**Files:**
- `DecentralChain/apps/blockchain-postgres-sync/src/lib/consumer/mod.rs:525` — CommitToGeneration mapped to `(1i32, ...)` i.e. stored as type=1 (Genesis placeholder). **Wrong.**
- `convert.rs:779` — `CommitToGenerationSkip` — no subtype table written.

**What:** Type-19 TXs ARE in the `txs` table but with wrong `tx_type = 1`. No `txs_19` subtype table with validator-specific fields (endorser_public_key, generation_period_start).
**Fix:**
1. `mod.rs:525` — change `(1i32, ...)` → `(19i32, ...)`
2. `convert.rs` — implement `Tx19` struct and `ConvertedTx::CommitToGeneration(Tx19{uid, sender, sender_public_key, endorser_public_key, generation_period_start, fee, height, block_uid, status})`
3. New Diesel migration for `txs_19` table
4. BPS rebuild → `deploy-bps.yml --network testnet`
5. BPS must resync from height 1 to backfill correct data

### Item 3 — `committedGeneratorsHash` in block headers (hard protocol fix)
**Context:** `mainnet-upgrade-validation.md` documents Steps A/B/C. **Step A is done** (Bug 5, commit d352f5fb9f + 44b93a06c5 — excluded from per-TX and block-level state hash). Steps B and C are not implemented.

**What is missing:** At each generation period boundary block, the final committed validator set for the next period is NOT cryptographically hashed into the block header. This means nodes cannot detect validator set divergence between competing chains via the state hash. The workaround (excluding from state hash) prevents crashes but removes the security guarantee.

**Fix — Step B:** Add `committedGeneratorsHash: Option[ByteStr]` to `BlockHeader` case class (`Block.scala`). At period boundary blocks (`height % periodLength == 0`), compute: `Blake2b256(sortBy(addr.toString)(committedGenerators(nextPeriod)).flatMap { case (addr, blsPk) => addr.bytes ++ blsPk.arr })`. Requires: proto update, `PBBlocks.scala` round-trip, block version bump.

**Fix — Step C:** In `BlockDiffer.fromBlockTraced`, validate `block.header.committedGeneratorsHash` at period boundaries.

**Prerequisite:** Locate the `.proto` source for `Block.Header` (not the generated Scala in `target/`). The three found proto files (`database.proto`, `api.proto`, `transactions_api.proto`) don't define `Block.Header` — it comes from an upstream dependency. Must identify before implementing.

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
  - `hotstuff { enabled = true; round-timeout-ms = 5000 }`

### Gen Nodes (LKE Frankfurt 172.105.64.89)
- **Image:** `ghcr.io/decentral-america/node-scala:node-scala-testnet-latest`
- **Config:** `infra/clusters/testnet/apps/dcc-nodes.yaml` via Flux GitOps
  - `known-peers = ["66.228.55.154:6868"]`
  - `enable-blacklisting = no`
  - `suspension-residence-time = 300s`
  - `hotstuff { enabled = true; round-timeout-ms = 5000 }`
- **gen-0 P2P port:** 6863, **REST port:** 6869
- **gen-1 P2P port:** 6864, **REST port:** 6870 (NOT 6869)

### blockchain-postgres-sync
- **Database:** `bps_testnet` on VPS postgres
- **Image:** `ghcr.io/decentral-america/blockchain-postgres-sync:testnet-latest`
- **Known issue:** type-19 TXs stored as type=1 (see Open Items)

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
# hotStuffFinalizedHeight advances when gen nodes are connected during 5s round window
# CurGens must be >= 1 for rounds to start
# Quorum: main(40M) + gen-0(20M) = 60M > 53M threshold (2/3 of 80M total)
```

### Verify generators committed for current period
```bash
gh workflow run peer-check.yml --repo Decentral-America/infra
# Look for CurGens >= 2 and NextGens >= 2
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
1. `dcc.conf` is primary config — `hotstuff.enabled` defaults to `false` unless explicitly set there. **Fix:** Add `hotstuff { enabled = true; round-timeout-ms = 5000 }` to `dcc.conf` on VPS.
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
- Main: ~40M DCC, gen-0: ~20M DCC, gen-1: ~20M DCC (total ~80M)
- Main + gen-0 = 60M > 53.3M → sufficient for QC
- Round timeout: 5000ms
