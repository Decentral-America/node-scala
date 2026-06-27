# Testnet Bootstrap Runbook

> **Single Source of Truth.** Last updated: 2026-06-26. Supersedes all prior STATUS/HANDOFF/TODO docs.

---

## Current Testnet State (2026-06-26)

| Item | Status |
|------|--------|
| Chain height | ~613+, advancing ~30s/block via fallback rule |
| Main node (Newark 66.228.55.154) | ✅ Running, producing blocks |
| gen-0 (LKE 172.105.64.89:6863) | ⚠️ Cycling — RC#2 fix deployed via config (see below) |
| gen-1 (LKE 172.105.64.89:6864) | ❌ Blacklisted on main node — fix: `disable-blacklisting-and-restart` + resync |
| State hash fix (RC#3) | ✅ Deployed (commit 44b93a0) |
| initial-base-target immutable | ✅ Documented — cannot change without new genesis |
| Auto commit-to-generation | ✅ Scheduled cron every 35 min (added 2026-06-26) |
| Peer reconnection watchdog | ✅ `peer-watchdog.yml` auto-reconnects every 5 min (added 2026-06-26) |
| T2 HotStuff | ❌ Not activated — requires committed validators with BLS keys |
| RC#2 blacklisting fix | ✅ Config deployed — requires `disable-blacklisting-and-restart` + resync to take effect |

---

## Root Causes Identified

### RC#1 — baseTarget inflation (RESOLVED)

**Cause:** With 3 validators mining from genesis, blocks come every ~10s (3× target rate). `baseTarget` triples per block. After 100 blocks, baseTarget saturates → each block takes 10+ minutes.  
**Fix applied:** `initial-base-target = 1739` (= 218 × 8) in genesis config prevents overshoot. Current genesis is locked at 218 (immutable — see RC#9).  
**Current state:** Blocks self-correct to ~30s equilibrium via fallback generator rule over 100–200 blocks.

---

### RC#2 — Peer disconnection cycling (ROOT CAUSE FIXED 2026-06-26)

**Precise cause (traced through source code):**

1. Gen-0 mines blocks on its own fork (disconnected from main node)
2. Gen-0 reconnects and attempts a chain switch (its forked chain has higher score)
3. `ExtensionAppender.scala:138` fails block validation → calls `peerDatabase.blacklistAndClose(ch, reason)`
4. Gen-0's IP is blacklisted on main node for `black-list-residence-time = 1h` (default)
5. `NetworkServer.scala:134` — when gen-0's outgoing channel closes, gen-0 suspends main node for `suspension-residence-time = 1m`
6. After 60s, gen-0 retries connecting to main node
7. `InboundConnectionFilter.scala:35` — main node rejects inbound from gen-0 (still blacklisted for 1h)
8. Gen-0 gets suspended again → **60s cycling**

**Fix (deployed 2026-06-26, no code rebuild required):**

All node configs (`dcc.conf` + `dcc-nodes.yaml`) now include:
```hocon
network {
  enable-blacklisting = no        # removes 1h IP bans after validation failure
  suspension-residence-time = 10s # faster channel-close recovery
}
```
Main node also has:
```hocon
synchronization {
  max-rollback = 2000
  invalid-blocks-storage {
    timeout = 30s   # invalid block IDs expire in 30s, not 1h
  }
}
```

**To activate immediately (one-time ops):**
1. Run `mainnode-ops → disable-blacklisting-and-restart` (applies config to VPS, clears in-memory blacklist via restart)
2. Run `resync-gen-nodes WIPE` (wipes gen-0/gen-1 fork chains → forces clean sync from main node)

**Why wipe gen nodes?** Gen-0 has been mining a fork with higher cumulative score. Wiping forces it to sync from the main node's canonical 613-block chain instead. After sync, all 3 nodes mine on the same chain.

---

### RC#3 — CommitToGeneration causes state hash divergence (FIXED)

**Cause:** TX lands in different blocks on competing chains → cumulative state hash mismatch → Feature 21 blocks chain switch.  
**Fix applied:** Removed `nextCommittedGenerators` from per-TX state hash in `TxStateSnapshotHashBuilder.scala` (commit `d352f5fb`, also `44b93a0` / `Caches.scala`).  
**Status:** Fixed. Chain advances past all period boundaries via fallback rule.

---

### RC#4 — Fallback rule vs explicit CommitToGeneration

When NOBODY commits for a period, the fallback rule allows all genesis-funded accounts to mine. T0 DeterministicFinality works with fallback. T2 HotStuff requires explicit commits with BLS keys.

**Implication:** Chain advances without CommitToGeneration TXs (fallback). Now automated: cron commits every 35 min to keep generators committed for T2 activation readiness.

---

### RC#5 — DCC requires miner enabled to sync blocks

With `miner.enable = no`, the DCC node does NOT actively request blocks from peers even when connected. All nodes must have `miner.enable = yes`.

---

### RC#6 — Convergence check must exclude non-syncing nodes

The commit-to-generation convergence check only requires main node + gen-0 agreement. Gen-1 (miner disabled or syncing) is excluded from min_height calculation.

---

### RC#7 — Block time normalization after multi-validator genesis

With 3 validators at 80% combined stake, blocks come every ~37.5s. Starting at `initial-base-target = 218`, baseTarget increases then self-corrects over ~100-200 blocks.  
**Recovery:** Self-corrects once validators reconnect and mine steadily.  
**Prevention:** Cannot change `initial-base-target` (see RC#9).

---

### RC#9 — `initial-base-target` is immutable (baked into genesis signature)

Changing `initial-base-target` invalidates the genesis block signature → node crashes: "Passed genesis signature is not valid."  
**Workaround:** Accept the initial ~580s period after multi-validator genesis. Block time self-corrects to equilibrium.

---

### RC#10 — Gen node wipe fails via kubectl exec if pods not Running

Wipe step uses `kubectl exec` which fails if pods are Pending/CrashLoopBackOff.  
**Workaround:** Use `cluster-diagnostics roll=true` for config-only changes. Wait for pods Running before `resync-gen-nodes WIPE`.

---

## Correct Bootstrap / Recovery Procedure

### Prerequisites
- All nodes running image `d352f5fb` or later (state hash fix)
- `miner.enable = yes` on all gen nodes
- `enable-blacklisting = no` in all node network configs (deployed 2026-06-26)
- `initial-base-target = 218` (immutable — cannot change)
- `generation-period-length = 100`
- `max-rollback = 2000`

### Full Reset Procedure (from height 0)

1. **Apply genesis config to main node:** `mainnode-ops → fix-genesis-config-and-restart`
2. **Apply RC#2 fix to main node:** `mainnode-ops → disable-blacklisting-and-restart`
3. **Wipe all nodes simultaneously:** `mainnode-ops → wipe-chain-and-restart` + `resync-gen-nodes WIPE`
4. **Wait for all nodes online (height > 5):** Check `curl https://testnet-node.decentralchain.io/peers/connected`
5. **If peers = 0 after 3 min:** `mainnode-ops → wipe-peers-and-restart` (now automated by peer-watchdog.yml)
6. **Commit-to-generation:** Now automated via schedule trigger every 35 min
7. **Monitor block time:** Should stabilize at ~37s/block via peer-watchdog.yml

### Recovery from Gen Node Fork (current situation 2026-06-26)

1. `mainnode-ops → disable-blacklisting-and-restart` — clears in-memory blacklist, applies RC#2 fix
2. `resync-gen-nodes WIPE` — wipes gen-0/gen-1 fork data, pods restart and sync from main node
3. Wait 5 min, verify heights agree and block production resumes from all 3 generators
4. No manual commit-to-generation needed — cron handles it

---

## Automation (all active as of 2026-06-26)

| Automation | Schedule | Workflow | Purpose |
|-----------|----------|----------|---------|
| Commit-to-generation | Every 35 min | `commit-to-generation.yml` | Keep generators committed before 50-min period boundaries |
| Peer reconnection watchdog | Every 5 min | `peer-watchdog.yml` | Auto-reconnect or restart if peer count drops to 0 |

---

## Config Reference

### Main Node (`/opt/dcc/config/node-testnet/dcc.conf` on 66.228.55.154)

Key non-default settings:
```hocon
network {
  enable-blacklisting = no         # RC#2 fix
  suspension-residence-time = 10s  # RC#2 fix
}
synchronization {
  max-rollback = 2000
  invalid-blocks-storage {
    timeout = 30s                  # RC#2 fix
  }
}
miner {
  enable = yes
  quorum = 0
}
```

### Gen Nodes (`dcc-nodes.yaml` → Flux → LKE)

Same `enable-blacklisting = no` + `suspension-residence-time = 10s` applied to gen-0-config, gen-1-config, val-0-config.

---

## What NOT to Do

- ❌ **Do not disable miners** — non-mining nodes don't sync in DCC
- ❌ **Do not submit CommitToGeneration TXs before convergence** — wait for shared ancestor
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit (1200 calls/hr). Use GraphQL polling + artifact download
- ❌ **Do not set `initial-base-target = 218` with 3 validators** — baseTarget explodes in ~130 blocks
- ❌ **Do not re-enable blacklisting** without understanding why a peer's blocks failed validation

---

## T2 HotStuff Activation Procedure (TODO — requires explicit commits)

1. Get BLS public key from each gen node: `kubectl exec dcc-gen-0-0 -- wget -qO- http://localhost:6869/node/blsPublicKey`
2. CommitToGeneration TXs with BLS keys (handled by `commit-to-generation.yml` when validators sign)
3. T2 activates automatically once `hotStuffSettings.enabled = true` AND validators in committed set
4. Prerequisite: gen-0 and gen-1 must be stably peered and on the same chain as main node

---

## Known Non-Issues

- `31HrVNJz...3ab8EE` with 0 DCC in block production stats: Genesis block signer. Always present with 1 block and 0 balance.
- T2 HotStuff shows `None`: Requires explicit CommitToGeneration TXs with BLS keys. Will activate once validators commit.
- Slow initial blocks (580s avg): Expected after genesis with `initial-base-target = 218`. Self-corrects over 100–200 blocks.
