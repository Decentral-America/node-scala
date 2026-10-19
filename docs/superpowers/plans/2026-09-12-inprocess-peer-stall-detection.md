# In-Process Peer Reconnection Stall Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect, in-process, the one real gap in the node's existing peer-reconnection logic — sustained 0-peer state caused by *suspension* exhaustion (all known candidates transiently suspended, not blacklisted for cause) — and self-heal it by clearing suspensions only, never blacklist entries. This replaces the destructive external `peer-watchdog.yml` (SSH + `docker kill` + wipe `peers.dat` + `docker restart`).

**Architecture:** `NetworkServer.scheduleConnectTask` (`NetworkServer.scala:241-256`) already reschedules itself every ~1-5s and already shortens its delay on `peerConnectionsMap.isEmpty`. Add a stall counter: track how many consecutive scheduled ticks have found `peerConnectionsMap.isEmpty` AND `peerDatabase.nextCandidate(...)` returned `None` (i.e., genuinely stuck, not just "haven't connected yet"). After a configurable threshold of consecutive stuck ticks, call a new `PeerDatabase.clearSuspension(): Unit` method (new primitive — does NOT exist today; `clearBlacklist()` is a different mechanism and must never be reused for this) and log the event. Blacklist entries are never touched automatically — blacklisting is for-cause and stays manual/deliberate, per the existing `blacklist-exempt` design rationale already documented at `PeerDatabaseImpl.scala:66-74`.

**Tech Stack:** Scala 3, existing Netty `workerGroup.schedule` timer loop, existing Guava `CacheBuilder`-backed `suspension` cache in `PeerDatabaseImpl`. No new dependencies.

## Global Constraints

- **Never conflate suspension and blacklist.** They are separate caches (`PeerDatabaseImpl.scala:31-32`) with separate intent: suspension is transient/fires-on-every-disconnect (`:100-109`), blacklist is for-cause and reason-carrying (`:84-98`). The new auto-heal path touches ONLY suspension.
- **Never auto-clear blacklist.** A prior (rejected, during planning) design called `clearBlacklist()` automatically — this is explicitly wrong per the audit: it would re-admit peers banned for cause, and does nothing for the actual suspension-exhaustion failure mode. Do not reintroduce this.
- The stall counter must reset to zero the moment `peerConnectionsMap` is non-empty again — no state should persist across a successful reconnect.
- Must not fire on a node that has no candidates configured at all (e.g. `known-peers = []` with `peers-exchange = no`, the main node's own deliberate config per the comment at `PeerDatabaseImpl.scala:71-73`) — clearing an empty suspension cache is harmless, but the log line must not read as an alarm in that expected-quiet configuration. Gate the log message on "was anything actually cleared."
- Every new/changed method needs a unit test; no placeholder tests.
- Config default for the stall threshold: match the external watchdog's existing debounce window (`peer-watchdog.yml`'s 900s/15-min debounce, `Ecosystem/infra/.github/workflows/peer-watchdog.yml:132`) so the in-process behavior is at least as conservative as what it replaces, not more aggressive.

---

## File Structure

- Modify: `node/src/main/scala/com/decentralchain/network/PeerDatabase.scala` — add `clearSuspension(): Unit` to the trait + `NoOp` implementation.
- Modify: `node/src/main/scala/com/decentralchain/network/PeerDatabaseImpl.scala` — implement `clearSuspension()`.
- Modify: `node/src/main/scala/com/decentralchain/network/NetworkServer.scala` — add stall-tick counter + threshold check in `scheduleConnectTask`.
- Modify: `node/src/main/scala/com/decentralchain/settings/NetworkSettings.scala` — add `peerStallThreshold: Int` (ticks) config field.
- Create: `node/tests/src/test/scala/com/decentralchain/network/peer/PeerDatabaseImplSpecification.scala` additions — tests for `clearSuspension()`.
- Create: `node/tests/src/test/scala/com/decentralchain/network/NetworkServerStallDetectionSpecification.scala` — tests for the stall-counter logic (extracted as a pure function first, per this codebase's established pattern of testing decision logic separately from the Netty wiring).
- Modify (retirement, final task): `Ecosystem/infra/.github/workflows/peer-watchdog.yml` — delete once soaked.

---

### Task 1: Add `clearSuspension()` to `PeerDatabase`

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/network/PeerDatabase.scala`
- Modify: `node/src/main/scala/com/decentralchain/network/PeerDatabaseImpl.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/network/peer/PeerDatabaseImplSpecification.scala`

**Interfaces:**
- Produces: `PeerDatabase.clearSuspension(): Unit` — new trait method, consumed by `NetworkServer` in Task 3.

- [ ] **Step 1: Write the failing test**

Open `node/tests/src/test/scala/com/decentralchain/network/peer/PeerDatabaseImplSpecification.scala` (already read during planning — it uses `withDatabase(settings)(f: PeerDatabase => Unit)` and a controllable `Ticker` for time). Add, inside the existing `"Peer database" - { ... }` block:

```scala
    "clearSuspension() removes a suspended address, leaving blacklist untouched" in withDatabase(settings1) { database =>
      database.suspend(address1)
      database.asInstanceOf[PeerDatabaseImpl].isSuspended(address1.getAddress) shouldBe true

      database.blacklist(address2.getAddress, "test reason")
      database.isBlacklisted(address2.getAddress) shouldBe true

      database.clearSuspension()

      database.asInstanceOf[PeerDatabaseImpl].isSuspended(address1.getAddress) shouldBe false
      database.isBlacklisted(address2.getAddress) shouldBe true // unaffected
    }

    "nextCandidate returns a previously-suspended address after clearSuspension()" in withDatabase(settings1) { database =>
      database.addCandidate(address1)
      database.touch(address1)
      database.suspend(address1)
      database.nextCandidate(Set()) shouldBe empty // still suspended

      database.clearSuspension()
      database.nextCandidate(Set()) should contain(address1)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.network.peer.PeerDatabaseImplSpecification"`
Expected: FAIL — `value clearSuspension is not a member of PeerDatabase`

- [ ] **Step 3: Add the trait method**

Edit `node/src/main/scala/com/decentralchain/network/PeerDatabase.scala`:

```scala
package com.decentralchain.network

import java.net.{InetAddress, InetSocketAddress}

import io.netty.channel.Channel

trait PeerDatabase {
  def addCandidate(socketAddress: InetSocketAddress): Boolean
  def touch(socketAddress: InetSocketAddress): Unit

  def nextCandidate(excluded: Set[InetSocketAddress]): Option[InetSocketAddress]

  def blacklist(host: InetAddress, reason: String): Unit
  def blacklistAndClose(channel: Channel, reason: String): Unit
  def isBlacklisted(address: InetAddress): Boolean
  def clearBlacklist(): Unit

  /** Clears the transient suspension cache ONLY — never touches blacklist. Suspension fires on
    * every connection close for any reason (see `PeerDatabaseImpl.suspend`'s doc comment) and is
    * NOT a for-cause ban, unlike blacklist (which is reason-carrying and deliberate). This exists
    * for `NetworkServer`'s in-process stall-detection self-heal
    * (docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md) — auto-clearing
    * blacklist instead would re-admit peers banned for cause, which must never happen automatically.
    */
  def clearSuspension(): Unit

  def knownPeers: Map[InetSocketAddress, Long]

  def detailedBlacklist: Map[InetAddress, (Long, String)]
  def detailedSuspended: Map[InetAddress, Long]

  def suspend(host: InetSocketAddress): Unit
}

object PeerDatabase {

  object NoOp extends PeerDatabase {
    override def addCandidate(socketAddress: InetSocketAddress): Boolean = true

    override def touch(socketAddress: InetSocketAddress): Unit = {}

    override def blacklist(host: InetAddress, reason: String): Unit = {}

    override def knownPeers: Map[InetSocketAddress, Long] = Map.empty

    override def nextCandidate(excluded: Set[InetSocketAddress]): Option[InetSocketAddress] = None

    override def detailedBlacklist: Map[InetAddress, (Long, String)] = Map.empty

    override def clearBlacklist(): Unit = ()

    override def clearSuspension(): Unit = ()

    override def suspend(host: InetSocketAddress): Unit = {}

    override def isBlacklisted(address: InetAddress): Boolean = false

    override val detailedSuspended: Map[InetAddress, Long] = Map.empty

    override def blacklistAndClose(channel: Channel, reason: String): Unit = channel.close()
  }
}
```

- [ ] **Step 4: Implement in `PeerDatabaseImpl`**

Edit `node/src/main/scala/com/decentralchain/network/PeerDatabaseImpl.scala`, add right after `clearBlacklist` (after line 182):

```scala
  def clearSuspension(): Unit = suspension.invalidateAll()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.network.peer.PeerDatabaseImplSpecification"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add node/src/main/scala/com/decentralchain/network/PeerDatabase.scala node/src/main/scala/com/decentralchain/network/PeerDatabaseImpl.scala node/tests/src/test/scala/com/decentralchain/network/peer/PeerDatabaseImplSpecification.scala
git commit -m "feat(network): add PeerDatabase.clearSuspension(), distinct from clearBlacklist"
```

---

### Task 2: Pure stall-counter decision logic

**Files:**
- Create: `node/src/main/scala/com/decentralchain/network/PeerStallDetector.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/network/PeerStallDetectorSpecification.scala`

**Interfaces:**
- Produces: `PeerStallDetector` — a small mutable-state-free-at-the-call-site class wrapping an internal counter, with methods `tick(hasConnections: Boolean, hasCandidate: Boolean): Boolean` (returns `true` exactly when the threshold is newly crossed on this call) and `reset(): Unit`. Consumed by `NetworkServer.scheduleConnectTask` in Task 3.

Extracted as its own small class (not inlined into `NetworkServer`) so the counter logic is unit-testable without spinning up Netty, matching this codebase's established pattern (`Miner.foldHotStuffConflicts`, `Miner.shouldSelfCommit` — pure logic pulled out of the stateful/IO-heavy class that uses it).

- [ ] **Step 1: Write the failing test**

Create `node/tests/src/test/scala/com/decentralchain/network/PeerStallDetectorSpecification.scala`:

```scala
package com.decentralchain.network

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Pure counter tests for `PeerStallDetector` — the in-process replacement for
  * `peer-watchdog.yml`'s external SSH+docker-restart remedy. See
  * docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md.
  */
class PeerStallDetectorSpecification extends AnyFreeSpec with Matchers {

  "PeerStallDetector" - {
    "never fires while connections exist, regardless of tick count" in {
      val detector = new PeerStallDetector(threshold = 3)
      (1 to 100).foreach { _ =>
        detector.tick(hasConnections = true, hasCandidate = false) shouldBe false
      }
    }

    "never fires while a candidate is available (not truly stuck, just mid-reconnect)" in {
      val detector = new PeerStallDetector(threshold = 3)
      (1 to 100).foreach { _ =>
        detector.tick(hasConnections = false, hasCandidate = true) shouldBe false
      }
    }

    "fires exactly once, on the tick where the threshold is first reached" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 2
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe true  // 3 -- fires
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 4 -- already fired, stays quiet
    }

    "resets and can fire again after a successful connection interrupts the stall" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false
      detector.tick(hasConnections = true, hasCandidate = false) shouldBe false // reconnected, resets
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1 again
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 2
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe true  // 3 -- fires again
    }

    "explicit reset() also clears the counter" in {
      val detector = new PeerStallDetector(threshold = 3)
      detector.tick(hasConnections = false, hasCandidate = false)
      detector.tick(hasConnections = false, hasCandidate = false)
      detector.reset()
      detector.tick(hasConnections = false, hasCandidate = false) shouldBe false // 1, not 3
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.network.PeerStallDetectorSpecification"`
Expected: FAIL — `object PeerStallDetector is not a member of package com.decentralchain.network` (file doesn't exist yet)

- [ ] **Step 3: Implement `PeerStallDetector`**

Create `node/src/main/scala/com/decentralchain/network/PeerStallDetector.scala`:

```scala
package com.decentralchain.network

/** Counts consecutive `scheduleConnectTask` ticks where this node has zero live peer connections
  * AND `PeerDatabase.nextCandidate` returned no candidate at all -- i.e. genuinely stuck, not just
  * "haven't finished a normal reconnect yet" (a candidate existing but the handshake still in
  * flight is NOT a stall). Once `threshold` consecutive such ticks are observed, `tick` returns
  * `true` exactly once (on the crossing tick) so the caller (`NetworkServer.scheduleConnectTask`)
  * can trigger a single `clearSuspension()` call rather than repeating it every tick. Any tick with
  * a live connection OR an available candidate resets the counter to zero.
  *
  * This is the in-process replacement for `peer-watchdog.yml`'s external SSH+docker-restart
  * remedy (docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md) -- unlike that
  * script, this NEVER touches blacklist, only suspension (see `PeerDatabase.clearSuspension`).
  *
  * Not thread-safe by design: `NetworkServer.scheduleConnectTask` is a single self-rescheduling
  * Netty timer loop (`workerGroup.schedule`), never invoked concurrently with itself.
  */
private[network] class PeerStallDetector(threshold: Int) {
  require(threshold > 0, s"threshold must be positive, got $threshold")

  private var consecutiveStuckTicks = 0
  private var alreadyFired          = false

  /** @return true exactly on the tick where the stall threshold is newly crossed. */
  def tick(hasConnections: Boolean, hasCandidate: Boolean): Boolean =
    if (hasConnections || hasCandidate) {
      reset()
      false
    } else {
      consecutiveStuckTicks += 1
      if (consecutiveStuckTicks >= threshold && !alreadyFired) {
        alreadyFired = true
        true
      } else false
    }

  def reset(): Unit = {
    consecutiveStuckTicks = 0
    alreadyFired = false
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.network.PeerStallDetectorSpecification"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add node/src/main/scala/com/decentralchain/network/PeerStallDetector.scala node/tests/src/test/scala/com/decentralchain/network/PeerStallDetectorSpecification.scala
git commit -m "feat(network): add pure PeerStallDetector counter for in-process peer-health self-heal"
```

---

### Task 3: Add `peerStallThreshold` config and wire `PeerStallDetector` into `NetworkServer`

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/settings/NetworkSettings.scala`
- Modify: `node/src/main/scala/com/decentralchain/network/NetworkServer.scala`
- Modify: `node/src/main/resources/application.conf`
- Test: `node/tests/src/test/scala/com/decentralchain/settings/NetworkSettingsSpecification.scala` (create if it doesn't exist — check first)

**Interfaces:**
- Consumes: `PeerStallDetector` (Task 2), `PeerDatabase.clearSuspension()` (Task 1).
- Produces: `NetworkSettings.peerStallThreshold: Int` config field; `NetworkServer.scheduleConnectTask` now self-heals.

- [ ] **Step 1: Check whether `NetworkSettingsSpecification` already exists**

Run: `find . -iname "NetworkSettingsSpecification.scala"`

If it exists, add tests there matching its existing style. If not, create it modeled directly on `MinerSettingsSpecification.scala`'s structure (same `ConfigFactory.parseString` + `ConfigSource.fromConfig(...).at("dcc.network").loadOrThrow[NetworkSettings]` pattern).

- [ ] **Step 2: Write the failing test**

```scala
  "peer-stall-threshold defaults to 900 (matches the former 15-min external watchdog debounce, at ~1 tick/sec worst case)" in {
    val config = ConfigFactory.parseString("""dcc.network {
                                              |  file = null
                                              |  known-peers = []
                                              |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
    val settings = ConfigSource.fromConfig(config).at("dcc.network").loadOrThrow[NetworkSettings]
    settings.peerStallThreshold shouldBe 900
  }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `sbt "node-tests/testOnly com.decentralchain.settings.NetworkSettingsSpecification"`
Expected: FAIL — `value peerStallThreshold is not a member of NetworkSettings`

- [ ] **Step 4: Add the field**

Edit `node/src/main/scala/com/decentralchain/settings/NetworkSettings.scala`, add to the case class (after `suspensionResidenceTime` at line 37):

```scala
    suspensionResidenceTime: FiniteDuration,
    // Consecutive scheduleConnectTask ticks with zero connections AND no available candidate
    // before the in-process stall detector self-heals by clearing suspension (never blacklist).
    // Default 900: this loop's tick interval is ~1-5s (AverageHandshakePeriod when disconnected,
    // see NetworkServer.scheduleConnectTask), so 900 ticks is roughly the same ~15-minute window
    // as the external peer-watchdog.yml's debounce it replaces -- see
    // docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md. Deliberately at least
    // as conservative as what it replaces, not more aggressive.
    peerStallThreshold: Int = 900,
    receivedTxsCacheTimeout: FiniteDuration,
```

- [ ] **Step 5: Add the config default**

Edit `node/src/main/resources/application.conf`, inside `dcc.network { ... }`:

```
    # In-process peer-reconnection stall detector. See NetworkSettings.peerStallThreshold.
    peer-stall-threshold = 900
```

- [ ] **Step 6: Run test to verify it passes**

Run: `sbt "node-tests/testOnly com.decentralchain.settings.NetworkSettingsSpecification"`
Expected: PASS

- [ ] **Step 7: Wire `PeerStallDetector` into `scheduleConnectTask`**

Edit `node/src/main/scala/com/decentralchain/network/NetworkServer.scala`. Add the import near the top:

```scala
// PeerStallDetector is in the same package (com.decentralchain.network) -- no import needed.
```

Inside `def apply(...)`, right before `def scheduleConnectTask(): Unit = ...` (before line 241), add:

```scala
    val stallDetector = new PeerStallDetector(networkSettings.peerStallThreshold)
```

Now edit `scheduleConnectTask` (lines 241-256) to feed the detector and self-heal on a threshold crossing:

```scala
    def scheduleConnectTask(): Unit = if (!shutdownInitiated) {
      val delay = (if (peerConnectionsMap.isEmpty || networkSettings.minConnections.exists(_ > peerConnectionsMap.size())) AverageHandshakePeriod
                   else 5.seconds) +
        (ThreadLocalRandom.current().nextInt(1000) - 500).millis // add some noise so that nodes don't attempt to connect to each other simultaneously

      workerGroup.schedule(delay) {
        val hasConnections = !peerConnectionsMap.isEmpty
        var candidateFound  = false
        if (outgoingChannels.size() < networkSettings.maxOutboundConnections) {
          val all = peerInfo.values().iterator().asScala.flatMap(_.remoteAddress.cast[InetSocketAddress])
          peerDatabase
            .nextCandidate(excluded = excludedAddresses ++ all)
            .foreach { candidate =>
              candidateFound = true
              doConnect(candidate)
            }
        }

        // In-process replacement for peer-watchdog.yml's external SSH+docker-restart remedy
        // (docs/superpowers/plans/2026-09-12-inprocess-peer-stall-detection.md). Fires only on
        // genuine stall (no connections AND no candidate at all, sustained) -- clears suspension
        // ONLY, never blacklist. A candidate existing but mid-handshake is not a stall and does
        // not reset/advance this differently than a normal tick already would via the branches
        // above (hasConnections=false, candidateFound=true both count as "not stuck" in the
        // detector itself).
        if (stallDetector.tick(hasConnections, candidateFound)) {
          log.warn(
            s"Peer reconnection stalled: 0 connections and no candidate for ${networkSettings.peerStallThreshold} consecutive attempts. " +
              "Clearing suspension cache (blacklist untouched) to allow retrying previously-suspended peers."
          )
          peerDatabase.clearSuspension()
        }

        scheduleConnectTask()
      }
    }
```

- [ ] **Step 8: Run the NetworkServer-related test suite**

Run: `sbt "node-tests/testOnly com.decentralchain.network.*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add node/src/main/scala/com/decentralchain/settings/NetworkSettings.scala node/src/main/scala/com/decentralchain/network/NetworkServer.scala node/src/main/resources/application.conf node/tests/src/test/scala/com/decentralchain/settings/NetworkSettingsSpecification.scala
git commit -m "feat(network): wire in-process peer-stall detection into scheduleConnectTask, self-heal via clearSuspension only"
```

---

### Task 4: Testnet soak — run in-process detector alongside the external watchdog

**Files:**
- No code changes — deploy + observe, same pattern as the self-commit plan's Task 4.

**Interfaces:**
- Consumes: the shipped `peer-stall-threshold` config from Task 3 (already defaults to `900`, no override needed unless testing a faster threshold for the soak window).
- Produces: a soak observation appended to this file.

- [ ] **Step 1: Optionally lower the threshold for a faster soak signal**

For observation purposes only (not for production), consider temporarily setting `peer-stall-threshold = 60` on gen-0 (the historically flakiest link per `INCIDENT-GEN0-PEERS.md`) so a real stall — if one occurs during the soak window — triggers and logs faster than the default 900-tick/~15-min window. Revert to the default before Task 5.

- [ ] **Step 2: Watch for a real or induced stall over one full day**

Run: `kubectl logs -n dcc dcc-gen-0-0 --since=24h | grep "Peer reconnection stalled"`

If nothing fires because gen-0 never actually stalls during the window, that's an acceptable soak outcome (absence of false positives) — but also deliberately verify the detector CAN fire: temporarily blacklist-exempt-bypass is not needed, simplest induced test is to suspend all of gen-0's known candidates by hand via `POST /debug/...` if such an endpoint exists (check `grep -rn "def route" node/src/main/scala/com/decentralchain/api/http/debug/DebugApiRoute.scala | grep -i peer`) or, simpler, just disconnect gen-0's network temporarily in a controlled test and confirm the log line appears once suspension count exhausts naturally.

- [ ] **Step 3: Confirm the external watchdog's debounce never actually engages during this soak**

Run: `gh run list --repo Decentral-America/infra --workflow=peer-watchdog.yml --limit 20 --json conclusion,createdAt`
Expected: no run during the soak window logs "Running wipe-peers-and-restart..." (check via `gh run view <id> --log | grep wipe-peers`) — i.e. the in-process detector should resolve any real stall before the external 5-min-poll/15-min-debounce watchdog ever needs to act.

- [ ] **Step 4: Document the soak result**

Append a dated `## Soak Log` entry to the bottom of this file.

---

### Task 5: Retire `peer-watchdog.yml`

**Files:**
- Modify (delete): `Ecosystem/infra/.github/workflows/peer-watchdog.yml`

**Interfaces:**
- Consumes: a passing Task 4 soak result, on ALL FOUR node identities (main, gen-0, gen-1, val-0), not just gen-0.

- [ ] **Step 1: Confirm the soak covered all 4 identities, not just gen-0**

If Task 4 only exercised gen-0 (the historically flakiest one), repeat the same one-day observation window for gen-1, val-0, and the main node before deleting the external safety net for all of them.

- [ ] **Step 2: Delete the workflow**

```bash
cd Ecosystem/infra
git rm .github/workflows/peer-watchdog.yml
git commit -m "chore: retire peer-watchdog.yml, superseded by in-process peer-stall detection (node-scala)"
```

- [ ] **Step 3: Final confirmation**

Run: `kubectl get pods -n dcc` and `curl -s https://testnet-node.decentralchain.io/node/status` — confirm chain height still advancing with zero external peer-health automation running.

---

## Self-Review Notes

- **Spec coverage:** new `clearSuspension()` primitive that never touches blacklist (Task 1, directly addresses the audit's core correction) → pure, testable stall counter (Task 2) → wired into the existing self-rescheduling loop with config-driven threshold matched to the external watchdog's own debounce window (Task 3) → soak with external watchdog as safety net (Task 4) → full retirement only after all 4 node identities are confirmed (Task 5).
- **Placeholder scan:** Task 4's induced-stall test step offers a conditional path ("if such an endpoint exists") because the debug API surface wasn't fully enumerated during planning — this is flagged explicitly with the exact grep to resolve it, not left as vague "test this somehow" language.
- **Type consistency:** `PeerStallDetector.tick(hasConnections: Boolean, hasCandidate: Boolean): Boolean` (Task 2) is called identically in Task 3's `scheduleConnectTask` wiring (`hasConnections`, `candidateFound`). `PeerDatabase.clearSuspension(): Unit` (Task 1) is the only method Task 3's self-heal path calls — `clearBlacklist()` is never referenced anywhere in this plan's production wiring, by design.
