# DecentralChain Node — Operator Runbook

> Last updated: **2026-06-30**

This runbook covers day-to-day operational tasks for a DecentralChain (`node-scala`) validator or full-node operator.

---

## Table of contents

1. [Prerequisites](#prerequisites)
2. [Starting the node](#starting-the-node)
3. [Checking node health](#checking-node-health)
4. [Sync status and block height](#sync-status-and-block-height)
5. [Wallet management](#wallet-management)
6. [Log management](#log-management)
7. [Upgrading the node](#upgrading-the-node)
8. [Graceful shutdown](#graceful-shutdown)
9. [Rollback procedure](#rollback-procedure)
10. [Known peer list](#known-peer-list)
11. [Go/No-Go checklist](#gono-go-checklist)
12. [Escalation contacts](#escalation-contacts)
13. [Testnet soak results](#testnet-soak-results)

---

## Prerequisites

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| Docker Engine | 25.x | Latest stable |
| RAM | 4 GB | 8 GB |
| CPU | 2 cores | 4+ cores |
| Disk (mainnet, fast SSD) | 200 GB | 500 GB |
| Network | 100 Mbit/s | 1 Gbit/s |
| Open inbound ports | 6868/tcp (P2P) | 6868/tcp + 6869/tcp (API) |

---

## Starting the node

### Docker (recommended)

```sh
docker run -d \
  --name dcc-node \
  --restart unless-stopped \
  -p 6868:6868 \
  -p 6869:6869 \
  -v dcc-data:/var/lib/dcc \
  -e DCC_NETWORK=mainnet \
  -e DCC_HEAP_SIZE=4g \
  -e DCC_WALLET_SEED="$DCC_WALLET_SEED" \
  -e DCC_WALLET_PASSWORD="$DCC_WALLET_PASSWORD" \
  ghcr.io/decentral-america/node-scala:mainnet-latest
```

Store secrets out of shell history:

```sh
# .env (chmod 600, never commit)
DCC_WALLET_SEED=<base58_seed>
DCC_WALLET_PASSWORD=<password>
```

Then: `docker run --env-file .env ...`

### Docker Compose (production)

See [docker/README.md](docker/README.md) for a complete Compose example.

### From the fat JAR (bare metal)

```sh
java -Xmx4g \
  -Djava.net.preferIPv4Stack=true \
  -jar node/target/dcc-all-<version>.jar \
  node/decentralchain-mainnet.conf
```

---

## Checking node health

```sh
# Node REST API status
curl -s http://localhost:6869/node/status | python3 -m json.tool

# Expected: blockchainHeight > 0, stateHeight == blockchainHeight, updatedTimestamp recent
```

Docker health check (reads the built-in HEALTHCHECK):

```sh
docker inspect --format='{{.State.Health.Status}}' dcc-node
# Expected: healthy
```

---

## Sync status and block height

```sh
# Local height
curl -s http://localhost:6869/blocks/height

# Compare against public peer
curl -s http://168.119.116.189:6869/blocks/height
```

If local height is > 100 blocks behind a peer, the node is still syncing. This is normal on first start (initial sync takes hours to days depending on hardware and network).

If the node stops progressing after syncing completes, restart it and check logs for `Fork` or `InvalidBlock` errors.

---

## Wallet management

```sh
# List addresses in the node wallet
curl -s http://localhost:6869/addresses

# Generate a new address (in the node wallet)
curl -s -X POST http://localhost:6869/addresses

# Check balance
curl -s "http://localhost:6869/addresses/balance/<address>"
```

**Never share your wallet seed.** The `DCC_WALLET_SEED` env var is not logged, but treat any system that has ever held it as potentially compromised if it is exposed.

---

## Log management

```sh
# Follow live logs
docker logs -f dcc-node

# Last 200 lines
docker logs --tail 200 dcc-node

# Export to file for analysis
docker logs dcc-node > /tmp/dcc-node-$(date +%Y%m%d).log 2>&1
```

Log levels (set via `DCC_LOG_LEVEL`): `OFF` `ERROR` `WARN` `INFO` `DEBUG` `TRACE`

Use `INFO` in production. `DEBUG` is useful for diagnosing sync or peer issues but generates significant volume.

---

## Upgrading the node

1. Pull the new image:
   ```sh
   docker pull ghcr.io/decentral-america/node-scala:mainnet-latest
   ```

2. Verify the image digest:
   ```sh
   docker inspect --format='{{.RepoDigests}}' ghcr.io/decentral-america/node-scala:mainnet-latest
   ```

3. Note the current block height before stopping:
   ```sh
   curl -s http://localhost:6869/blocks/height
   ```

4. Stop the running node gracefully (see [Graceful shutdown](#graceful-shutdown)).

5. Start a new container from the updated image:
   ```sh
   docker run -d \
     --name dcc-node \
     --restart unless-stopped \
     -p 6868:6868 \
     -p 6869:6869 \
     -v dcc-data:/var/lib/dcc \
     --env-file .env \
     -e DCC_NETWORK=mainnet \
     -e DCC_HEAP_SIZE=4g \
     ghcr.io/decentral-america/node-scala:mainnet-latest
   ```

6. Confirm the node is syncing from the pre-upgrade height:
   ```sh
   curl -s http://localhost:6869/blocks/height
   ```

---

## Graceful shutdown

The container uses `STOPSIGNAL SIGINT`, which triggers a clean JVM shutdown:

```sh
docker stop --time 60 dcc-node
```

The `--time 60` gives the node up to 60 seconds to flush state and close RocksDB cleanly. Do not use `docker kill` or `SIGKILL` — this risks database corruption requiring a full re-sync.

---

## Rollback procedure

If the upgraded node fails to start or produces errors after upgrade:

1. Stop the faulty container:
   ```sh
   docker stop dcc-node && docker rm dcc-node
   ```

2. Start the previous image (use the SHA-pinned tag from the last known-good CI run):
   ```sh
   docker run -d \
     --name dcc-node \
     --restart unless-stopped \
     -p 6868:6868 \
     -p 6869:6869 \
     -v dcc-data:/var/lib/dcc \
     --env-file .env \
     -e DCC_NETWORK=mainnet \
     ghcr.io/decentral-america/node-scala:sha-<previous-commit>
   ```

3. If the blockchain state is corrupted (node fails to load blocks), a full re-sync from the network peers is required. Remove the data volume and restart:

   > ⚠️ This destroys all local chain state. The node will re-sync from genesis (hours to days).

   ```sh
   docker stop dcc-node && docker rm dcc-node
   docker volume rm dcc-data
   docker run -d --name dcc-node ... (same as upgrade)
   ```

---

## Known peer list

Mainnet seed peers (as of release 1.6.1):

```
168.119.116.189:6868
135.181.87.72:6868
35.158.218.156:6868
52.48.34.89:6868
```

Testnet nodes (as of 2026-06-30, image `v1.6.3-be2dcfc0`):

| Role | Host | P2P | API |
|------|------|-----|-----|
| Main node (Newark) | 66.228.55.154 | 6868 | 6869 |
| Gen nodes (LKE Frankfurt) | 172.105.64.89 | 6863 | 6864 |

All 3 generator nodes are active: `CurGens=3`, `NextGens=3`.

These are configured in `node/decentralchain-mainnet.conf`. To add additional peers at runtime:

```sh
curl -X POST "http://localhost:6869/peers/connect" \
  -H "Content-Type: application/json" \
  -d '{"host": "<ip>", "port": 6868}'
```

---

## Go/No-Go checklist

Before declaring a node production-ready:

- [ ] Node is fully synced: `blockchainHeight == stateHeight` on `/node/status`
- [ ] `/blocks/height` matches a known public mainnet peer (within 5 blocks)
- [ ] REST API responds within 500 ms on `/node/status`
- [ ] Docker `HEALTHCHECK` reports `healthy`
- [ ] Wallet contains at least one address with expected balance
- [ ] Firewall allows inbound 6868/tcp from P2P peers
- [ ] Firewall restricts 6869/tcp to internal network or reverse proxy only
- [ ] `DCC_WALLET_SEED` is not in shell history or container inspect output
- [ ] Volume persistence confirmed: restart node, verify height continues from same point
- [ ] Log level set to `INFO` or `WARN` (not `DEBUG`/`TRACE`) in production

---

## Escalation contacts

| Role | Contact |
|------|---------|
| On-call engineer | See internal PagerDuty rotation |
| Security issues | security@decentral.exchange |
| Public discussion | https://github.com/Decentral-America/DecentralChain/discussions |

---

## Testnet soak results

**Soak completed: 2026-06-30 — PASSED (all 4 phases)**

| Metric | Value |
|--------|-------|
| Chain height at soak end | 9733+ |
| T2 finality lag | 0 blocks |
| Round-timeout | 1200 ms (tuned down from 5000 ms) |
| Generators online | 3 / 3 (CurGens=3, NextGens=3) |
| BPS commit | `fbece975a` |
| Type-19 transactions | Enabled |
| Node image | `v1.6.3-be2dcfc0` |

**Known transient issue (self-healing):** T0 DeterministicFinality was lagging at block 9668 during the soak window. No operator action required — the extension catches up automatically once its internal re-seek completes (fixed in BPS `fbece975a` and node-scala `ff9d86ae`).

**Patches applied before soak:**
- BPS: dedup + upsert in `insert_blocks_or_microblocks`
- BPS `fbece975a`: Loader.scala RocksDB re-seek bug fix (DecentralChain)
- node-scala `ff9d86ae`: Loader.scala (BlockchainUpdates extension) re-seek fix
- Infra: `round-timeout-ms` reduced from 5000 → 1200 ms
