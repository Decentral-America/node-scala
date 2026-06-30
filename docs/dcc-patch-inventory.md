# DCC Patch Inventory

Audit of every DCC-exclusive commit on top of Waves `v1.3.5` in
[Decentral-America/DCC](https://github.com/Decentral-America/DCC) (`version-1.2.x` branch).

> Generated for **DCC-145** — _Extract and categorize the DCC patch from the current working node_

## Legend

| Category | Meaning |
|----------|---------|
| **Build/CI** | Build scripts, CI pipelines, packaging, Docker |
| **Branding** | Rename "Dcc" → "DCC" in user-facing strings, docs, APIs |
| **Protocol change** | Runtime behaviour: fee labels, asset-pair names, feature descriptions |
| **Chain ID/config** | Network config, sample config, chain parameters |
| **Documentation** | README, docs, swagger |

## Commit inventory

| SHA | Message | Files changed | Category | Port to node-scala? |
|-----|---------|---------------|----------|---------------------|
| `4a144bdbf` | first refactoring round change jar and debian name, checkPR still works | Jenkinsfile, build.sbt, docker/Dockerfile, docker/build-scripts/entrypoint.sh, docker/build-scripts/setup-node.sh, docker/dockerTestJenkinsfile, node-it/build.sbt, node-it/.../Docker.scala, node/build.sbt, node/decentralchain-sample.conf (renamed from dcc-sample.conf), node/src/main/resources/node-kamon.conf, Importer.scala, settings/package.scala, DccSettingsSpecification.scala, node/dcc-sample.conf, performance-test/Jenkinsfile, releaseJenkinsfile | Build/CI + Branding | **Yes** — rename artefact names (jar, deb), Docker references, drop old Jenkinsfiles. Already partially done in node-scala; verify parity. |
| `61f200973` | refactor api | .travis.yml, README.md, swagger.json, CompositeHttpService.scala | Branding + Documentation | **Yes** — swagger title/description rebrand to DCC; README rewrite; drop .travis.yml (already absent in node-scala). |
| `b77374b5c` | change DCC into DCC | 21 files across benchmark, node-it tests, FeeValidation.scala, InvokeDiffsCommon.scala, InvokeScriptTransactionDiff.scala, AssetPair.scala, ExchangeTransactionSpecification.scala, OrderJsonSpecification.scala | Branding + Protocol change | **Yes** — critical: the string `"DCC"` in `AssetPair`, `FeeValidation`, and `InvokeDiffsCommon` affects API output and log messages. Must port to node-scala equivalent files. |
| `daaf429d4` | make more changes to api | swagger.json | Branding | **Yes** — additional swagger DCC branding (host, basePath, info). |
| `3e14d2d08` | refactor feature | BlockchainFeature.scala | Branding | **Yes** — feature description string: "1000 DCC" → "1000 DCC". Cosmetic but user-visible in feature activation API. |
| `d2a79036b` | update docs and update version name | build.sbt, docker/README.md, lang/jvm/build.sbt, swagger.json, Constants.scala | Branding + Chain ID/config | **Yes** — `ApplicationName` and `AgentName` changed to "DCC" in Constants.scala; build version bumped; swagger info updated. Must port Constants change and version references. |
| `3ee55c988` | Merge branch 'wavesplatform:version-1.2.x' into version-1.2.x | (merge commit — 25 files from upstream Waves) | Build/CI | **No** — upstream Waves merge already incorporated into node-scala via the v1.6.x base. No DCC-specific content. |
| `88e2f122c` | Update decentralchain-sample.conf | node/decentralchain-sample.conf | Chain ID/config | **Yes** — sample config cleanup. Review and merge relevant settings into node-scala's sample conf. |
| `565ead4c1` | Update decentralchain-sample.conf | node/decentralchain-sample.conf | Chain ID/config | **Yes** — expanded sample config with full node settings. Port relevant network/mining/REST-API defaults to node-scala sample conf. |

## Summary

| Category | Count | Port? |
|----------|-------|-------|
| Build/CI | 2 | 1 yes, 1 no (merge) |
| Branding | 5 | 5 yes |
| Protocol change | 1 | 1 yes |
| Chain ID/config | 3 | 3 yes |
| Documentation | 1 | 1 yes |
| **Total unique commits** | **9** | **8 yes / 1 no** |

> Note: some commits span multiple categories; the primary category is listed.

## Port priority

1. **Constants.scala** (`d2a79036b`) — `ApplicationName = "DCC"`, `AgentName` — identity on the network.
2. **AssetPair / FeeValidation / InvokeDiffsCommon** (`b77374b5c`) — `"DCC"` → `"DCC"` in protocol-level strings.
3. **BlockchainFeature** (`3e14d2d08`) — feature description branding.
4. **Swagger / API branding** (`61f200973`, `daaf429d4`) — user-facing API documentation.
5. **Build artefacts** (`4a144bdbf`) — jar/deb/Docker naming.
6. **Sample config** (`88e2f122c`, `565ead4c1`) — node default configuration.

## Downstream tickets unblocked

- **DCC-146** — Apply Chain-ID / network config changes to node-scala
- **DCC-147** — Apply branding changes to node-scala
- **DCC-148** — Apply protocol-level changes to node-scala

---

## DCC-148 Audit Results

### Protocol change evaluation

The DCC repo contains **no true consensus-breaking protocol changes** on top of Waves v1.3.5.
All "protocol change" items are **string-level branding** — replacing `"DCC"` with `"DCC"` in
user-facing error messages, API responses, and feature descriptions. None affect block validation,
state transitions, or consensus rules.

### Features investigated

| Feature | Finding | Scope |
|---------|---------|-------|
| Inter-Chain Gateway | Not present in node code | Application layer (DApp/service) |
| Proof of Incentivized Sustainability | Not present in node code | Application layer or absent |
| Carbon Sequestration | Not present in node code | Application layer (data transactions) |
| Native Swap (AMM) | Not present in node code | Application layer (DApp) |

### Changes ported to `dev` (node-scala)

| File (node-scala v1.6.x) | Change | Source commit |
|--------------------------|--------|---------------|
| `Asset.scala` | `DccName = "DCC"` → `"DCC"` | `b77374b5c` |
| `FeeValidation.scala` | Fee error messages: `"DCC"` → `"DCC"` | `b77374b5c`, `4b26ead0d` |
| `InvokeDiffsCommon.scala` | Invoke fee error: `"DCC"` → `"DCC"` | `4b26ead0d` |
| `InvokeScriptDiff.scala` | Payment error: `"DCC"` → `"DCC"` | `4b26ead0d` |
| `BlockchainFeature.scala` | Feature description: `"1000 DCC"` → `"1000 DCC"` | `7222a3560` |
| Integration tests (14 files) | Asset pair / fee error strings updated | — |
| Unit tests (16 files) | Error message assertions updated | — |

### Intentionally not ported

| Item | Reason |
|------|--------|
| `EthOrders.scala` `"DCC"` | EIP-712 signing format — changing would break Ethereum wallet compatibility. Not present in DCC v1.3.5. |

### Test results

* `sbt node-tests/test` — **no new failures introduced**
* 5 pre-existing failures (from DCC-146/147 and JDK 25 incompatibility) remain unchanged

---

## 2026-06-28 to 2026-06-30 patches (T2 soak window)

Applied immediately before and during the T2 testnet soak. All patches are production-merged.

### BPS: dedup + upsert in `insert_blocks_or_microblocks`

| Field | Value |
|-------|-------|
| Repo | `Decentral-America/DecentralChain` (BPS) |
| Category | Bug fix / correctness |
| Status | Deployed |

**Problem:** Duplicate block/microblock rows could be inserted on rapid re-org or replay, causing constraint violations.
**Fix:** Changed the insert strategy to dedup before write and upsert on conflict, making the operation idempotent.

---

### BPS `fbece975a`: Loader.scala RocksDB re-seek bug fix

| Field | Value |
|-------|-------|
| Repo | `Decentral-America/DecentralChain` (BPS) |
| Commit | `fbece975a` |
| Category | Bug fix / correctness |
| Status | Deployed — type-19 enabled |

**Problem:** The Loader's RocksDB iterator did not re-seek to the correct position after a compaction or snapshot boundary crossing, causing the BlockchainUpdates extension (T0 DeterministicFinality feed) to stall.
**Fix:** Added explicit re-seek to the iterator before consuming the next batch. The fix also enables type-19 transaction processing in BPS.
**Observable effect:** T0 DeterministicFinality lag self-heals; `CurGens` and `NextGens` remain stable at 3.

---

### node-scala `ff9d86ae`: Loader.scala (BlockchainUpdates extension) re-seek fix

| Field | Value |
|-------|-------|
| Repo | `node-scala` |
| Commit | `ff9d86ae` |
| Category | Bug fix / correctness |
| Status | Deployed |

**Problem:** Mirror of the BPS Loader.scala issue. The BlockchainUpdates extension in node-scala had the same RocksDB re-seek omission, causing the extension to stop emitting events after iterator exhaustion.
**Fix:** Explicit re-seek added at the same iterator boundary as the BPS fix. Paired with `fbece975a` for full end-to-end coverage.

---

### Infra: `round-timeout-ms` 5000 → 1200 ms

| Field | Value |
|-------|-------|
| Scope | Infrastructure / node config |
| Category | Performance tuning |
| Status | Applied to all testnet nodes |

**Change:** Reduced `round-timeout-ms` from 5000 ms to 1200 ms on all generator and main nodes.
**Rationale:** Under a 3-generator testnet topology, 5000 ms produced unnecessary pause windows between mining rounds. At 1200 ms, T2 finality lag dropped to 0 and block production became continuous.

---

### Infra: T2 soak PASSED (all 4 phases)

| Field | Value |
|-------|-------|
| Scope | Infrastructure / QA |
| Category | Validation |
| Date | 2026-06-30 |
| Result | PASSED |

**Summary:** All 4 soak phases completed successfully:
1. Single-generator baseline — chain advancing, no stalls
2. Multi-generator handoff — `CurGens` / `NextGens` transitions correct
3. Round-timeout stress — 1200 ms sustained, T2 lag = 0
4. BPS replay integrity — no duplicate rows, type-19 events delivered

Chain height at soak end: **9733+**. T0 DeterministicFinality transient lag at block 9668 resolved via self-heal (Loader re-seek fix).
