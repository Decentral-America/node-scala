# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-06-27. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-06-27 — FULLY OPERATIONAL)

| Item | Status |
|------|--------|
| Chain height | 1625+, advancing ~30s/block |
| Main node (Newark 66.228.55.154) | ✅ Running, healthy, host network mode |
| gen-0 (LKE 172.105.64.89:6863) | ✅ Mining blocks on canonical chain |
| gen-1 (LKE 172.105.64.89:6864) | ✅ Mining blocks on canonical chain |
| val-0 (LKE 172.105.64.89:6865) | ✅ Connected, synced |
| blockchain-postgres-sync | ✅ Running, healthy |
| matcher | ✅ Running, healthy — public key `2eEUvypDSivnzPiLrbYEW39SM8yMZ1aq4eJuiKfs4sEY` |
| T2 HotStuff | 🔄 CurGens: 3 committed (auto-renewed every 35min via cron). Awaiting QC formation — round-timeout increased to 5000ms, suspension-residence-time 300s |

---

## All Credentials — KeeWeb Backup Location

**File:** `/Users/jourlez/Documents/Code/Blockchain/Ecosystem/KEEWEB_BACKUP.md`

Key entries:
- **MATCHER_SEED mnemonic:** `tomorrow bleak cram rival inherit river genuine unknown guitar sister slot scale flip animal grit`
- **MATCHER_ACCOUNT_PASSWORD:** `+cUVQtSQTU+KRzP+Q1m+b5fgtHUSiNsWb4xAZp/ArVI=`
- **MATCHER_ADDRESS:** `31T5QNR7coVipCQuvcXyz4yjdqq3MR5K974`
- **POSTGRES_PASSWORD:** `NyaDHU8cuEesdXLnhGNZpMrgunevidu6gDR0QFQfoag=`
- **Main node wallet seed (Base58):** `PCK4Fstm8w9CcR1YmQdAPjUCBLUwyETzRci2Ypo93xXGgqVb2HJUh9Gq4R16`
- **gen-0 seed (mnemonic):** `pizza walk tourist speed dress wagon link property answer sell drum random loop high paper`
- **gen-1 seed (mnemonic):** `love earth taxi into alone reopen common blade curtain rookie result depart left ensure state`

---

## 4 Code Bugs Fixed (2026-06-27)

### Bug 1: RC#2 — Blacklist cycle (config fix)
`enable-blacklisting = no` in all node configs. No rebuild needed.

### Bug 2: PeerKey race condition (config fix)
Both sides had each other in `known-peers` → simultaneous connections → duplicate detection → `allChannels` lost gen-0's channel → score broadcasts never reached gen-0 → 5-min idle timeout.
**Fix:** `known-peers = []` on main node. Gen nodes initiate only.

### Bug 3: MessageCodec.isNewMsgsSupported (code fix — commit d0b24c55)
`isNewMsgsSupported` returned `false` for DCC version `(0,0,0)` → `GetSignatures` silently dropped → 5-min sync timeout → channel closed.
**Fix:** `v1 == 0` treated as new-msgs-supported.

### Bug 4: BlockIdSeqSpec.maxLength too small (code fix — commit cc88d9417b)
`maxLength = 13004` sized for 200×64-byte signatures. With 32-byte hash IDs, even moderate chains exceeded limit → `"BlockIds message length N is invalid"` → instant `blacklistAndClose` after every handshake.
**Fix:** `maxLength = 4 + 3000 × 33 = 99,004`

---

## Infrastructure State

### Main Node (Newark 66.228.55.154)
- **Container:** `node-scala-testnet` via docker-compose
- **Network mode:** `host` (NOT bridge — bridge mode caused TCP connectivity failure from LKE)
- **Image:** `ghcr.io/decentral-america/node-scala:node-scala-testnet-latest` → `cc88d9417b`
- **Config:** `/opt/dcc/config/node-testnet/dcc.conf`
  - `known-peers = []` — gen nodes initiate connections only
  - `enable-blacklisting = no`
  - `suspension-residence-time = 10s`
- **Chain data:** fresh from genesis after resets today

### Gen Nodes (LKE Frankfurt 172.105.64.89)
- **Image:** `ghcr.io/decentral-america/node-scala:testnet-latest` → same `cc88d9417b`
- **Config:** `dcc-nodes.yaml` via Flux GitOps
  - `known-peers = ["66.228.55.154:6868"]` — only main node
  - `enable-blacklisting = no`
  - `suspension-residence-time = 10s`
- **Chain data:** synced from main node

### blockchain-postgres-sync
- **Database:** `bps_testnet` (PostgreSQL local on VPS)
- **Status:** Running healthy after tables were truncated via `sudo -u postgres psql`
- **Starting height:** 1 (fresh after chain reset)

### Matcher
- **Data dir:** `/opt/dcc/data/matcher-testnet/` on VPS host → `/var/lib/decentralchain-dex/` in container
- **Config dir (CORRECT):** `/opt/dcc/config/matcher-testnet/` → `/var/lib/decentralchain-dex/config/` (READ-ONLY bind mount — shadows data volume's `config/` subdir)
  - This is the mount that `dex.conf` reads via `include`. Write `local.conf` HERE, NOT in the data dir.
- **Config:** `/opt/dcc/config/matcher-testnet/local.conf`
  ```hocon
  dcc.dex {
    address-scheme-character = "!"
    account-storage.type = in-mem
  }
  ```
- **Account storage:** `in-mem` — no `account.dat` file needed. Seed comes from `MATCHER_ACCOUNT_SEED` env var in `/opt/dcc/secrets/testnet.env`
- **Port:** 6886 (REST API, health check on `:1AE6` in `/proc/net/tcp`)
- **Public key:** `2eEUvypDSivnzPiLrbYEW39SM8yMZ1aq4eJuiKfs4sEY`
- **CRITICAL — Volume shadowing:** The compose mounts BOTH:
  - `/opt/dcc/data/matcher-testnet → /var/lib/decentralchain-dex` (data)
  - `/opt/dcc/config/matcher-testnet → /var/lib/decentralchain-dex/config:ro` (config, shadows data/config/)
  - Writing `local.conf` to data dir config is silently ignored by the container.

---

## Automation

| Automation | Schedule | Workflow | Purpose |
|-----------|----------|----------|---------|
| Commit-to-generation | Every 35 min | `commit-to-generation.yml` | Keep generators committed |
| Peer reconnection watchdog | Every 5 min | `peer-watchdog.yml` | Auto-reconnect on 0 peers |

---

## Operations Reference

### Re-deploy main node after chain reset
```bash
# 1. Apply config fixes
gh workflow run mainnode-ops.yml --field operation=clear-known-peers-and-restart

# 2. Force pull new image + wipe chain
gh workflow run restart-host-network.yml
# (OR force-recreate-node.yml for bridge→host switch)

# 3. Resync gen nodes
gh workflow run resync-gen-nodes.yml --field confirm=WIPE
```

### Fix blockchain-postgres-sync after chain reset
```bash
# BPS stores last height in postgres. After reset, truncate tables:
ssh deploy@66.228.55.154 'sudo -u postgres psql -d bps_testnet -c "
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='"'"'public'"'"'
  LOOP EXECUTE '"'"'TRUNCATE '"'"'||r.tablename||'"'"' CASCADE'"'"'; END LOOP;
END; $$;"'
docker restart blockchain-postgres-sync-testnet
```

### Fix matcher (wrong config dir, or crashing with account.dat error)
```bash
# The compose mounts /opt/dcc/config/matcher-testnet → /var/lib/decentralchain-dex/config (read-only).
# Write local.conf to the CONFIG dir (not the DATA dir):
ssh deploy@66.228.55.154 'sudo tee /opt/dcc/config/matcher-testnet/local.conf > /dev/null <<EOF
dcc.dex {
  address-scheme-character = "!"
  account-storage.type = in-mem
}
EOF'
docker restart matcher-testnet

# Verify via workflow:
gh workflow run matcher-fix-app-conf.yml --repo Decentral-America/infra
```

### Fix BPS after node restart (start height exceeds chain height)
```bash
# BPS stores last synced height in postgres. After node restart, BPS may
# try to subscribe from a height higher than the node currently reports.
# Simply restart BPS (it will re-read the chain and catch up):
docker start blockchain-postgres-sync-testnet
# OR use the workflow:
gh workflow run matcher-fix-app-conf.yml --repo Decentral-America/infra
# (workflow now also handles BPS restart)

# If BPS still fails after restart (wrong height from chain reset):
ssh deploy@66.228.55.154 'sudo -u postgres psql -d bps_testnet -c "
DO \$\$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='"'"'public'"'"'
  LOOP EXECUTE '"'"'TRUNCATE '"'"'||r.tablename||'"'"' CASCADE'"'"'; END LOOP;
END; \$\$;"'
docker restart blockchain-postgres-sync-testnet
```

---

## What NOT to Do

- ❌ **Do not disable miners** — non-mining nodes don't sync in DCC
- ❌ **Do not use bridge mode for main node Docker container** — causes TCP connectivity failure from LKE
- ❌ **Do not write matcher local.conf to `/opt/dcc/data/matcher-testnet/config/`** — shadowed by the config volume mount; container never sees it
- ✅ **Write matcher local.conf to `/opt/dcc/config/matcher-testnet/local.conf`** — this is the real config location

---

## Bug 5: InvalidStateHash on mining after CommitToGeneration (fixed 2026-06-27, commit e6f9e76cc8)

**Symptom:** `Error mining block by ADDRESS: InvalidStateHash(Some(...), Some(...))` in node logs immediately after CommitToGenerationTransaction microblocks are appended.

**Root cause:** `BlockDiffer.fromBlockTraced` at `stateHeight < sponsorshipHeight` (< 2700) computed `feeFromPreviousBlock` by summing `maybePrevBlock.transactionData × 3/5`. The miner's `createInitialBlockSnapshot` used `blockchain.carryFee(Some(reference))`. These diverge when `rocksdb.carryFee = 0` but `rocksdb.lastBlock.transactionData` still has CommitToGen fees — this happens when a competitor-block commit path resets carryFee to 0 while the stored liquid block still has TXs. The resulting balance in `initSnapshot` differed by exactly `3/5 × (sum of CommitToGen fees)`.

**Fix:** `node/src/main/scala/com/decentralchain/state/diffs/BlockDiffer.scala` — use `blockchain.carryFee(None)` in the pre-sponsorship `else if stateHeight > ngHeight` branch, matching `createInitialBlockSnapshot`'s source.

**Diagnosis method:** Add WARN log in `computeInitialStateHash` and `packTransactionsForKeyBlock` printing `initBalances` → compare miner vs BlockDiffer balance at same height.

---

## T2 HotStuff Activation

### How it works
- Transaction type **19** (`CommitToGenerationTransaction`) — each generator commits its BLS key
- The node's `/transactions/sign` endpoint auto-derives BLS from Ed25519 private key, auto-computes Proof of Possession
- Committed generators become active in the NEXT generation period (every 100 blocks)
- Once active, HotStuff runs 3-round protocol (Prepare → PreCommit → Commit QC) per block
- `GET /blockchain/finality` → `hotStuffFinalizedHeight` advances as QCs form

### REST API ports per node
| Node | REST API port | API key from KEEWEB |
|------|--------------|---------------------|
| main node (Newark) | 6869 | MAIN_NODE_REST_API_KEY |
| gen-0 (LKE) | 6869 | GEN_0_NODE_REST_API_KEY |
| gen-1 (LKE) | **6870** | GEN_1_NODE_REST_API_KEY |
| val-0 (LKE) | 6869 | VAL_0_NODE_REST_API_KEY |

### Commit workflow (run once per 100-block period)
```bash
gh workflow run commit-generators-hotstuff.yml --repo Decentral-America/infra
```
- Signs via each node's own `/transactions/sign` (BLS auto-derived)
- Gen-0 accessed via `kubectl port-forward dcc-gen-0-0 -n dcc 16869:6869`
- Gen-1 accessed via `kubectl port-forward dcc-gen-1-0 -n dcc 16870:6870` (port 6870!)
- "already committed" error = normal if already committed this period

### Verify T2 is active
```bash
curl http://localhost:6869/blockchain/finality
# hotStuffFinalizedHeight should be non-null once generators are in currentGenerators
```
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit
- ❌ **Do not check KEEWEB_BACKUP.md last** — check it FIRST for any credential issue
