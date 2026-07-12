# T2 HotStuff — Step-5 Findings & Rework Design

> **What this is.** The record of the first real multi-node (step-5) run of T2 HotStuff on the live
> testnet, the bugs it surfaced and fixed, the one that remains, and the **architectural rework** the
> evidence points to. Read alongside — and it updates the forward-looking parts of —
> [`hotstuff-integration-design.md`](./hotstuff-integration-design.md) (implementation SSOT),
> [`hotstuff-security-review.md`](./hotstuff-security-review.md) (internal review + findings), and
> [`hotstuff-audit-readiness.md`](./hotstuff-audit-readiness.md) (audit scope/evidence).
>
> **Bottom line.** T2 was documented "done" but had never actually run across ≥2 nodes. Step-5 showed it
> did **nothing** on the wire and took **four** distinct fixes just to get validators voting on the same
> block. QC formation still isn't confirmed live. The `view = block-height, leader = that height's
> forger` model in `hotstuff-integration-design.md §5` **fights** classic 3-phase HotStuff and should be
> reworked (§3 below) — or reconsidered against feature-25, which already provides 2/3 finality (§4).
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

After #1–#4: **votes converge** — all three validators vote the same `(view, block)` (verified in logs).

### Open
**QC still does not form / no sustained commit** despite converged votes. Three votes on one
`(view, phase, block)` = 100% stake should satisfy `HotStuffQuorum.formQC`, yet `QC=false` persisted.
The BLS path is almost certainly fine (`verifyVote` uses `BlsUtils.verifyBasic`, the *same* primitive
feature-25 verifies endorsements with successfully). The likely culprit is **timing/model**, not crypto:
with the current model there is no single *current view* all replicas dwell on, so a node rarely holds
≥2/3 of the votes for one target *at the same time* before moving on. Confirming this needs pool-level
instrumentation (log accumulated stake per target inside `HotStuffVotePool.onVote`) — but it is itself a
symptom of the architecture in §3.

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
property HotStuff depends on. #2–#4 were consequences of forcing that mapping onto an NG chain; the open
QC issue is the same mismatch at the voting layer. **This is a design problem, not another patch.**

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
- [ ] Resolve §2-Open (QC formation) — but only *after* the §4 decision, since Option B may retire it.
- [ ] If Option A: pacemaker/single-active-view rework + new adversarial tests (leader rotation, view
      change, concurrent-height safety) + fresh internal review before re-running step-5.
- [ ] External audit must review the *reworked* model, not the current shell.
- The four fixed transport/model bugs and the observability (`hotStuffFinalizedHeight`, instrumentation)
  carry forward regardless of A/B.
