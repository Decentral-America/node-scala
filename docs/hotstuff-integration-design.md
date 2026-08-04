# T2 HotStuff — Implementation & Integration Design (SSOT)

> **Status (updated 2026-08-04):** ✅ **Rework complete, merged to `main` @ `9c49632398`, and
> testnet-authoritative.** The `view=block-height` shell-model problem that the "REWORK PENDING" banner
> below used to warn about (first found live, 2026-07-12) is resolved: the pacemaker rework (real
> leader-timeout/view-change, decoupled `proposalValid`), `HotStuffVotePool` bounding, `lockedQC`
> persistence across restarts, and the re-propose-locked-branch leader-timeout optimization are all merged.
> By explicit human decision, ahead of the external audit and scoped to testnet only, a
> `dcc.hotstuff.authoritative` opt-in flag is also live on all 4 testnet nodes — a genuine HotStuff commit
> now raises the authoritative feature-25 `finalizedHeight` (monotonic max()-merge). `GET
> /blocks/height/finalized` is confirmed advancing on live testnet via this mechanism (verified
> 2026-08-04: `height=107779`, `height/finalized=107697`). Mainnet is completely unaffected —
> `authoritative` stays `false` there, still gated behind the external audit.
>
> Since that rework landed, two more things happened and are both closed: (1) **T10** — a
> cross-committee-epoch fork hazard (two disjoint committees each forming an honestly-signed 2/3 QC for a
> different block at the identical view/height) was found and fixed 2026-08-03 (wire-format
> `committeeEpoch` binding, schema 1.6.5, + `HotStuffQuorum.acceptableCommitteeEpoch` transition gate);
> adversarial review then found the fix's OWN wiring introduced a distinct liveness gap (`committeeEpoch`
> derived from the signer's live tip instead of the vote's target height), fixed 2026-08-04 by deriving it
> as a pure function of target height. See §6 and `docs/hotstuff-audit-readiness.md` T10 entry for full
> detail — **narrowed, not fully closed:** no live multi-node Docker evidence of an actual committee-epoch
> *transition* exists yet, only unit/DST simulation. (2) **SC-695** (unrelated RIDE feature, not part of
> HotStuff) was separately implemented behind feature id 30, dormant — irrelevant to this document beyond
> noting node-scala `main` now also contains it.
>
> **Gated behind `dcc.hotstuff.enabled` (default `false` outside testnet) and `dcc.hotstuff.authoritative`
> (default `false` everywhere except testnet) — zero behaviour change on mainnet today.**
> **Design authority:** the high-level spec is `Ecosystem/CONSENSUS.md`; this file is the SSOT for the
> node-scala *implementation* of T2. Keep it updated as code lands.
>
> ⚠️ Making `authoritative = true` on **mainnet** remains gated on: the external audit signing off (see
> `hotstuff-audit-readiness.md` and `hotstuff-security-review.md`), a formal multi-day testnet soak record
> for the reworked/authoritative model (not yet documented), and equivocation→slashing wiring.
>
> <details><summary>Original "REWORK PENDING" banner (superseded 2026-08-04, kept for history)</summary>
>
> ⚠️ **REWORK PENDING — do not treat as ship-ready.** Pure BFT core is complete + unit-tested
> and the CI simulation is green, BUT the first real multi-node run (step 5, live testnet, 2026-07-12)
> showed the `view=block-height` shell model does not work on an NG chain: it took **four** fixes just to
> get validators voting on the same block, and **QC formation is still unconfirmed live.** Full write-up
> and the proposed rework (pacemaker/single-active-view, or lean on feature-25) are in
> **[`hotstuff-step5-findings-and-rework.md`](./hotstuff-step5-findings-and-rework.md)**.
>
> ⚠️ Enabling on mainnet is gated on: schemas 1.6.4 published, step-5 multi-node + soak, and an
> **external audit** (see `hotstuff-security-review.md`).
> </details>

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
**Landed (compiled, gated OFF — zero behaviour change when disabled):** `NodeHotStuffEffects`
(broadcast/BLS-sign/observational commit), `MessageObserver` inbound routing (39/40/41), **and the full
`Application` wiring** — gated coordinator construction, a dynamic per-period committee provider
(`blockchain.currentGeneratorSet`), inbound subscriptions on a dedicated single-thread scheduler, the
pacemaker timer (`round-timeout` → `onTimeout`), and the propose-if-we-forged hook via `lastBlockInfo`.
Implementer decision: **view = SETTLED block height (tip−1), leader = that block's FairPoS forger,
proposed/voted target = the canonical key-block id `blockchain.blockId(view)`.** Rationale (step-5
finding, 2026-07-12): the first cut used `view = tip height` and proposed the *liquid* `lastBlockInfo.id`.
Under Waves-NG the tip block's id changes as microblocks append and differs across nodes, so several
nodes proposed different blockIds at the *same* view → votes fragmented → **no QC ever formed**. Running
one **settled** height behind the tip and voting on the canonical `blockId(s)` gives exactly one leader
and one agreed block per view. A replica also enforces `proposalValid(view, blockId) =
blockchain.blockId(view).contains(blockId)` — it votes only for the canonical block at that height, so a
Byzantine leader cannot make honest nodes vote for a fabricated block. Commit is **observational**
(feature-25 stays authoritative).

⚠️ **RUNTIME-PARTIALLY-VALIDATED:** compiled + unit/simulation-tested, **plus a real 4-node docker
cluster smoke run** (`FourNodeHotStuffTestSuite`). What the live run established:
- **Non-destructive / no crash:** with `dcc.hotstuff.enabled = true` the 4 nodes start, the coordinator
  initialises (`T2 HotStuff coordinator ENABLED`), blocks + microblocks keep being produced, and there
  are **zero** exceptions, decode/serialization errors, or netty pipeline failures. Enabling HotStuff
  behaves **identically** to the disabled baseline on the same cluster — the gated wiring does not alter
  node behaviour, which is exactly what step 5 must confirm before mainnet.
- **Finality-advances assertion is GREEN on a properly-resourced runner.** On CI (ubuntu-latest, PR #17
  run `29162581781`) `FourNodeHotStuffTestSuite` **passed**: *"a HotStuff-enabled 4-node cluster finalizes
  on every node without halting or forking (12.8s)."* So with HotStuff enabled, feature-25 finality keeps
  advancing on all 4 nodes — confirmed on real nodes, not just locally.
  On a resource-constrained host (Docker capped at ~7.75 GiB shared with other containers) the same suite
  is flaky: the node-it peer mesh fragments (asymmetric peer suspensions; some nodes hold only 2–3 of 3
  links), so cross-node endorsements miss the 2/3 quorum and `finalizedHeight` stalls at 1. **That was
  reproduced with HotStuff DISABLED too** → a node-it mesh/resource issue, **not** caused by HotStuff
  (`known-peers` is correctly wired — Docker.scala:215). Lesson: run step 5 where node-it has real memory
  headroom, never a memory-pressured laptop sandbox.
- **Harness prerequisite fixed:** node-it published the image's EXPOSE'd P2P port (6868) instead of the
  node's configured port (`dcc.network.port = 6863`), NPE-ing on every container start. Fixed in
  Docker.scala (publish the resolved-config ports; wait for host bindings). Benefits all node-it suites.

The view=height mapping, cross-period state, and proposing still need a **green** multi-node run +
external audit before `hotstuff.enabled` is ever set true on mainnet.

**Remaining:** a clean green step-5 finality run (adequately-resourced node-it) + testnet soak; external audit.

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
- **Step 5 (smoke green):** `node-it` multi-node suites (pattern: `node-it/.../sync/finalization/*`).
  `FourNodeHotStuffTestSuite` **passes on CI** (ubuntu-latest, PR #17) — the enabled wiring is
  non-destructive and feature-25 finality advances on all 4 nodes with HotStuff on. (Flaky only on a
  memory-pressured host; see §5 — not a HotStuff issue.) **Update (2026-07-26):** crashed-leader and
  network-partition are done — the suite's own three cases now cover happy-path, smallest-stake-node
  crash (survivors' finalized height never regresses, reconverges on restart), and real Docker-level
  partition of the smallest-stake node via `Docker.disconnectFromNetwork` (majority's finalized height
  never regresses during isolation, reconverges on heal). This is provably sufficient for a partition
  under a SINGLE FIXED committee: any two ≥2/3-stake quorums of the same committee must share ≥1/3 of
  it, so a static-committee partition can never yield two independent quorums for different blocks —
  ordinary BFT quorum-intersection math, not something a bigger seed sweep would add confidence to.
  That quorum-intersection guarantee does NOT extend across two DIFFERENT committees (e.g. a full
  validator-set rotation between committed-generators periods) — `HotStuffCrossEpochForkSpecification`
  (`node/tests/.../consensus/hotstuff/HotStuffCrossEpochForkSpecification.scala`) originally exhibited
  two disjoint, entirely honest 2/3-quorums independently certifying different blocks at an identical
  (view, height), with zero shared signers, invisible to `HotStuffSafety.equivocators` (which only
  catches a single voter double-signing). **Update (2026-08-03): closed at the unit layer.** The chosen
  design was the wire-format committee-identity binding + coordinator-level transition-gating rule
  option (not the full joint-consensus two-phase membership protocol, judged disproportionate to the
  hazard): a `committeeEpoch` field (schema 1.6.5, = `state.GenerationPeriod.index`) is now folded into
  the signed vote/QC bytes (`HotStuffQuorum.voteMessage`), and `HotStuffQuorum.acceptableCommitteeEpoch`
  gates `HotStuffEngine.onQC`/`onProposal` to only accept the current epoch or the immediately-preceding
  one. The same spec file now also proves the fix (labeled-epoch votes can no longer be merged/relabeled
  across epochs; `HotStuffEngine.onQC` rejects an out-of-window epoch end-to-end) alongside the original,
  still-passing, unlabeled-vote hazard tests. 114/114 HotStuff-area tests green. Two things this unit-level
  closure does **not** yet cover: the `protobuf-schemas` 1.6.5 change is not yet published to Maven
  Central (local-`.m2`-only today), and no live multi-node Docker run has exercised a real committee-epoch
  transition (only unit/DST simulation) — both need to happen (the latter on CI/testnet, given local
  `node-it` Docker's documented memory/flakiness constraints) before this can be called closed end-to-end.
  **Update (2026-08-04): a follow-up adversarial review found the 2026-08-03 fix's OWN wiring introduced a
  distinct, previously-uncharacterized LIVENESS gap** — `committeeEpoch` was derived from the signing
  replica's own live chain tip (`blockchainUpdater.currentGenerationPeriod`) rather than the vote's TARGET
  height, so two fully honest, synced replicas voting the identical `(view, phase, blockId, blockHeight)`
  target could sign *different* epochs if their local tip crossed a generation-period boundary at slightly
  different moments (ordinary propagation skew, not an attack), and `formQC`'s epoch-sensitive
  `sameTarget` check then permanently stalled that target. Fixed the same day by deriving `committeeEpoch`
  as a pure function of the target height (`HotStuffCoordinator.Enabled`'s `committeeEpochOf: Int => Int`
  parameter, `blockchain.generationPeriodOf(targetHeight).index` in `Application.scala`) at every
  vote-signing call site, plus an epoch-aware `(voterIndex, committeeEpoch)` dedup in
  `HotStuffVotePool.onVote` as defense-in-depth. See `HotStuffCrossEpochLivenessSpecification` for the
  reproduction and fix proof; also merged to `main` @ `9c49632398`. `protobuf-schemas` 1.6.5 is now
  **published to Maven Central** (verified live at `repo1.maven.org/maven2/io/decentralchain/protobuf-schemas/1.6.5/`,
  200 OK 2026-08-04) — no longer a CI-build blocker. The one still-open item from both the fork-hazard and
  liveness fixes is unchanged: no live multi-node Docker evidence of an actual committee-epoch *transition*
  yet, only unit/DST simulation.
  A genuine Twins-style equivocating-validator node-it test (one node double-
  voting into two live partitions) remains separate future work — needs a purpose-built
  fault-injection node image, per `FourNodeHotStuffTestSuite`'s own doc comment. BFT safety/liveness
  only manifest across ≥4 nodes; no unit test substitutes for the node-it layer, but the cross-epoch
  hazard above is fully demonstrated at the unit layer and does not need one for that part.

## 7. Wire format (schemas 1.6.5, `dcc/block.proto`)
`HotStuffPhase{UNSPECIFIED,PREPARE,PRE_COMMIT,COMMIT}`;
`HotStuffVote{view, phase, block_id, block_height, voter_index, signature(BLS), committee_epoch(7)}`;
`QuorumCertificate{view, phase, block_id, block_height, signer_indexes[], aggregated_signature(BLS), committee_epoch(7)}`;
`HotStuffProposal{view, block_id, justify:QuorumCertificate}`.
**1.6.4 is published** — live on Maven Central (`io.decentralchain:protobuf-schemas:1.6.4`, autoPublish
via central-publishing-maven-plugin); node-scala CI resolves it. (Was the last pre-merge blocker.)

**1.6.5 (T10 fix, 2026-08-03) adds `uint32 committee_epoch = 7` to both `HotStuffVote` and
`QuorumCertificate`** — see the T10 entry in `docs/hotstuff-audit-readiness.md` §4/§7 for the full
design rationale (identifier reused from `state.GenerationPeriod.index`; transition-gating rule in
`HotStuffQuorum.acceptableCommitteeEpoch`). proto3 field-7 addition is wire-backward-compatible by
construction (older peers omit it, decoding as `0`, which is also this field's default everywhere in
`node-scala`). **Not yet published**: the schema change is committed on the `DecentralChain` repo
(`packages/sdk/protobuf-schemas`, branch `consensus/committee-epoch-wire-field`, commits `a5ea11594`
"feat(hotstuff): add committee_epoch field to HotStuffVote/QuorumCertificate (schema 1.6.5)" and
`50376fcef` "chore(hotstuff): regenerate TS protobuf stubs for committee_epoch field") but only
installed to local Maven (`~/.m2`) so far — it needs the same credentialed Maven Central publish step
that 1.6.4 required (§8 Open Gates item 1) before `node-scala`'s `consensus/fix-cross-epoch-fork`
branch can build in CI or merge.

## 8. Open gates
1. ✅ Publish `protobuf-schemas` 1.6.4 (credentialed release) — **done**, live on Maven Central.
2. ✅ Step 4c shell landed + step-5 smoke **green on CI** (`FourNodeHotStuffTestSuite` passes on
   ubuntu-latest, PR #17: 4-node cluster finalizes with HotStuff enabled).
   ✅ Crashed-leader and static-committee partition scenarios (see §6, 2026-07-26 update).
   ✅ Cross-committee-epoch fork hazard (T10) — **closed at the unit layer, 2026-08-03; a related
     liveness gap found in the same fix closed 2026-08-04** — wire-format `committeeEpoch` binding
     (schema 1.6.5) + `HotStuffQuorum.acceptableCommitteeEpoch` transition-gating rule, proven by
     `HotStuffCrossEpochForkSpecification`'s fix-side tests and `HotStuffEngine.onQC` end-to-end
     rejection; the 2026-08-04 fix derives `committeeEpoch` from the vote's target height instead of the
     signer's live tip (`HotStuffCrossEpochLivenessSpecification`). All merged to `main` @ `9c49632398`.
     See `docs/hotstuff-audit-readiness.md` T10 entry for full detail. `protobuf-schemas` 1.6.5 is now
     **published to Maven Central** (verified 2026-08-04) — no longer a CI-build blocker. **Still open:**
     no live multi-node Docker evidence of an actual committee-epoch transition yet (unit/DST-simulation
     only) — needs a `node-it` scenario on CI/testnet.
   ✅ Testnet deployed, `dcc.hotstuff.enabled=true` + `dcc.hotstuff.authoritative=true` live on all 4
     testnet nodes (2026-08-03/04, image `sha-9c49632`), by explicit human decision ahead of the audit,
     scoped to testnet only. `GET /blocks/height/finalized` confirmed advancing via this mechanism.
   ◻ Remaining: Twins-style equivocating-validator node-it scenario (needs a fault-injection node
     build); a formal multi-day soak record for the reworked/authoritative model (crash/partition/
     equivocation) is not yet documented despite the live deployment above.
3. ◻ External audit before `hotstuff.authoritative = true` is ever considered on mainnet (testnet-only
   today, does not require audit sign-off per the explicit human decision that scoped it there).
