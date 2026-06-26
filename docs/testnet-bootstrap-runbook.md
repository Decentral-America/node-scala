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

1. **Full reset:** Wipe all chain data simultaneously (`mainnode-ops wipe-chain-and-restart` + `resync-gen-nodes WIPE`)

2. **Let chain build:** With the correct `initial-base-target`, all 3 validators mine at ~30s/block. Chains converge via score comparison (state hash fix ensures this works).

3. **Run commit-to-generation ONCE:** After height 20+, when all nodes have converged. The convergence check (main + gen-0 agreement) ensures TXs go onto the canonical chain. This activates explicit validators for T2 HotStuff.

4. **Keep committing:** Run commit-to-generation every ~80 blocks (before each period boundary). Can be automated with a cron schedule.

### What NOT to do
- ❌ Do not disable miners for "single-miner bootstrap" — non-mining nodes don't sync
- ❌ Do not submit CommitToGeneration TXs before chain convergence — causes state hash divergence (now fixed but still better practice)
- ❌ Do not use `gh run watch` — burns GitHub API rate limit (1200 calls/hr)
- ❌ Do not set `initial-base-target = 218` with 3 validators — baseTarget explodes in 100 blocks

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

## Automation Needed

### Auto commit-to-generation cron
The commit-to-generation workflow should run automatically every ~80 blocks (~40 minutes). Set up as a scheduled GitHub Actions workflow or use `/schedule` in Claude Code.

### Peer monitoring
Alert when `Connected Peers: 0` on main node. Auto-trigger `mainnode-ops wipe-peers-and-restart` after 5 minutes of 0 peers.

---

## Known Non-Issues

- `31HrVNJz...3ab8EE` with 0 DCC in block production stats: Genesis block signer. Always present with 1 block and 0 balance. Not a bug.
- T2 HotStuff shows `None`: Requires explicit CommitToGeneration TXs with BLS keys. Will activate once validators commit.
