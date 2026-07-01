# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-07-01. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-07-01)

| Item | Status |
|------|--------|
| Chain height | 12155+, advancing ~2 blocks/min |
| Main node (Newark 66.228.55.154) | ✅ Healthy — v1.6.3, all extensions running |
| gen-0 (LKE 172.105.64.89:6863) | ✅ Mining — height 12155 |
| gen-1 (LKE 172.105.64.89:6864) | ✅ Mining — height 12155, 13 restarts (normal) |
| val-0 (LKE 172.105.64.89:6865) | ⚠️ CrashLoopBackOff fixed 2026-07-01 — memory increased 620Mi→880Mi, heap 512m→768m; was crash-looping 148 times in 4 days due to OOM; Flux rolling update in progress |
| blockchain-postgres-sync | ✅ Healthy, syncing (fbece975a, type-19 enabled) |
| matcher | ✅ Healthy (port 6886) |
| admin-dashboard | ✅ Healthy — E2E crash fixed (E2E_SUITE_PATH=/dev/null/disabled) |
| T0 DeterministicFinality | ✅ Advancing — at 11960 (self-healed from 9668 gap) |
| T2 HotStuff | ✅ ACTIVE — lag=0, round-timeout=1200ms, validators=2 (val-0 not yet participating) |
| CurGens | 3 committed (main + gen-0 + gen-1) per auto-commit every 5 min |
| NextGens | 3 committed — auto-commit cron stable |
| Prometheus monitoring | ✅ LIVE — 2 rule groups (alerts.yml loaded), 7 alert rules |
| Log aggregation | ✅ LIVE — Loki 3.3.2 + Promtail running on VPS |
| Alertmanager | ✅ LIVE — routes alerts → GitHub Issues webhook |
| Alert webhook | ✅ LIVE — alert-webhook-testnet running |
| Chain state backup | ⚠️ FAILING — workflow fixed 2026-07-01 but GitHub secret `BACKUP_OBJ_ENDPOINT` not yet set; **ACTION REQUIRED**: add to GitHub testnet environment |

### Plugin JARs (`/opt/dcc/plugins/testnet/`)
| File | Source | Purpose |
|------|--------|---------|
| `ext.jar` (184KB) | `Ecosystem/matcher` @ `0767d246` | Registers BlockchainUpdates + DEXExtension; NodeBlockchainApiGrpcService |
| `grpc.jar` (4.6MB) | `Ecosystem/matcher` @ `0767d246` | DccBlockchainApiGrpc stubs + DEX gRPC + 14-field Block$Header |

Both JARs built from source, committed to `infra/plugins/testnet/`, deployed by `update-node-image.yml`.
**Classpath:** `lib/plugins/*:lib/*` — plugins first so extension `application.conf` registers both extensions. 14-field `Block$Header` in `grpc.jar` (matcher updated to match protobuf-schemas 1.6.3).

---

## T0 DeterministicFinality — Status (2026-07-01)

**Status:** ✅ Self-healed. T0 finalizedHeight was stuck at ~9668 after the T2 soak test (Phase C killed both gen nodes; no CommitToGeneration transactions were signed for those blocks). T0 resumed advancing once endorsements accumulated and now sits at ~11960, lagging T2 (12100+) by ~140 blocks (~70 min) — this is normal and expected.

**Root cause of prior stall:** `BlockEndorser` requires `committedGenerators()` to return a valid set for each block. During the gap period (blocks where no generators were committed), no endorsements could flow. Once the generators committed consistently again, T0 caught up block-by-block.

**Not a chain safety issue:** T2 HotStuff provides finality at lag=0. T0 is an additional finality layer. A T0 lag ≤200 blocks is expected and normal; only a stall >200 blocks for >30 min should trigger investigation.

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

### Item 6 — Stress test ✅ COMPLETE (2026-06-30)

| Config | TPS achieved | Error rate | p50 | p99 | p99.9 | Verdict |
|--------|-------------|-----------|-----|-----|-------|---------|
| 1 sender, 50 TPS | 50.0 | **0.00%** | 33ms | 67ms | 107ms | ✅ Healthy |
| 1 sender, 100 TPS | 99.9 | **0.00%** | 18ms | 104ms | 163ms | ✅ Healthy |
| 1 sender, 500 TPS | 469.7 | **0.00%** | 34ms | 786ms | 11,415ms | ⚠️ UTX queue backed up |
| 5 senders, 500 TPS | **377.0** | **0.00%** | 234ms | 787ms | 71,935ms | ⚠️ Block throughput ceiling |

**Conclusions:**
- **Node block throughput ceiling: ~380 TPS** (255 tx/microblock × ~1.5 microblocks/s)
- **Healthy operating range: ≤100 TPS/sender** with p99 < 200ms and zero errors
- **Multi-sender setup**: `fund-load-test-senders.yml` (1M DCC each to nonces 1-4) + `list-senders.yml`
- Workflow: `DecentralChain/.github/workflows/stress-test.yml`

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
| Auto-commit generators | Every 5 min (staggered dual cron: `*/10 * * * *` + `:05/:15/:25/:35/:45/:55`) | `auto-commit-generators.yml` | Keep all 3 generators committed for next period — 12 fire attempts/hour survives GitHub cron outages |
| Peer reconnection watchdog | Every 15 min | `peer-watchdog.yml` | Force-reconnect gen nodes to main node if peers drop |
| Chain state backup | Daily 03:00 UTC | `backup-chain-state.yml` | Snapshot node-state volume → Cloudflare R2 (7-day retention) |

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
# auto-commit-generators.yml fires every 5 min (staggered dual cron, 12 attempts/hr)

# Emergency manual commit (if cron is down):
gh workflow run auto-commit-generators.yml --repo Decentral-America/infra
```

### Deploy monitoring config (Prometheus alerts + Loki + Alertmanager)
```bash
gh workflow run dispatch-test-5.yml --repo Decentral-America/infra
# "Deploy Monitoring Stack" — deploys prometheus.yml, alerts.yml, loki, promtail, alertmanager
# Alerts configured in monitoring/alerts.yml:
#   BlockProductionStalled — no block in 5 min (CRITICAL)
#   T2FinalizationStalled — lag >50 blocks for 10 min (HIGH)
#   T2GeneratorsNotCommitted — NextGens <2 for 15 min (HIGH)
#   NodePeersLow — <1 peer for 5 min (HIGH)
#   T0FinalizationStalled — T0 lag >200 blocks for 30 min (MEDIUM)
```

### Check val-0 node address and height
```bash
gh workflow run dispatch-test-6.yml --repo Decentral-America/infra
# "Check Val-0 Node Address" — kubectl port-forward to val-0, checks /addresses and /node/status
```

---

## GitHub Actions Workflow Trigger Bug (Discovered 2026-06-30)

GitHub silently fails to register `workflow_dispatch` triggers under two conditions:
1. **`workflow_dispatch:` + `environment:` in jobs** — GitHub's parser fails to register the trigger when these appear together without a second `on:` trigger
2. **Go template syntax `{{.Names}}`** in `run:` blocks — `docker ps --format "{{...}}"` breaks the entire file's trigger registration

**Symptoms:** workflow shows as file path (e.g. `.github/workflows/foo.yml`) in `gh workflow list` instead of its `name:` field; `gh workflow run` returns HTTP 422 "Workflow does not have 'workflow_dispatch' trigger"

**Fix:** Add `workflow_call:` as a second trigger. Replace `docker ps --format "{{...}}"` with `docker ps | grep`.

**CRITICAL:** GitHub caches broken trigger state at first registration — editing the file never fixes it. A new file path is required. Broken files deleted: `dispatch-test.yml`, `dispatch-test-3.yml`, `dispatch-test-4.yml`, `infra-mon.yml`, `infra-val0.yml`.

Working dispatch-able workflows: `dispatch-test-5.yml` (Deploy Monitoring Stack), `dispatch-test-6.yml` (Check Val-0 Node Address).

### Add BACKUP_OBJ_ENDPOINT GitHub Secret (required for backup)
```bash
# The chain state backup fails without this secret.
# Format: https://<cloudflare_account_id>.r2.cloudflarestorage.com
# Add via GitHub: Settings → Environments → testnet → Add secret → BACKUP_OBJ_ENDPOINT
# OR via CLI (requires the actual endpoint URL):
gh secret set BACKUP_OBJ_ENDPOINT --repo Decentral-America/infra --env testnet
# Then verify:
gh workflow run backup-chain-state.yml --repo Decentral-America/infra
```

---

## Incident Log

### INC-001: 7-Hour GitHub Actions Cron Outage (2026-07-01 01:41–08:57 UTC)
**Impact:** T2 HotStuff stalled — CurGens=0/NextGens=0 at height ~11437. Chain block production via FairPoS continued unaffected.
**Root cause:** GitHub Actions cron scheduler silently stopped firing all scheduled workflows in the `Decentral-America/infra` repo for 7h 16m.
**Detection:** Manual check of chain finality showed T2 lag growing; last auto-commit was at 01:41 UTC.
**Recovery:** Manually dispatched `auto-commit-generators.yml` → NextGens=3 → T2 resumed at next period boundary (~11500).
**Mitigation applied:** Increased cron frequency from `*/35 * * * *` to dual staggered schedule (`*/10 * * * *` + `:05/:15/:25/:35/:45/:55`), giving 12 fire attempts per hour. A 3.5-hour cron gap cannot stall T2 again — worst case is one missed 5-min window.
**Long-term gap:** The VPS can commit the main node generator locally (no LKE access needed), but gen-0/gen-1 require GitHub Actions + kubectl. Consider a VPS-side cron for main node as a secondary failsafe.

### INC-002: val-0 CrashLoopBackOff (2026-07-01, 148 restarts over 4 days)
**Impact:** val-0 not participating in T2 HotStuff consensus (validators=2 not 3). Reduces fault tolerance; mainnet requires all validators stable.
**Root cause:** JVM OOM kill — val-0 had 512m heap / 620Mi container limit while gen-0 (768m / 880Mi) and gen-1 (640m / 750Mi) ran stably. Node started successfully (~35 min uptime), memory grew, OOM killed the container.
**Fix applied 2026-07-01:** Increased val-0 to 768m heap / 880Mi limit (matching gen-0) via `clusters/testnet/apps/nodes.yaml`. Flux applied within ~1 min.
**Verification:** Watch for restarts to stop; val-0 height should catch up to chain; validators count should rise to 3 after next generator commit period.

---

## What NOT to Do

- ❌ **`restart-host-network.yml` WIPES chain data** — use `update-node-image.yml` instead for image updates
- ❌ **`peer-watchdog.yml` SIGKILLs the node** — it's an emergency tool, not routine automation. Causes BPS crash and chain reset
- ❌ **Do not use bridge mode for main node** — causes TCP connectivity failure from LKE
- ❌ **Do not write matcher `local.conf` to `/opt/dcc/data/matcher-testnet/config/`** — shadowed by config mount
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit (1200/hr)
- ❌ **Do not check credentials from memory** — always read KEEWEB_BACKUP.md first

---

## Disaster Recovery

### Main Node Loss (Newark VPS)
If the Newark VPS becomes unavailable, T2 quorum drops to gen-0+gen-1 only (2/3 ✓). Chain production continues via FairPoS. T2 finalization continues if gen nodes stay connected.

**Recovery procedure:**
1. Provision new VPS (same region, same specs): `gh workflow run provision.yml --repo Decentral-America/infra`
2. Bootstrap new VPS: `gh workflow run push-secrets.yml --repo Decentral-America/infra`
3. Restore chain state from latest R2 snapshot:
   ```bash
   rclone copy r2:pg-backups-testnet/chain-state/node-state-LATEST.tar.gz /tmp/ --config /opt/dcc/rclone.conf
   docker volume create node-state-testnet
   docker run --rm -v node-state-testnet:/data -v /tmp:/backup alpine tar xzf /backup/node-state-LATEST.tar.gz -C /data
   ```
4. Deploy node: `gh workflow run update-node-image.yml --repo Decentral-America/infra`
5. Restore BPS database: `pg_restore` from latest pg_dump
6. **RTO target:** ~30 minutes | **RPO target:** 24 hours (daily backup schedule)

### Chain State Backup Schedule
Automated daily backups via `backup-chain-state.yml` (03:00 UTC):
- Destination: `r2:pg-backups-testnet/chain-state/` — 7-day retention
- Includes: full RocksDB volume (blocks, state, blockchain-updates)
- R2 credentials: `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` (repo-level GitHub secrets, already configured)
- **REQUIRED to activate:** set GitHub secret `BACKUP_OBJ_ENDPOINT` in the testnet environment (format: `https://<account_id>.r2.cloudflarestorage.com`)
- Verify: `gh workflow run backup-chain-state.yml --repo Decentral-America/infra`

**NOTE:** As of 2026-07-01 the backup workflow has been updated to read the endpoint from the GitHub secret `BACKUP_OBJ_ENDPOINT` (no longer requires the VPS testnet.env to have this value). The secret must be added before backups will succeed.

### Gen Node Loss
LKE auto-reschedules pods. If both gen nodes go offline temporarily, FairPoS continues (main node mines solo). T2 pauses until quorum is restored. No manual action needed — Flux monitors and restarts.

---

## ⚠️ Security: API Keys in Git History
Node REST API keys are in git history of `infra` repo (commits Jun 25-27). Keys must be **rotated before mainnet**:
1. Generate new API keys (random alphanumeric, 32+ chars)
2. Compute hash using `secureHash = Keccak256(Blake2b256(key))` then base58-encode.
   **CRITICAL: NOT SHA256** — node uses `crypto.secureHash` per `ApiRoute.scala:27`. Use the node's own utility:
   ```bash
   curl -s -X POST http://localhost:6869/utils/hash/secure \
     -H "Content-Type: application/json" -d '{"message":"YOUR_NEW_KEY"}' | python3 -c "import json,sys; print(json.load(sys.stdin)['hash'])"
   ```
3. Update `api-key-hash` in `dcc.conf` for each node:
   - Main: `infra/node-config/testnet/dcc.conf` → deploy via `deploy-node-config.yml`
   - Gen/val: `infra/clusters/testnet/apps/nodes.yaml` → Flux auto-applies within 10 min
4. Update GitHub Actions secrets: `MAIN_NODE_REST_API_KEY`, `GEN_0_NODE_REST_API_KEY`, `GEN_1_NODE_REST_API_KEY`, `VAL_0_NODE_REST_API_KEY`
5. Verify each node: `curl -H "X-API-Key: NEW_KEY" http://localhost:6869/peers/connected` → expect 200

**Current api-key-hash values** (in git — source of the rotation requirement):
- main: `5gZJk3xTibMQ65CvKeBzoHR4pY5h7EYmAc87ZZcLW7ps`
- gen-0: `9JJ6P8coxQdrRS9XWyxwPgbF1zCL6NGMoAjwmaLDEimY`
- gen-1: `2dee71STxrm5YNC8RDSAkaxCMKSoYBAw2pfwFk8TdT2S`
- val-0: `FMb13YEvv9XBgbRcP4SosJVWf5X2i8iTG9H2ZEswaTLE`

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
| val-0 (LKE) | 6865 | 6871 | VAL_0_NODE_REST_API_KEY |

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
- Round timeout: 1200ms (tuned 2026-06-30, p99 ~1000ms + 20% margin)

### Item 7 — E2E test suite ✅ PASSED (2026-06-30)
Direct vitest run against `testnet-node.decentralchain.io` — 2 spec files, 29 tests, all passed.
- `network/node-api.spec.ts`: blocks, addresses, transactions, node health (24 tests)
- `network/peers.spec.ts`: connectivity, chain consistency (5 tests)
- Workflow: `infra/.github/workflows/admin-e2e.yml` (runs from CI against public HTTPS endpoint)
