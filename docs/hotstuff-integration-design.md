# T2 HotStuff — Implementation & Integration Design (SSOT)

> **Status:** in implementation on `feature/hotstuff-t2`. Pure BFT core complete + unit-tested; the
> side-effecting shell (step 4c) and multi-node validation (step 5) remain. **Gated behind
> `dcc.hotstuff.enabled` (default `false`) — zero behaviour change on any node today.**
> **Design authority:** the high-level spec is `Ecosystem/CONSENSUS.md`; this file is the SSOT for the
> node-scala *implementation* of T2. Keep it updated as code lands.
>
> ⚠️ Enabling on mainnet is gated on: schemas 1.6.4 published, step-5 multi-node + soak, and an
> **external audit** (see `hotstuff-security-review.md`).

## 1. Protocol
Basic 3-phase HotStuff (prepare → pre-commit → commit) over the **committed-generator committee**
(generators who submitted `CommitToGenerationTransaction` with a BLS key, PoP-verified on-chain at
`CommitToGenerationTransactionDiff.scala:22`). Quorum = **≥2/3 of committed stake** (reuses
feature-25's `FinalizationVoting.isFinalized`: `endorsed*3 ≥ total*2`). On stall/timeout the view
advances and **feature-25 Deterministic Finality continues underneath — the chain never halts.**

## 2. Architecture — functional core / imperative shell
- **Functional core (done):** deterministic, no I/O, no clock (`Date.now`/random unavailable; block
  ancestry injected as `(BlockId, BlockId) => Boolean`). Fully unit-testable.
- **Coordinator (done):** `HotStuffCoordinator.Enabled` orchestrates the reducers into the 3-phase loop,
  side effects injected via `HotStuffEffects`. Validated by an in-process deterministic 4-node
  simulation (happy path + one crashed node). `Disabled` is a no-op used when the flag is off.
- **Shell binding (step 4c-bind, not built):** the real `HotStuffEffects` (broadcast via `allChannels`,
  sign via `wallet`/BLS, commit → finalized height), plus `Application`/`Miner`/network-dispatch/timer
  wiring. Pure I/O — validated only by step 5.

## 3. Module inventory

| Module | Package `com.decentralchain.consensus.hotstuff` (unless noted) | Status | Tests |
|--------|----------------------------------------------------------------|--------|-------|
| `HotStuffSettings` | `settings` — pureconfig, wired in `DCCSettings`, default off | ✅ | 4 |
| `HotStuffVote` / `QuorumCertificate` / `HotStuffProposal` | `network` — domain + PB (schemas 1.6.4) + `MessageSpec` codes **39/40/41** in `BasicMessagesRepo` | ✅ | 5 |
| `HotStuffQuorum` | `voteMessage`, `verifyVote`, `formQC`, `verifyQC`, `hasQuorum` | ✅ | 7 |
| `HotStuffSafety` | `SafetyState`, `safeToVote`, `update`, `committedBlock`, `equivocators` | ✅ | 8 |
| `HotStuffPacemaker` | `leaderFor`, `onQC`, `onTimeout` | ✅ | 6 |
| `HotStuffVotePool` | `onVote` (accumulate → `formQC` at 2/3; drops invalid) | ✅ | 6 |
| `HotStuffEngine` | reducer: `onQC`, `onProposal`, `onTimeout` | ✅ | 8 |
| `HotStuffCoordinator` + `HotStuffEffects` | orchestration (Disabled no-op + Enabled); validated by in-process 4-node simulation | ✅ | 2 (sim) |
| **shell binding** | real `HotStuffEffects` impl + `Application`/`Miner`/network/timer wiring (see §5) | ❌ 4c-bind | — (step 5) |

## 4. Protocol ↔ code (one block, view v, leader L forging block N)

| Step | Actor | Handled by | Layer |
|------|-------|-----------|-------|
| Forge N, broadcast `HotStuffProposal(v, id(N), highQC=prepareQC)` | L | shell → `Miner` hook | 4c |
| Decide vote: verify justify, safety rule | each | `HotStuffEngine.onProposal` (→ `verifyQC`, `safety.update`, `safeToVote`) | core |
| If safe: sign `HotStuffVote(v, PREPARE, …)` + broadcast | each | shell (`BlsKeyPair.sign(HotStuffQuorum.voteMessage)`, `allChannels.broadcast`) | 4c |
| Tally PREPARE votes → `prepareQC` | L | `HotStuffVotePool.onVote` → `formQC` | core |
| On `prepareQC`: track, then vote PRE_COMMIT | each | `HotStuffEngine.onQC` → shell re-votes | core+4c |
| Tally PRE_COMMIT → `precommitQC`; on it **lock** + vote COMMIT | each | `onVote` → `onQC` (`update` sets `lockedQC`) | core+4c |
| Tally COMMIT → `commitQC`; **finalize N** | each | `onVote` → `onQC` → `committedBlock` → `HotStuffAction.Committed` → shell applies | core+4c |
| Leader silent → rotate leader, next view | all | `HotStuffEngine.onTimeout` → `leaderFor(v+1)`; shell timer | core+4c |

## 5. Step 4c — the shell (integration seams)
**Landed (gated, compiled, additive — zero behaviour change):** `NodeHotStuffEffects` (broadcast via
`allChannels`, BLS-sign via `wallet`, observational commit) and `MessageObserver` inbound routing for
codes 39/40/41. **Remaining (deliberate, harness-validated):** Application subscription lifecycle,
per-period committee refresh, leader↔forger mapping, pacemaker timer, Miner proposing hook.

Mirror the existing feature-25 endorsement path:
- **Construct & wire** a `HotStuffEngine` shell/actor in `Application.scala` next to
  `BlockEndorser.InMemory` (`Application.scala:147-148`) — needs `wallet`, `allChannels`, `blockchain`,
  committee lookups, finalized-height writer.
- **Trigger** off the post-apply hook `blockEndorser.vote(gs)` in `BlockAppender.scala:48` and `Miner`
  (`Miner.scala:59,320`): as leader → emit proposal; as replica → `onProposal` → sign+broadcast vote.
- **Inbound dispatch:** decode `RawBytes(39|40|41)` (registered in `BasicMessagesRepo.specsByCodes`) →
  domain → `onProposal`/`onVote`/`onQC`. Mirror `EndorseBlockSpec` consumption.
- **Committee/stake:** `CommonGeneratorsApi.generators(h)` / `blockchain.committedGenerators(period)` /
  `currentGeneratorSet`.
- **Commit application (⚠️ the hard part / design decision):** there is **no external setter** for
  finalized height — it is computed internally by feature-25's `FinalizationState` inside
  `BlockchainUpdaterImpl` (see `:395`, `:440-447`). Applying a HotStuff commit therefore requires an
  invasive, reviewed change to the node's core state machine. **Decision needed:** does the HotStuff
  fast-commit (a) *raise* `finalizedHeight` ahead of feature-25 when a `commitQC` lands (HotStuff as the
  faster finality source, feature-25 as fallback), or (b) run purely observational (expose a separate
  `hotStuffFinalizedHeight`, leave feature-25 authoritative) for the first soak? Recommend **(b) for the
  initial testnet soak** (zero risk to the authoritative finalized height), then **(a)** after the soak +
  external audit. Either way this is core-state work validated only by step 5, not a plug-in effect.
- **Pacemaker timer:** monix `Scheduler` task firing `onTimeout` at `hotStuffSettings.roundTimeout`,
  reset on each QC.
- **Gate:** everything behind `settings.hotStuffSettings.enabled`.
- **Open design point:** the HotStuff-view ↔ block-height/forger mapping (CONSENSUS.md: "forger =
  leader"). First view of a height aligns with the FairPoS forger; timeouts rotate via `leaderFor`.

## 6. Testing strategy
- **Unit (done):** 45 tests — the 7 core modules (adversarial: forged aggregate sig, below-quorum,
  stale-justify no-vote, monotonic commit, equivocation, invalid-vote drop) plus a deterministic
  in-process 4-node coordinator simulation (happy path + one crashed node).
- **Step 5 (required):** `node-it` multi-node suites (pattern: `node-it/.../sync/finalization/*`) —
  agreement under crashed leader, network partition, equivocating validator — then testnet soak
  behind the flag. BFT safety/liveness only manifest across ≥4 nodes; no unit test substitutes.

## 7. Wire format (schemas 1.6.4, `dcc/block.proto`)
`HotStuffPhase{UNSPECIFIED,PREPARE,PRE_COMMIT,COMMIT}`;
`HotStuffVote{view, phase, block_id, block_height, voter_index, signature(BLS)}`;
`QuorumCertificate{view, phase, block_id, block_height, signer_indexes[], aggregated_signature(BLS)}`;
`HotStuffProposal{view, block_id, justify:QuorumCertificate}`.
**1.6.4 is unpublished** — built to local `~/.m2` for dev; must be published (Sonatype/GPG) before CI/merge.

## 8. Open gates
1. Publish `protobuf-schemas` 1.6.4 (credentialed release).
2. Step 4c shell + step 5 multi-node validation + testnet soak.
3. External audit before `hotstuff.enabled = true` on mainnet.
