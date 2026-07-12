# T2 HotStuff — OWN-IT-TO-COMPLETION HANDOFF (SSOT)

> **You are inheriting T2 HotStuff. This is the single entry point. Read it fully, then drive T2 to a
> decision and to done.** It is written to be handed to an engineer or pasted as an agent prompt. It does
> NOT duplicate the deep docs — it orients you, gives the exact playbook, and lists every gotcha already
> paid for in blood. Depth lives in the five docs in §2.
>
> **Mission:** get DCC fast-finality *actually working, tested, soaked, and externally audited* so
> `dcc.hotstuff.enabled=true` is safe on mainnet — OR make the evidence-based call that feature-25
> (already live) covers the need and formally descope T2-as-HotStuff. Either outcome is "done."
>
> **Prime directive:** do not trust status claims — verify on a real ≥3-node network. T2 was documented
> "done/soak-PASSED" while it had never run across two nodes and did nothing on the wire. Everything below
> was established empirically on the live testnet, 2026-07-12.

---

## 1. TL;DR state (as of 2026-07-12)
- **Works:** feature-25 Deterministic Finality (single-round BLS, 2/3 committed stake) is live, authoritative, healthy. HotStuff commit is **observational** → it can NOT harm the chain. `dcc.hotstuff.enabled` defaults **false**.
- **HotStuff progress this session:** went from *silent on the wire* → *all validators converge voting the same (view, block)* via **four** fixes (transport, view-model, settled-depth, gossip — see §3 & the rework doc).
- **OPEN blocker:** QC still does not form / no sustained commit despite converged votes. `hotStuffFinalizedHeight` reached 44103 once (during catch-up) then stuck.
- **Decision gate (do this FIRST, §5):** rework as proper pacemaker/single-active-view HotStuff (**Option A**) vs extend the already-working feature-25 for low-latency finality and descope HotStuff (**Option B, recommended to evaluate first**).
- **Branches:** all work on node-scala `dev` (via `feature/hotstuff-t2`, kept in sync). infra on `main` (Flux GitOps). Nothing on mainnet.
- **Latest testnet image:** `ghcr.io/decentral-america/node-scala:dev-hotstuff` = `sha256:818179f9…` (all 4 fixes + INFO instrumentation), deployed to all testnet nodes.

## 2. Read these (do not re-derive)
| Doc | Purpose |
|-----|---------|
| `docs/hotstuff-step5-findings-and-rework.md` | **START HERE after this file.** The 4 fixes, the open QC issue, the root architectural finding, salvageable vs rework, Options A/B. |
| `docs/hotstuff-integration-design.md` | Implementation SSOT — protocol↔code map, module inventory, step-4c shell seams. (Marked REWORK PENDING.) |
| `docs/hotstuff-security-review.md` | Internal adversarial review + findings #1–5 with current status. |
| `docs/hotstuff-audit-readiness.md` | External-audit scope, trust model, threat matrix (T1–T9), evidence index, enable-gate checklist. |
| `../../infra/clusters/testnet/TOPOLOGY.md` | The testnet node inventory + the two deploy substrates + the one release workflow. **Read before any deploy.** |
| `../../infra/clusters/testnet/RUNBOOK.md` | Scenario E soak plan (planned, not run) + generator commitment. |

## 3. The four fixes already landed (keep these; they are correct regardless of A/B)
1. **Transport** — `network/MessageCodec.scala` `encode` had no case for HotStuff types (default `throw`) → outbound dropped. Added 3 cases; `network/MessageCodecSpec` round-trip test.
2. **View model** — was `view=tip height` on the *liquid* block id (diverges across nodes). Now `view = tip − settledDepth`, target = canonical `blockchainUpdater.blockId(s)`; replica `proposalValid` guard (vote only the canonical block at that height). `Application.scala` propose hook + `HotStuffCoordinator.Enabled`.
3. **settled-depth** — configurable `dcc.hotstuff.settled-depth` (default 3) in `settings/HotStuffSettings.scala` + infra node configs. Must exceed inter-node tip skew.
4. **Gossip** — `Application.scala` HotStuff message subscriptions now relay each message once (dedup cache) so votes reach ALL validators, not just direct peers (mirrors feature-25 "will be shared").

**Salvageable core (sound, unit-tested — do NOT rewrite):** `consensus/hotstuff/HotStuff{Quorum,Safety,VotePool,Engine}.scala`, the in-process 4-node sim (`HotStuffSimulationSpecification`), BLS committee/PoP reuse from feature-25. **What needs rework:** the shell view/leader/pacemaker mapping in `Application.scala` + how `HotStuffPacemaker` is (not) used.

## 4. The open blocker — precise next diagnostic
Symptom: all 3 validators vote the same `(view, phase, block)` (confirmed in logs) but `QC=false`, no `observational commit`, `hotStuffFinalizedHeight` not sustaining. BLS is almost certainly fine (`HotStuffQuorum.verifyVote` uses `BlsUtils.verifyBasic` — the *same* primitive feature-25 verifies endorsements with). Suspect: no single *current view* all replicas dwell on, so no node holds ≥2/3 for one target *simultaneously* — i.e. a symptom of the §3-#2 architecture. **Before another cycle, add pool-level instrumentation:** in `HotStuffVotePool.onVote`, log the accumulated signer set + stake vs the 2/3 threshold per `(view,phase,blockId)`. That single data point decides "votes don't co-reside on a node (model problem → Option A/B)" vs "quorum/pool miscount (local bug → fix + done)."

## 5. DECISION FIRST (don't code until this is made)
Per `hotstuff-step5-findings-and-rework.md §4`:
- **Option A — proper HotStuff:** pacemaker + monotonic view counter + one rotating leader + single active view + pipelined 3-chain, decoupled from block height. Largest change; core modules mostly stay, shell rewritten. Delivers responsiveness + view-change liveness.
- **Option B — extend feature-25 (evaluate first):** feature-25 already gives single-round 2/3 deterministic finality; its ~100-block lag is a *config* (`generation-period-length=100`), not a protocol limit. Tuning/extending it (shorter periods / per-block endorsement) may deliver "fast finality" with already-reviewed primitives and no second protocol/pacemaker/audit surface.
- **Question for consensus-eng + auditor:** does DCC need HotStuff's specific responsiveness + view-change beyond a tuned feature-25? Yes → A. No → B, descope T2-as-HotStuff.

## 6. Execution playbook (the loop, with every gotcha)
**Repos:** each dir under `Ecosystem/` is its own git repo. `node-scala` (code) + `infra` (deploy, Flux). Parent gitignores `Ecosystem/`.

**Code change → validate → ship (node-scala):**
1. Edit on `feature/hotstuff-t2`. `sbt "node/compile"` then `sbt "node-tests/testOnly <spec>"` (HotStuff specs live in the **`node-tests`** project, NOT `node`).
2. `sbt scalafmtAll` (CI enforces scalafmt 3.11.1; the tree was reformatted — keep it clean).
3. Commit (NO `Co-Authored-By` trailer — jourlez sole author). Merge `feature/hotstuff-t2` → `dev` (`git merge --no-ff`), push. Keep local `dev` synced (`git pull` before merge; it drifts because PRs merge on the remote).
4. Build image: `gh workflow run publish-node-scala.yml --repo Decentral-America/node-scala --ref dev -f docker-tags=dev-hotstuff` (~30–60 min). Get digest: `gh api /orgs/Decentral-America/packages/container/node-scala/versions --jq '[.[]|select(.metadata.container.tags[]?=="dev-hotstuff")][0].name'`.

**Deploy to testnet (infra) — ONE workflow does both substrates:**
5. `gh workflow run deploy-testnet-release.yml --repo Decentral-America/infra -f image_ref=dev-hotstuff -f deploy_vps=true`. It SSH-deploys the VPS main node AND opens a k8s pin PR (`pin-node-image-*`).
6. Merge the pin PR: `gh pr merge <N> --repo Decentral-America/infra --squash --admin` (infra `main` is branch-protected; admin-merge is the team norm).
7. Force the k8s roll: `gh workflow run cluster-diagnostics.yml --repo Decentral-America/infra -f network=testnet -f roll=true` (flux reconcile + rollout restart of the StatefulSets; Flux does NOT auto-roll reliably on the digest bump alone).

**Verify (NO log-scraping, NO REST polling loops):**
8. Public VPS: `curl -s https://testnet-node.decentralchain.io/node/status` → `hotStuffFinalizedHeight` present+advancing = committing (RESETS on node restart — take TWO spaced single reads to confirm it advances, never a poll loop).
9. k8s gen nodes (not public): `gh workflow run hotstuff-status.yml -f network=testnet` then `gh run download <RID> -n hotstuff-status` → read the **artifact** (image + `/node/status` + `[HotStuff]` logs). NEVER `gh run view --log` (burns REST).
10. Steady-state alerting is the deployed Prometheus/Grafana (`dcc_hotstuff_finalized_height`, `dcc_finality_lag`, `FinalizationStalled >250/15m`). Rely on it instead of polling.

**Rollback:** `git revert` the infra image-pin commit on `main` → Flux restores the prior digest; re-run the roll.

## 7. Environment gotchas (all learned the hard way)
- **No local cluster access:** `kubectl` has no context; `flux` not installed. All cluster ops go through infra workflows (they pull the LKE kubeconfig via the Linode API secret). Diagnose via artifacts, not kubectl.
- **GitHub API blips constantly here** — wrap every `gh` call in a retry-on-"error connecting" loop. `git push` over HTTPS is more reliable than `gh api`.
- **Two deploy substrates** (VPS via SSH + LKE via Flux) with no auto-sync — ALWAYS use `deploy-testnet-release.yml` for image changes, never hand-edit `nodes.yaml` or run a single-substrate workflow (that caused a half-deploy). See TOPOLOGY.md.
- **Node version string doesn't change** between builds (`v1.6.3`), so `/node/version` can't tell which image is running — use `hotstuff-status.yml` (shows the pod image digest) or the presence of `hotStuffFinalizedHeight`.
- **pureconfig requires every `dcc.hotstuff.*` key** (Scala case-class defaults are NOT honored on parse) — new settings MUST be added to the infra node configs (`nodes.yaml` ×3 + `node-config/testnet/dcc.conf`) or the node fails to parse.
- **node-it is mesh-flaky locally** (Docker capped ~7.75 GiB shared) — validate finality on CI/testnet, not the laptop. The node-it harness port-publish NPE is already fixed (`node-it/.../Docker.scala`).
- **node-scala `check-pr` is red repo-wide** on pre-existing `-Werror` deprecations + scalafmt debt (not our code); integration tests were decoupled via `if: ${{ !cancelled() }}`. Team admin-merges past check-pr.
- **`round-timeout` is a Duration** (`1200ms`), NOT the old `round-timeout-ms` int (that key is silently ignored). Fixed in configs; watch for regressions.

## 8. Key identifiers
- **Testnet nodes:** main/VPS `31RPEKcz71a3hdxt8z7qLhTpRMuRV2kUyr6` (SSH, public `testnet-node.decentralchain.io`, committee idx 0); gen-0 `31PmKNdHAU5sZbtg8TrzKh8WfE7E8xBc9WD` (LKE, idx 1); gen-1 `31dLhqhGoGVhtkf5msWFmgZn1ErrVR6b9qV` (LKE, idx 2); val-0 (LKE, non-mining). LKE cluster label `dcc-peer-testnet`, namespace `dcc`, pods `dcc-{gen-0,gen-1,val-0}-0`.
- **Config:** `dcc.hotstuff { enabled, round-timeout=1200ms, settled-depth=3 }`.
- **P2P message codes:** HotStuffVote=39, QuorumCertificate=40, HotStuffProposal=41 (`network/BasicMessagesRepo.scala`).
- **Schemas:** `io.decentralchain:protobuf-schemas:1.6.4` live on Maven Central (wire types).
- **Feature:** Deterministic Finality = feature 25.

## 9. Definition of done (mainnet enable-gate — full list in audit-readiness §8)
- [ ] §5 decision made (A vs B), recorded in the rework doc.
- [ ] If A: pacemaker/single-active-view rework + adversarial tests (leader rotation, view change, concurrent-height safety) + refreshed internal review.
- [ ] Sustained commit verified on testnet (`hotStuffFinalizedHeight` tracks tip continuously across restarts, all nodes) + a recorded multi-day soak with crash/partition/equivocation scenarios (RUNBOOK Scenario E — currently fiction, must become real).
- [ ] External third-party consensus audit sign-off on the **reworked** model.
- [ ] Decision + re-audit if HotStuff is ever made *authoritative* (raise finalized height on commitQC) — today it is observational only.
- [ ] Only then flip `dcc.hotstuff.enabled=true` on mainnet.

## 10. First actions for the inheritor
1. Read `hotstuff-step5-findings-and-rework.md`.
2. Add the §4 pool instrumentation, one build/deploy/verify loop → get the "votes co-reside or not" datum.
3. Make the §5 A-vs-B decision with consensus-eng + the auditor; record it.
4. Execute the chosen path against §9. Keep every doc in §2 updated as SSOT — do not let status drift from reality again.
