# Testnet Bootstrap Runbook

## Root Causes Identified (2 days of debugging)

### 1. baseTarget inflation — slow blocks
**Cause:** With 3 validators mining from genesis, blocks come every ~10s (3× target rate). `baseTarget` triples per block. After 100 blocks, baseTarget saturates → each block takes 10+ minutes.  
**Fix:** Reset with `initial-base-target` set high enough to match the expected multi-validator rate, OR accept slow initial blocks that auto-correct over ~100 blocks.  
**Proper fix:** Set `initial-base-target = 1739` (= 218 × 8, tuned for 3 validators at 80% total stake mining at 30s each).

### 2. Main node loses peer connections
**Cause:** Unknown — the P2P connection between VPS (66.228.55.154) and LKE cluster (172.105.64.89) drops periodically. Peers.dat blacklist, score comparison oscillation, or network instability.  
**Symptom:** Connected Peers: 0 on dashboard, block time spikes.  
**Workaround:** `mainnode-ops wipe-peers-and-restart` forces reconnection.  
**Proper fix:** Investigate if the `blockchain-postgres-sync` or `scanner` service is hammering the P2P port. Add reconnection monitoring + auto-alert.

### 3. CommitToGenerationTransaction causes state hash divergence
**Cause (FIXED):** The TX lands in different blocks on competing chains, causing cumulative state hash mismatch. Feature 21 (state hash validation) prevents chain switches.  
**Fix applied:** Removed `nextCommittedGenerators` from per-TX state hash in `TxStateSnapshotHashBuilder.scala` (commit `d352f5fb`). This is in production on the new image.  
**Status:** Fixed. Chain advances past all period boundaries now.

### 4. Fallback rule vs explicit CommitToGeneration
**Discovery:** When NOBODY commits for a period, the fallback rule allows all genesis-funded accounts to mine. T0 DeterministicFinality ALSO works with fallback. T2 HotStuff requires explicit commits with BLS keys.  
**Implication:** For basic chain operation, CommitToGeneration TXs are optional. Only needed for T2 HotStuff activation.

### 5. DCC requires miner enabled to sync blocks
**Cause:** With `miner.enable = no`, the DCC node does NOT actively request blocks from peers even when connected. Miner module drives sync.  
**Impact:** "Single-miner bootstrap" approach with other miners disabled doesn't work — non-mining nodes stay at genesis.  
**Fix:** All nodes must have miner enabled for proper P2P sync.

### 6. Convergence check must exclude non-syncing nodes
**Cause:** The commit-to-generation convergence check included gen-1 (miner disabled → stuck at height 1) in the min_height calculation, making convergence impossible.  
**Fix applied:** Convergence check now only requires main node + gen-0 agreement (active miners only).

### 7. Block time normalization after multi-validator genesis
**Cause:** With 3 validators at 80% combined stake, blocks come every ~37.5s. The Waves/DCC baseTarget adjustment formula increases baseTarget when blockInterval < 2×averageDelay (60s). Starting at baseTarget=218, repeated fast blocks drive baseTarget toward Long.MaxValue. At Long.MaxValue, each block contributes score≈1, making score comparison irrelevant. Block production slows dramatically (10+ min/block) until baseTarget self-corrects downward.  
**Symptom:** Dashboard shows Avg Block Time: 580s.  
**Recovery:** Self-corrects over ~100-200 blocks once validators reconnect and mine steadily. No action needed unless peer connections are also lost.  
**Prevention:** Set `initial-base-target = 272` (starting closer to natural equilibrium) and ensure all validators connect within 60 seconds of genesis.

### 8. gen node wipe fails via kubectl exec
**Cause:** `resync-gen-nodes` wipe step uses `kubectl exec` to run `rm -rf /var/lib/dcc/data`. Fails if gen pods are in non-Running state (Pending, CrashLoopBackOff, Terminating) during a rollout cycle.  
**Workaround:** Use `cluster-diagnostics roll=true` (reconcile + rollout restart, no wipe) to pick up config changes. For wipe: wait for pods to be fully Running before triggering resync-gen-nodes.  
**Proper fix:** Add pod readiness wait before the wipe step in resync-gen-nodes.yml.

---

## Correct Bootstrap Procedure

### Prerequisites
- All nodes running image `d352f5fb` or later (state hash fix)
- `initial-base-target = 1739` in genesis config (prevents baseTarget inflation)
- `miner.enable = yes` on all nodes
- `micro-block-interval = 2s` (normal production value)
- `generation-period-length = 100` (production value)
- `max-rollback = 2000`

### Steps

1. **Apply genesis config to main node FIRST:** `mainnode-ops fix-genesis-config-and-restart` (patches initial-base-target=272, generation-period-length=100 on VPS)

2. **Full reset — all nodes simultaneously:** `mainnode-ops wipe-chain-and-restart` + `resync-gen-nodes WIPE`. If gen wipe fails (pods not Ready), use `cluster-diagnostics roll=true` instead.

3. **Wait for all nodes online (height > 5):** Check `curl https://testnet-node.decentralchain.io/peers/connected` — needs to show gen-0 and gen-1. If peers=0 after 3 min, run `mainnode-ops wipe-peers-and-restart`.

4. **Run commit-to-generation:** After height 20+. The convergence check requires main node + gen-0 to agree on a shared block ID. With the state hash fix, this passes within minutes.

5. **Keep committing every ~80 blocks:** Before each period boundary (every 100 blocks). Automate via cron or `/schedule` command in Claude Code targeting `commit-to-generation.yml`.

6. **Monitor block time:** Should stabilize at ~37s/block. If spike to 10+ min: check Connected Peers. If 0, run `mainnode-ops wipe-peers-and-restart`.

### What NOT to do
- ❌ **Do not disable miners** — non-mining nodes don't sync in DCC (miner drives sync)
- ❌ **Do not submit CommitToGeneration TXs before convergence** — causes state hash divergence (state hash fix mitigates but avoid anyway)
- ❌ **Do not use `gh run watch`** — burns GitHub REST rate limit (1200 calls/hr). Use GraphQL polling + artifact download.
- ❌ **Do not set `initial-base-target = 218` with 3 validators** — baseTarget explodes in ~130 blocks, then takes hours to normalize
- ❌ **Do not disable `rest-api` section** — the `disable-miner-and-restart` bug that disabled REST API is now fixed but watch the sed/awk targeting

---

## Current Config Issues to Fix

### `initial-base-target` (HIGH PRIORITY)
Current: `218`  
Should be: `1739` (218 × 8, tuned for 80% stake at 30s blocks)  
Why: With 3 validators at 80% combined stake, actual block time = 30s/0.8 = 37.5s. Starting at 218, the baseTarget increases and equilibrates around 218 × (37.5/30) ≈ 272. The current 218 causes initial overshoot.

Actually, the proper calculation: for the testnet genesis with 3 validators at combined 80% stake, expected block time = 37.5s. The baseTarget self-adjusts to produce 30s blocks on average. Set `initial-base-target = 272` to start near equilibrium.

### `generation-period-length` consideration
Current: `100` blocks = ~50 minutes per period (at 30s/block)  
Mainnet: 100 blocks  
OK for testnet — keep as is.

---

## Automation Needed (TODO)

### 1. Auto commit-to-generation cron
**Action:** Schedule `commit-to-generation.yml` to run every 40 minutes (before each 100-block period boundary at ~37s/block).  
**How:** GitHub Actions schedule trigger OR `infra/scripts/gh-wait-and-download.sh` approach via cron.

### 2. Peer reconnection watchdog
**Action:** If `testnet-node.decentralchain.io/peers/connected` returns 0 peers for >5 min, auto-trigger `mainnode-ops wipe-peers-and-restart`.  
**How:** Monitor task or GitHub Actions scheduled workflow polling the endpoint.

### 3. Block time alert
**Action:** If avg block time > 120s, alert that baseTarget has inflated or validators disconnected.

### 4. T2 HotStuff activation procedure (not yet done)
T2 requires `CommitToGenerationTransaction` with BLS public keys. The BLS keys are derived from each validator's wallet seed. Procedure:
1. Get BLS public key from each gen node: `kubectl exec dcc-gen-0-0 -- wget -qO- http://localhost:6869/node/blsPublicKey` (endpoint TBD)
2. Include BLS public key in CommitToGeneration TX (already handled by the node's own wallet signing)
3. The commit-to-generation workflow handles this — it signs TXs with the node's own wallet
4. T2 activates automatically once `hotStuffSettings.enabled = true` (already set) AND validators are in the committed set

---

## Known Non-Issues

- `31HrVNJz...3ab8EE` with 0 DCC in block production stats: Genesis block signer. Always present with 1 block and 0 balance. Not a bug.
- T2 HotStuff shows `None`: Requires explicit CommitToGeneration TXs with BLS keys. Will activate once validators commit.
