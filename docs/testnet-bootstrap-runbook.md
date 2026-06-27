# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-06-27. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-06-27)

| Item | Status |
|------|--------|
| Chain height | ~1400+, advancing ~30s/block |
| Main node (Newark 66.228.55.154) | ✅ Running, healthy, host network mode |
| gen-0 (LKE 172.105.64.89:6863) | ✅ Mining blocks on canonical chain |
| gen-1 (LKE 172.105.64.89:6864) | ✅ Mining blocks on canonical chain |
| val-0 (LKE 172.105.64.89:6865) | ✅ Connected, synced |
| blockchain-postgres-sync | ✅ Running, syncing from height 1 |
| matcher | 🔄 Being fixed — account.dat being recreated from KeeWeb seed |
| T2 HotStuff | ❌ Not activated — requires committed validators with BLS keys |

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
- **account.dat:** Recreating from KeeWeb seed
- **Credentials:** See KeeWeb section above
- **Config:** `config/application.conf` in data dir with `address-scheme-character = "!"` and `type = encrypted-file`

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

### Fix matcher after account.dat deletion
```bash
# Run with real seed from KeeWeb:
gh workflow run matcher-create-account.yml
# Seed: "tomorrow bleak cram rival inherit river genuine unknown guitar sister slot scale flip animal grit"
# Password: "+cUVQtSQTU+KRzP+Q1m+b5fgtHUSiNsWb4xAZp/ArVI="
```

---

## What NOT to Do

- ❌ **Do not disable miners** — non-mining nodes don't sync in DCC
- ❌ **Do not use bridge mode for main node Docker container** — causes TCP connectivity failure from LKE
- ❌ **Do not delete `/opt/dcc/data/matcher-testnet/`** — destroys account.dat (recreate from KeeWeb seed if deleted)
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit
- ❌ **Do not check KEEWEB_BACKUP.md last** — check it FIRST for any credential issue
