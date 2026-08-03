# T2 HotStuff — Step-5 Findings & Rework Design

> **What this is.** The record of the first real multi-node (step-5) run of T2 HotStuff on the live
> testnet, the bugs it surfaced and fixed, the one that remains, and the **architectural rework** the
> evidence points to. Read alongside — and it updates the forward-looking parts of —
> [`hotstuff-integration-design.md`](./hotstuff-integration-design.md) (implementation SSOT),
> [`hotstuff-security-review.md`](./hotstuff-security-review.md) (internal review + findings), and
> [`hotstuff-audit-readiness.md`](./hotstuff-audit-readiness.md) (audit scope/evidence).
>
> **Bottom line.** T2 was documented "done" but had never actually run across ≥2 nodes. Step-5 showed it
> did **nothing** on the wire and took **five** distinct fixes to get validators voting on the same block
> AND forming QCs. As of **2026-07-13 QC formation is confirmed live**: all three testnet nodes commit in
> lockstep (`hotStuffFinalizedHeight` = tip − settled-depth, advancing). The final blocker was a **local
> vote-message `blockHeight` mismatch** (fix #5), NOT the view model — the votes *did* co-reside; the pool
> rejected them on a height field mismatch and swallowed it silently. The `view = block-height` model in
> `hotstuff-integration-design.md §5` therefore does **not** prevent commit; a proper pacemaker (§3/§4
> Option A) is now an optional upgrade for responsiveness + view-change, not a prerequisite for finality.
>
> **No production risk:** HotStuff commit is observational; feature-25 Deterministic Finality is
> authoritative and healthy throughout. `dcc.hotstuff.enabled` is `false` by default.

## 1. Step-5 method
Deployed the gated build to the live testnet (VPS main node + LKE gen-0/gen-1/val-0 — see infra
`clusters/testnet/TOPOLOGY.md`), enabled `dcc.hotstuff.enabled=true`, exposed observational
`hotStuffFinalizedHeight` on `/node/status`, and added INFO instrumentation to the coordinator
(`onLeaderTurn` / `onProposal` / `castVotes` / `onVote` / `onQC`). Inspected via the read-only
`hotstuff-status.yml` artifact (image + `/node/status` + logs per gen node). This is what a
resourced step-5 harness must automate; the local `node-it` suite could not surface any of the below
(it asserts feature-25 non-destructiveness, not HotStuff commit, and was mesh-flaky).

## 2. Findings

### Fixed (root-caused live, each with a regression test, merged to `dev`)
| # | Bug | Root cause | Fix |
|---|-----|-----------|-----|
| 1 | No HotStuff message ever left a node | `MessageCodec.encode` is a hand-written per-type dispatch with a `throw "unsupported"` default; HotStuff types had no case (EndorseBlock did — hence feature-25 worked, T2 didn't) | add the 3 encode cases; `MessageCodecSpec` round-trip test |
| 2 | Votes fragmented across blockIds at one view | `view = tip height`, proposing the **liquid** `lastBlockInfo.id`, which changes as microblocks append and differs across nodes → many blocks per view | `view = settled height (tip−depth)`, propose the canonical `blockchain.blockId(s)`; replica `proposalValid` guard = only vote the canonical block at that height |
| 3 | Steady-state stall after a catch-up burst | settled depth = 1 too shallow: a ~1-block-behind replica still has the view's height as its *liquid* tip → guard rejects → no quorum | configurable `dcc.hotstuff.settled-depth` (default 3 > inter-node tip skew) |
| 4 | Each validator saw <2/3 of votes | `allChannels.broadcast` reaches **direct peers only**; in the hub topology (gen↔main, not gen↔gen) votes didn't reach all. feature-25 relays ("will be shared"); HotStuff didn't | gossip each HotStuff message once (relay to peers except sender) with a bounded dedup cache |
| 5 | **QC never formed despite converged votes** (the last blocker) | A replica handling a received proposal voted with `blockHeight = blockchainUpdater.height` (its LOCAL tip, `≈ s + settledDepth`) while the leader voted with `blockHeight = s` (settled view). The vote is a BLS signature over `voteMessage(view, phase, blockId, blockHeight)`, and `formQC` requires an **identical `blockHeight`** across all votes. Votes shared the `(view,phase,blockId)` bucket and passed the voter-count quorum, but `formQC` returned `Left` on the height mismatch — swallowed in `HotStuffVotePool.onVote`'s branch mislabeled "unreachable". | replica votes over `p.view` (== settled height); pool-level instrumentation + guarded WARN safety-net in `HotStuffCoordinator.onVote`; regression tests in `HotStuffVotePoolSpecification` (mixed-height → no QC; same-height → QC). Corrected the false "unreachable" comment. |

After #1–#4: **votes converge** — all three validators vote the same `(view, block)`. After #5: **QCs form
and blocks commit** — confirmed live 2026-07-13, all three nodes advance `hotStuffFinalizedHeight` in
lockstep at tip − settled-depth.

### ~~Open~~ — RESOLVED (fix #5 above)
The QC blocker was diagnosed by **static analysis of the QC-formation path**, not another deploy-guess.
The earlier hypothesis here — "no single current view all replicas dwell on, so votes don't co-reside" —
was **wrong**: the votes *did* co-reside in one pool bucket and *did* reach the stake quorum; `formQC`
rejected them on a `blockHeight` field the voter-count quorum check ignored, and the pool discarded that
`Left` silently. The requested pool-level instrumentation was added anyway (it now reports accumulated
signers + stake-quorum per target and a guarded WARN if quorum is met yet no QC forms) and would have
pinpointed this directly. **Lesson: a `Left`/"unreachable" branch on a safety-critical path must be
logged, never swallowed.**

## 3. Root architectural finding
The implemented model (`hotstuff-integration-design.md §5`):
- **view = block height h**, **leader(h) = FairPoS generator of block h**, one 3-phase instance per height.

Every height is its own view with its own leader, and **many views run concurrently** — each node is
busy leading the heights it forged while also replying to others. Classic 3-phase/pipelined HotStuff
(Abraham et al.) assumes the opposite:
- a **monotonic view counter** advanced by a **pacemaker**,
- **one rotating leader per view**, **one active view at a time**, one proposal per view,
- all replicas focused on the current view so votes concentrate → QC → pipeline to the next view.

Mapping "view" onto "block height with the block's own forger as leader" removes the single-active-view
property HotStuff depends on. #2–#4 were consequences of forcing that mapping onto an NG chain.

> **Correction (2026-07-13).** An earlier version of this section claimed "the open QC issue is the same
> [architectural] mismatch at the voting layer." That was **wrong** — the QC blocker was a local vote-message
> `blockHeight` mismatch (finding #5), and once fixed the current model **commits across all nodes**. The
> concurrent-view model is therefore not a correctness blocker for observational finality. It remains
> sub-optimal for *responsiveness* and lacks a real view-change/pacemaker, so Option A below is a genuine
> improvement — but it is an **optional upgrade driven by product need, not a prerequisite for T2 to work.**

### What is salvageable
- ✅ **Pure core** (`HotStuffQuorum`, `HotStuffSafety`, `HotStuffVotePool`, `HotStuffEngine`) — sound,
  deterministic, unit-tested (incl. the in-process 4-node simulation, which *does* form QCs because it
  drives a single view). Keep.
- ✅ **Transport + gossip** (fixes #1, #4) — required regardless of the view model. Keep.
- ✅ **BLS committee/PoP** reuse from feature-25 — correct. Keep.
- ♻️ **The shell view/leader/pacemaker mapping** (`Application` wiring, `HotStuffPacemaker` usage) — this
  is what must be reworked.

## 4. Rework options (for the engineering + audit track)

**Option A — Proper pacemaker-driven HotStuff.** Introduce a real view counter + pacemaker: one active
view at a time, deterministic rotating leader (e.g. round-robin over the committed-generator committee),
the leader proposes the highest settled key-block it has, replicas vote, 3-chain pipelines. Decouples
"view" from "height". Largest change; the core modules mostly stay, the shell is rewritten. Delivers true
responsive BFT finality + view-change liveness.

**Option B — Extend feature-25 instead of a second protocol.** Feature-25 already gives **single-round,
2/3-committed-stake, BLS-aggregate deterministic finality** and is live + working. Its ~100-block lag is
a *config* choice (`generation-period-length=100`), not a protocol limit. Lower-latency "fast finality"
may be reachable by tuning/extending feature-25 (shorter periods / per-block endorsement rounds) using
primitives that are already reviewed — avoiding a parallel HotStuff stack, its pacemaker, and its audit
surface entirely. **Recommended to evaluate first**: it may satisfy the T2 goal at a fraction of the risk.

**Decision needed (owner: consensus eng + external auditor):** does DCC need HotStuff's specific
guarantees (responsiveness + view-change) beyond what a tuned feature-25 provides? If yes → Option A with
a proper pacemaker. If no → Option B, and T2-as-HotStuff is descoped.

## 5. Enable-gate impact (updates `hotstuff-audit-readiness.md §8`)
- [x] **Resolve §2-Open (QC formation) — DONE 2026-07-13** (fix #5); commits live on all nodes.
- [ ] **Sustained soak (RUNBOOK Scenario E)** — the remaining evidence gap: `hotStuffFinalizedHeight`
      tracks tip continuously across restarts / crash / partition over multiple days on all nodes,
      recorded with REAL results.
- [ ] §4 A-vs-B decision — now a pure *product* call (commit works either way); if Option A, add the
      pacemaker/single-active-view rework + adversarial tests (leader rotation, view change,
      concurrent-height safety) + fresh internal review before re-running step-5.
      **Progress (branch `consensus/hotstuff-pacemaker-rework`, not yet merged):**
      `HotStuffPacemaker.leaderFor`/`onTimeout` existed as pure, unit-tested primitives but were never
      actually wired as the shell's view-driver -- `HotStuffCoordinator.onTimeout()` only bumped
      `EngineState.pacemaker` with nothing observing it, and Application.scala picked the proposer
      purely via a FairPoS-forger check on a settled height. Added `HotStuffCoordinator.onRoundTimerTick`
      (real leader-timeout detection: only advances the view + triggers the newly-rotated leader to
      auto-propose via an injected `blockSource` when NO QC formed since the previous tick) and
      `currentView`. Pure core (Engine/Safety/Quorum/VotePool) untouched throughout.
      **View/height decoupling (this pass):** the shell's `proposalValid` guard originally read
      `blockchainUpdater.blockId(view).contains(blockId)`, assuming `view == the settled height of the
      proposed block` -- the same coupling findings #2/#5 above already had to fix once, reintroduced
      here for a genuine pacemaker view-change (view advances independently of height on a
      leader-timeout). A RED test (`HotStuffViewChangeSpecification`, commit `4bf3217e87`) proved this
      concretely: wiring a height-coupled `proposalValid` + a real `blockSource` and driving a
      leader-timeout produced a proposal that was broadcast but never self-voted ("ListBuffer() was
      empty") -- a silent liveness gap, not a compile-time hypothetical. Fix (commit `fd5e4ea15e`):
      `proposalValid` changed from `(Int, BlockId) => Boolean` (view, blockId) to `BlockId => Boolean` --
      it now answers "does this replica recognize blockId on its own chain" (chain-membership, via
      `blockchainUpdater.heightOf`), with no view involved at all. View-ordering/lock safety is
      unaffected -- it was always `HotStuffSafety.safeToVote`'s job (extends-locked-branch / newer
      justify-QC), unconditionally enforced inside `HotStuffEngine.onProposal` after this guard passes.
      The message-observer's blockHeight (used for the vote message, and required identical across all
      votes for a target by `HotStuffQuorum.formQC`) is now derived from the proposal's own `blockId`
      (`blockchainUpdater.heightOf(p.blockId)`) instead of from `p.view`, so every honest replica
      computes the same value regardless of view -- preventing finding #5's mismatch class by
      construction rather than convention. `blockSource` is wired for real in `Application.scala`:
      on a leader-timeout, the newly-rotated leader (re-)proposes the current settled tip (same block
      the per-height happy path would pick next). Safety reasoning for wiring this on a live network
      (testnet has `dcc.hotstuff.enabled=true`): T2 stays strictly observational (only
      `hotStuffFinalizedHeight` moves; feature-25 remains sole authoritative finality); worst case if
      replicas' view counters diverge is a non-quorate round (liveness, not safety) -- functionally
      identical to any other non-quorate HotStuff round today. 89/89 relevant unit/DST/message/settings
      specs green (79 hotstuff + 10 messages/settings), 0 regressions; `node` module compiles clean.
      **Deferred (not done in this pass, genuine open follow-up):** `blockSource`'s re-proposal strategy
      is deliberately the *simple* case only -- always re-derive "the current settled tip" fresh. It does
      NOT implement the more sophisticated classic-HotStuff pacemaker liveness optimization of
      re-proposing a specific prior not-yet-QC'd proposal extending the locked/prepareQC branch (which
      would require exposing `HotStuffCoordinator`'s internal safety state, e.g. `prepareQC`, through a
      new API surface -- a real design question, not mechanical, left for whoever picks this up next).
      Also still open: 3-chain pipelining across genuinely sequential views, and Task 8 Steps 3-4
      (bounding `HotStuffVotePool.seenCommittees`, re-running node-it finalization suites on a real
      cluster). None of this is audit-ready until those are addressed and external audit runs.
- [ ] External audit before any mainnet enable (of the shipped model, or the reworked one if Option A).
- The five fixed transport/model/height bugs and the observability (`hotStuffFinalizedHeight`,
  instrumentation) carry forward regardless of A/B.
