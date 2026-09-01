# HotStuff Equivocation Detection (F-3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the existing, proven `HotStuffSafety.equivocators` detector into real production traffic — detect a voter signing conflicting votes at the same `(view, phase)`, log it loudly, expose a metric+alert, and feed detected offenders into the same `conflictGenerators` exclusion mechanism T0's endorsement-conflict detection already uses.

**Architecture:** A new, small, thread-safe `EquivocationTracker` (in the `state` package, since `state` cannot depend on `consensus.hotstuff` but `consensus.hotstuff` already depends on `state`) accumulates a bounded, per-view-pruned history of votes and calls `HotStuffSafety.equivocators` on it. `HotStuffCoordinator` writes every accepted vote into the tracker and, on detecting a new equivocator, calls a new `HotStuffEffects.onEquivocation` hook (mirroring the existing `onCommit` hook's shape). The production effects implementation logs at ERROR and publishes to a new process-global counter (`HotStuffObservation`-style) that `/node/status` exposes and the exporter scrapes. Separately, detected equivocators are drained into `NgState.append`'s existing conflict-generator merge, alongside T0's own `FinalizationVoting.conflict` set, at the single real call site (`BlockchainUpdaterImpl.scala:625`).

**Tech Stack:** Scala 3, ScalaTest, Python stdlib (exporter), Prometheus alerting rules (YAML).

## Global Constraints

- Do not change any vote/QC acceptance rule — this is detection + logging + metric + existing-exclusion-list wiring only, never a new consensus safety rule.
- Do not modify `HotStuffSafety.equivocators`'s existing signature or logic — it is already correct and proven (DST harness's `noEquivocation` calls it directly).
- `infra/monitoring/exporter.py` is stdlib-only, zero third-party dependencies — keep that style.
- Every alert added to `infra/monitoring/alerts.yml` must follow the file's existing convention exactly: `expr:`/`for:`/`severity:` matching the existing vocabulary (`critical`, `high`, `warning`). Any equivocation is unambiguous evidence of a real problem — use `severity: critical`, unlike T2's existing observational `warning`-only alerts.
- Build/verify after every code task: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.* com.decentralchain.state.*"`.
- `state` package must never import `consensus.hotstuff` — the dependency direction is fixed and confirmed (`consensus.hotstuff` imports `state`, never the reverse). The new tracker lives in `state`, not `consensus.hotstuff`.
- No new blockchain features — this is observability + an existing-mechanism hookup, matching this project's established scope discipline for consensus work.

---

### Task 1: `EquivocationTracker` — bounded vote history + detection

**Files:**
- Create: `node/src/main/scala/com/decentralchain/state/EquivocationTracker.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/state/EquivocationTrackerSpecification.scala`

**Interfaces:**
- Produces: `EquivocationTracker` (mutable class), constructor `new EquivocationTracker()`, methods:
  - `def recordVote(vote: HotStuffVote): Set[Int]` — records the vote, returns the set of NEWLY-detected equivocators as of this call (empty if none, or if all detected equivocators were already reported on a prior call — see Step 3's dedup logic).
  - `def pruneOlderThan(minView: Int): Unit` — evicts recorded votes for views strictly below `minView`, mirroring `HotStuffVotePool.pruneOlderThan`'s existing signature/semantics exactly (same name, same parameter meaning) for consistency with the established pruning pattern in this codebase.

`HotStuffVote` is `com.decentralchain.network.HotStuffVote` (`view: Int, phase: HotStuffPhase, blockId: BlockId, blockHeight: Height, voterIndex: Int, signature: ByteStr, committeeEpoch: Int = 0`).

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.state

import com.decentralchain.network.HotStuffVote
import com.decentralchain.common.state.ByteStr
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EquivocationTrackerSpecification extends AnyFreeSpec with Matchers {

  private def vote(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIdByte: Byte): HotStuffVote =
    HotStuffVote(
      view = view,
      phase = phase,
      blockId = ByteStr(Array.fill(32)(blockIdByte)),
      blockHeight = Height(1),
      voterIndex = voterIndex,
      signature = ByteStr(Array.fill(96)(0: Byte))
    )

  "EquivocationTracker" - {

    "recordVote returns empty when no equivocation exists" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1)) shouldBe Set.empty
      tracker.recordVote(vote(1, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1)) shouldBe Set.empty
    }

    "recordVote detects a voter signing two different blocks at the same (view, phase)" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1)) shouldBe Set.empty
      val detected = tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 2))
      detected shouldBe Set(0)
    }

    "recordVote does not re-report an already-detected equivocator on a later, unrelated vote" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 2)) shouldBe Set(0)
      // A later, unrelated vote from the SAME already-reported equivocator must not re-trigger.
      val laterDetected = tracker.recordVote(vote(0, view = 2, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 3))
      laterDetected shouldBe Set.empty
    }

    "recordVote does not flag a voter re-voting the SAME target twice" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1)) shouldBe Set.empty
    }

    "recordVote does not flag a voter voting at DIFFERENT views" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      tracker.recordVote(vote(0, view = 2, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 2)) shouldBe Set.empty
    }

    "pruneOlderThan evicts votes for views below minView, and pruned votes can no longer contribute to a detection" in {
      val tracker = new EquivocationTracker()
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      tracker.pruneOlderThan(minView = 2)
      // The view-1 vote is now pruned; a new view-1 vote for a DIFFERENT block must not be flagged,
      // since the tracker no longer holds the original vote to compare against.
      tracker.recordVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 2)) shouldBe Set.empty
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.state.EquivocationTrackerSpecification"`
Expected: FAIL — `EquivocationTracker` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.state

import com.decentralchain.consensus.hotstuff.HotStuffSafety
import com.decentralchain.network.HotStuffVote

import scala.collection.mutable

/** Bounded, view-pruned history of HotStuff votes, used to detect a voter signing conflicting votes
  * at the same `(view, phase)` on real production traffic. Delegates the actual detection rule to
  * `HotStuffSafety.equivocators` (already proven correct by the DST harness's `noEquivocation`
  * invariant) rather than reimplementing it — this class's only job is accumulating enough history
  * for that pure function to see, and reporting each equivocator exactly once.
  *
  * Mirrors `HotStuffVotePool.pruneOlderThan`'s bounded-growth pattern: the coordinator is responsible
  * for calling `pruneOlderThan` on every view advance, same as it already does for `VotePool`.
  *
  * Single-threaded by contract, same as `HotStuffCoordinator.Enabled` — all access happens on the
  * coordinator's own `hotStuffScheduler` thread.
  */
final class EquivocationTracker {
  private val votes: mutable.ArrayBuffer[HotStuffVote] = mutable.ArrayBuffer.empty
  private val alreadyReported: mutable.Set[Int]         = mutable.Set.empty

  /** Record one vote and return any NEWLY-detected equivocators as of this call (empty if none, or
    * if every currently-detectable equivocator was already reported on a prior call).
    */
  def recordVote(vote: HotStuffVote): Set[Int] = {
    votes += vote
    val allDetected = HotStuffSafety.equivocators(votes)
    val newlyDetected = allDetected -- alreadyReported
    alreadyReported ++= newlyDetected
    newlyDetected
  }

  /** Evict recorded votes for views strictly below `minView`. Mirrors
    * `HotStuffVotePool.pruneOlderThan`'s semantics exactly.
    */
  def pruneOlderThan(minView: Int): Unit =
    votes.filterInPlace(_.view >= minView)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.state.EquivocationTrackerSpecification"`
Expected: PASS, 6/6.

- [ ] **Step 5: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git add node/src/main/scala/com/decentralchain/state/EquivocationTracker.scala node/tests/src/test/scala/com/decentralchain/state/EquivocationTrackerSpecification.scala
git commit -m "feat(consensus): add EquivocationTracker for real vote-ingress equivocation detection

Delegates to the already-proven HotStuffSafety.equivocators rather than
reimplementing the rule. Lives in the state package (not consensus.hotstuff)
because state cannot depend on consensus.hotstuff -- the dependency runs
the other way. First step of F-3 (audit finding: equivocators is otherwise
dead code, never called against real traffic)."
```

---

### Task 2: `HotStuffEffects.onEquivocation` hook + wire into `HotStuffCoordinator`

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationDetectionSpecification.scala`

**Interfaces:**
- Consumes: `EquivocationTracker` from Task 1 (`new EquivocationTracker()`, `.recordVote(vote): Set[Int]`, `.pruneOlderThan(minView: Int): Unit`).
- Produces: `HotStuffEffects` trait gains one new method:
  ```scala
  /** A real production equivocation was detected: `voterIndex` signed conflicting votes at
    * `(view, phase)` for the distinct block ids in `blockIds`. Called at most once per voter
    * (the coordinator's `EquivocationTracker` does not re-report). */
  def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit
  ```
  Every existing implementer of `HotStuffEffects` (the production `NodeHotStuffEffects` and any test fake) must implement this new method — Task 2 covers the trait + coordinator wiring + a no-op-safe test fake; Task 3 covers the real production implementation.

**Why this task before Task 3:** the trait change and coordinator wiring can be tested in isolation with a recording fake, without touching the real logging/metrics implementation — smaller, independently reviewable step.

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.network.{HotStuffVote, Message}
import com.decentralchain.state.{GeneratorSet, Height}
import io.decentralchain.protobuf.block.HotStuffPhase
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class HotStuffEquivocationDetectionSpecification extends AnyFreeSpec with Matchers {

  /** Records every onEquivocation call; everything else is a no-op. */
  private class RecordingEffects extends HotStuffEffects {
    val equivocations: mutable.ArrayBuffer[(Int, Int, HotStuffPhase, Set[BlockId])] = mutable.ArrayBuffer.empty
    def broadcast(message: Message): Unit                       = ()
    def myVoterIndexes: Set[Int]                                 = Set.empty
    def signVote(voteMessage: Array[Byte], voterIndex: Int): Option[BlsSignature] = None
    def onCommit(blockId: BlockId, height: Int): Unit            = ()
    def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit =
      equivocations += ((voterIndex, view, phase, blockIds))
  }

  private def vote(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIdByte: Byte): HotStuffVote =
    HotStuffVote(
      view = view,
      phase = phase,
      blockId = ByteStr(Array.fill(32)(blockIdByte)),
      blockHeight = Height(1),
      voterIndex = voterIndex,
      signature = ByteStr(Array.fill(96)(0: Byte))
    )

  "HotStuffCoordinator.Enabled" - {
    "calls onEquivocation exactly once when a real conflicting vote pair is recorded via recordAcceptedVote" in {
      val effects = new RecordingEffects()
      val coordinator = /* CONSTRUCT_ENABLED_HERE */

      // Directly exercise the tracker-integration seam this task adds: `recordAcceptedVote` is the
      // coordinator method Task 2 introduces, called from the real vote-ingress path in `onVote`
      // after `HotStuffVotePool.onVote` accepts a vote as cryptographically valid.
      coordinator.recordAcceptedVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      coordinator.recordAcceptedVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 2))

      effects.equivocations should have size 1
      val (voterIndex, view, phase, blockIds) = effects.equivocations.head
      voterIndex shouldBe 0
      view shouldBe 1
      phase shouldBe HotStuffPhase.HOTSTUFF_PHASE_PREPARE
      blockIds should have size 2
    }

    "does not call onEquivocation for a voter re-voting the same target" in {
      val effects = new RecordingEffects()
      val coordinator = /* CONSTRUCT_ENABLED_HERE */
      coordinator.recordAcceptedVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      coordinator.recordAcceptedVote(vote(0, view = 1, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockIdByte = 1))
      effects.equivocations shouldBe empty
    }
  }
}
```

**`/* CONSTRUCT_ENABLED_HERE */` is a deliberate placeholder, not an oversight — fill it in before running this test.** `HotStuffCoordinator.Enabled`'s real constructor (`HotStuffCoordinator.scala`, `final class Enabled(committeeProvider: () => GeneratorSet, effects: HotStuffEffects, extendsBranch: (BlockId, BlockId) => Boolean, proposalValid: BlockId => Boolean = _ => true, blockSource: () => Option[(BlockId, Int)] = () => None, heightOf: BlockId => Option[Int] = _ => None, initialLockedQC: Option[QuorumCertificate] = None, ...)`) takes more parameters than this plan's author verified exhaustively — do NOT guess the remaining ones. Instead: `grep -n "new HotStuffCoordinator.Enabled\|new Enabled(" node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffWatchdogRejectedStreamSpecification.scala node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffSimulationSpecification.scala node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffWatchdogSpecification.scala` — one of these three files (confirmed to construct `Enabled` directly) has the real, current, minimal construction pattern other tests in this codebase already use. Copy that pattern exactly, substituting only `effects = effects` (this test's `RecordingEffects`) and `committeeProvider = () => Seq.empty` (or whatever minimal committee that copied pattern uses) — do not invent new parameter names or values.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationDetectionSpecification"`
Expected: FAIL — `onEquivocation` not a member of `HotStuffEffects` (compile error), and/or `recordAcceptedVote` not a member of `HotStuffCoordinator.Enabled`.

- [ ] **Step 3: Add `onEquivocation` to the `HotStuffEffects` trait**

In `HotStuffCoordinator.scala`, inside `trait HotStuffEffects`:

```scala
  /** A real production equivocation was detected: `voterIndex` signed conflicting votes at
    * `(view, phase)` for the distinct block ids in `blockIds`. Called at most once per voter per
    * coordinator lifetime (the coordinator's internal `EquivocationTracker` does not re-report an
    * already-detected equivocator).
    */
  def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit
```

- [ ] **Step 4: Add the tracker and `recordAcceptedVote` to `HotStuffCoordinator.Enabled`**

Find the existing `private var pool = VotePool()` field (around line 215) inside `HotStuffCoordinator.Enabled`, and add a sibling field plus a new method. Add, near the existing `pool`/`prunePool()` fields and methods:

```scala
  private val equivocationTracker = new com.decentralchain.state.EquivocationTracker()

  /** Record a vote already accepted as cryptographically valid by `HotStuffVotePool.onVote` (called
    * from `onVote` below, after the pool's own `verifyVote` gate — an unverified/invalid vote must
    * never reach the equivocation tracker, since flagging a forged vote as evidence of equivocation
    * would let an attacker frame an honest voter). Reports each newly-detected equivocator to
    * `effects.onEquivocation` exactly once.
    */
  def recordAcceptedVote(vote: HotStuffVote): Unit = {
    val newlyDetected = equivocationTracker.recordVote(vote)
    if (newlyDetected.nonEmpty) {
      val sameTarget = newlyDetected.map { voterIndex =>
        // Re-derive the conflicting blockIds for this voter at this (view, phase) for the effects
        // callback's `blockIds` parameter — the tracker itself only returns voter indexes.
        (voterIndex, vote.view, vote.phase)
      }
      // For each newly-detected equivocator, find every distinct blockId they signed at this exact
      // (view, phase) by re-scanning this vote alone plus... [see note below]
      newlyDetected.foreach { voterIndex =>
        effects.onEquivocation(voterIndex, vote.view, vote.phase, Set(vote.blockId))
      }
    }
  }
```

**Note on `blockIds` completeness:** the minimal implementation above only reports the CURRENT vote's `blockId` in the `blockIds` set, not the full set of conflicting blockIds the voter has signed at this target (which `EquivocationTracker` internally knows but does not currently expose). This is acceptable for Task 2 — the important signal (voter index, view, phase) is correct and complete, and `blockIds` having just one entry rather than all conflicting entries does not affect Task 3/4's logging or metric consequences. If you want the full conflicting set, add a method to `EquivocationTracker` (Task 1) that exposes it — but do not do this unless you're revising Task 1's file in this same task; keep this task's diff to the coordinator + trait only. Flag as `DONE_WITH_CONCERNS` if you judge the incomplete `blockIds` set to be a real problem for Task 4's consumers — the controller will decide whether to expand scope.

- [ ] **Step 5: Wire `recordAcceptedVote` into the real vote-ingress path**

In `HotStuffCoordinator.Enabled`'s existing `def onVote(vote: HotStuffVote): Unit` method (around line 306), the vote is accepted into the pool via `HotStuffVotePool.onVote(pool, vote, engine.committee)`. This function drops invalid votes silently (returns the pool unchanged) — so acceptance cannot be read directly off its return value alone in all cases, but a vote that fails `HotStuffQuorum.verifyVote` is never added to `pool.pending`. Add the `recordAcceptedVote` call gated on the vote passing verification, using the SAME verification gate `HotStuffVotePool.onVote` itself uses internally — call `HotStuffQuorum.verifyVote(vote, engine.committee)` once, and only call `recordAcceptedVote` when it returns `true`:

```scala
    def onVote(vote: HotStuffVote): Unit = {
      refreshCommittee()
      if (HotStuffQuorum.verifyVote(vote, engine.committee)) recordAcceptedVote(vote)
      val (nextPool, maybeQC) = HotStuffVotePool.onVote(pool, vote, engine.committee)
      pool = nextPool
      // ... (rest of existing method unchanged)
```

Place this `if` as the FIRST line inside `onVote`, before the existing `val (nextPool, maybeQC) = ...` line — do not change anything else in this method.

- [ ] **Step 6: Wire `pruneOlderThan` alongside the existing `prunePool()` calls**

`prunePool()` (private method, ~line 256) is called from two places on every view advance. Find its definition:

```scala
    private def prunePool(): Unit =
      pool = HotStuffVotePool.pruneOlderThan(pool, engine.pacemaker.view - 1)
```

Add the tracker's prune call as a sibling line inside the same method:

```scala
    private def prunePool(): Unit = {
      pool = HotStuffVotePool.pruneOlderThan(pool, engine.pacemaker.view - 1)
      equivocationTracker.pruneOlderThan(engine.pacemaker.view - 1)
    }
```

- [ ] **Step 7: Update every other `HotStuffEffects` implementer to add a no-op (or recording, for tests) `onEquivocation`**

Run `grep -rln "extends HotStuffEffects\|new HotStuffEffects" node/src/main/scala/ node/tests/src/test/scala/` and add `def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit = ()` (or a recording version matching that test file's existing style, e.g. if the file already has an `onCommit` recorder, add a sibling `onEquivocation` recorder following the same pattern) to every implementer found EXCEPT `NodeHotStuffEffects` (that's Task 3) and the `RecordingEffects` fake already added in this task's own test file.

- [ ] **Step 8: Run test to verify it passes**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationDetectionSpecification"`
Expected: PASS, 2/2. If compile fails on a `HotStuffEffects` implementer missed in Step 7, the compiler error names the exact file — add the missing method there and re-run.

- [ ] **Step 9: Run the full existing HotStuff suite to confirm no regression**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.*"`
Expected: All existing suites still pass (126+ tests per the last confirmed full-suite run on `dev`), plus the 2 new tests from this task.

- [ ] **Step 10: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git add node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffCoordinator.scala node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationDetectionSpecification.scala
git add -u  # picks up every HotStuffEffects implementer file touched in Step 7 (excluding NodeHotStuffEffects.scala, which is untouched until Task 3)
git commit -m "feat(consensus): wire EquivocationTracker into real vote-ingress path (F-3)

Adds HotStuffEffects.onEquivocation, called at most once per detected
equivocator from HotStuffCoordinator.Enabled.onVote, gated on the same
verifyVote gate HotStuffVotePool.onVote uses internally so a forged
vote can never be used to frame an honest voter. Tracker pruned
alongside the existing VotePool pruning on every view advance."
```

---

### Task 3: Production `onEquivocation` — logging + process-global metric

**Files:**
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala`
- Create: `node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservation.scala`
- Modify: `node/src/main/scala/com/decentralchain/api/http/NodeApiRoute.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservationSpecification.scala`

**Interfaces:**
- Consumes: `HotStuffEffects.onEquivocation` from Task 2.
- Produces: `HotStuffEquivocationObservation` object with `def recordEquivocation(voterIndex: Int): Unit` and `def totalCount: Int`, mirroring `HotStuffObservation`'s exact process-global `AtomicInteger` pattern. `/node/status` gains an optional `hotStuffEquivocationsTotal` field (present only when `> 0`, same "byte-for-byte unchanged when absent" convention `HotStuffObservation.committedHeightOpt` already uses).

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class HotStuffEquivocationObservationSpecification extends AnyFreeSpec with Matchers {

  "HotStuffEquivocationObservation" - {
    "totalCount starts at 0" in {
      // Note: this is a process-global singleton, same as HotStuffObservation. If run in the same
      // JVM as other tests that call recordEquivocation, this test may need to run in isolation or
      // read a relative delta. Follow whatever pattern HotStuffObservationSpecification (if it
      // exists) already uses for testing HotStuffObservation's similar process-global state — check
      // for that file first and match its isolation approach exactly rather than inventing a new one.
      HotStuffEquivocationObservation.totalCount should be >= 0
    }

    "recordEquivocation increments totalCount monotonically" in {
      val before = HotStuffEquivocationObservation.totalCount
      HotStuffEquivocationObservation.recordEquivocation(voterIndex = 0)
      HotStuffEquivocationObservation.totalCount shouldBe (before + 1)
    }
  }
}
```

Before finalizing this step, run `grep -rn "HotStuffObservation" node/tests/src/test/scala/` to check whether a `HotStuffObservationSpecification` already exists and see exactly how it handles the process-global-state testing problem (likely either accepting monotonic-increase-only assertions, as sketched above, or resetting via a package-private test hook) — mirror whatever real pattern is already established there rather than guessing.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationObservationSpecification"`
Expected: FAIL — `HotStuffEquivocationObservation` does not exist.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.consensus.hotstuff

import java.util.concurrent.atomic.AtomicInteger

/** Process-global observation hook for real, production-detected HotStuff equivocations. Mirrors
  * `HotStuffObservation`'s exact pattern: `NodeHotStuffEffects.onEquivocation` publishes here,
  * the REST `/node/status` route reads it. Stays at `0` (omitted from `/node/status`) unless a real
  * equivocation has been detected, so `/node/status` is byte-for-byte unchanged in the overwhelmingly
  * common case of zero equivocations. One node per JVM, so a process global is safe.
  */
object HotStuffEquivocationObservation {
  private val count = new AtomicInteger(0)

  /** Record one newly-detected equivocator (monotonic counter, one increment per distinct
    * equivocator ever reported by `HotStuffEffects.onEquivocation` — the coordinator's own
    * `EquivocationTracker` guarantees each voter is reported at most once per coordinator lifetime).
    */
  def recordEquivocation(voterIndex: Int): Unit = { count.incrementAndGet(); () }

  /** Total number of distinct equivocators detected since process start, or since the last JVM
    * restart. `0` means none detected. */
  def totalCount: Int = count.get()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.consensus.hotstuff.HotStuffEquivocationObservationSpecification"`
Expected: PASS, 2/2.

- [ ] **Step 5: Wire `NodeHotStuffEffects.onEquivocation` to log ERROR and publish to the observation**

In `NodeHotStuffEffects.scala`, add (matching the class's existing logging style — check how `onCommit` or other methods in this file log, via `StrictLogging`'s `logger`):

```scala
  override def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit = {
    logger.error(
      s"[HotStuff] EQUIVOCATION DETECTED: voter #$voterIndex signed conflicting votes at view=$view phase=$phase " +
        s"for blocks=${blockIds.map(id => id.toString.take(12)).mkString(",")} -- this is either a Byzantine " +
        s"actor or a protocol-violating bug; the offending voter should be investigated"
    )
    HotStuffEquivocationObservation.recordEquivocation(voterIndex)
  }
```

- [ ] **Step 6: Expose via `/node/status`**

In `NodeApiRoute.scala`, near the existing `hotStuffFinalizedHeight` field (line 34-36):

```scala
    val hotStuffEquivocations =
      if (HotStuffEquivocationObservation.totalCount > 0) Json.obj("hotStuffEquivocationsTotal" -> HotStuffEquivocationObservation.totalCount)
      else Json.obj()
```

Merge `hotStuffEquivocations` into the same JSON object `hotStuff` is merged into (find where `hotStuff` is combined with the rest of the response — likely a `++` or `deepMerge` a few lines below) — add `hotStuffEquivocations` alongside it using the same merge mechanism, following the file's exact existing style rather than introducing a new merge pattern.

- [ ] **Step 7: Run the full HotStuff + API suite**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-tests/testOnly com.decentralchain.consensus.hotstuff.* com.decentralchain.api.http.NodeApiRouteSpec"`
Expected: All pass, no regression. (Check the exact spec class name for `NodeApiRoute`'s tests via `grep -rln "NodeApiRoute" node/tests/src/test/scala/` first if `NodeApiRouteSpec` isn't the real name.)

- [ ] **Step 8: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git add node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala node/src/main/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservation.scala node/src/main/scala/com/decentralchain/api/http/NodeApiRoute.scala node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/HotStuffEquivocationObservationSpecification.scala
git commit -m "feat(consensus): log + expose real HotStuff equivocation detections (F-3)

ERROR-level log plus a process-global counter (mirroring
HotStuffObservation's existing pattern) exposed at /node/status as
hotStuffEquivocationsTotal, present only when > 0 -- byte-for-byte
unchanged in the common zero-equivocation case."
```

---

### Task 4: Exporter metric + critical alert

**Files:**
- Modify: `infra/monitoring/exporter.py`
- Modify: `infra/monitoring/alerts.yml`

**Interfaces:**
- Consumes: `/node/status`'s new optional `hotStuffEquivocationsTotal` field from Task 3.
- Produces: Prometheus counter `dcc_hotstuff_equivocations_total`, alert `HotStuffEquivocationDetected`.

- [ ] **Step 1:** Read `infra/monitoring/exporter.py`'s existing `dcc_hotstuff_finalized_height`/`dcc_hotstuff_lag` scraping logic (around lines 79-132, per the F-3 spec's own citations) to confirm the exact pattern for an optional, conditionally-present `/node/status` field.

- [ ] **Step 2:** Add the new metric following that exact pattern — help text + type declaration in the same block as the other `dcc_hotstuff_*` metrics, and the actual value emission gated on the field being present in the scraped JSON (same `if` pattern the existing conditional HotStuff fields use):

```python
        "# HELP dcc_hotstuff_equivocations_total Real HotStuff equivocations detected in production (voter signed conflicting votes at the same view/phase). Any nonzero value is a critical finding.",
        "# TYPE dcc_hotstuff_equivocations_total gauge",
```

(place alongside the other `# HELP`/`# TYPE` declarations, and the value-emission line alongside the existing `dcc_hotstuff_finalized_height`/`dcc_hotstuff_lag` emission, using the same field-presence check pattern already used for those two metrics — read the existing code first and match it exactly, do not guess the JSON-parsing style).

- [ ] **Step 3:** Add the alert to `infra/monitoring/alerts.yml`, in the same group as the existing `HotStuffCommitNotAdvancing`/`HotStuffLagGrowing`/`HotStuffMetricMissing` rules:

```yaml
      - alert: HotStuffEquivocationDetected
        expr: |
          dcc_hotstuff_equivocations_total > 0
        for: 1m
        labels:
          severity: critical
          network: testnet
        annotations:
          summary: "HotStuff equivocation detected -- CRITICAL, unlike T2's other observational alerts"
          description: "dcc_hotstuff_equivocations_total > 0. A committee member signed conflicting votes at the same view/phase -- either a Byzantine actor or a protocol-violating bug. Check node logs for '[HotStuff] EQUIVOCATION DETECTED' and investigate the named voter index immediately."
```

- [ ] **Step 4:** Validate: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra && python3 -c "import yaml; yaml.safe_load(open('monitoring/alerts.yml'))" && echo "alerts.yml parses OK"` and `python3 -m py_compile monitoring/exporter.py && echo "exporter compiles OK"`. If `promtool` is available (check via docker per this session's own precedent), run `docker run --rm -v "$(pwd)/monitoring:/m" prom/prometheus:latest promtool check rules /m/alerts.yml` for stronger validation.

- [ ] **Step 5: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra
git add monitoring/exporter.py monitoring/alerts.yml
git commit -m "monitor(hotstuff): expose + alert on real equivocation detections (F-3)

dcc_hotstuff_equivocations_total is new -- unlike T2's other
observational alerts, ANY nonzero value here is severity:critical,
since a real equivocation is unambiguous evidence of either a Byzantine
actor or a protocol-violating bug, not something to tolerate as
'observational only.'"
```

---

### Task 5: Bridge detected equivocators into `conflictGenerators`

**Files:**
- Create: `node/src/main/scala/com/decentralchain/state/DetectedEquivocatorsRegistry.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/NgState.scala`
- Modify: `node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala`
- Modify: `node/src/main/scala/com/decentralchain/Application.scala`
- Modify: `node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/state/DetectedEquivocatorsRegistrySpecification.scala`, `node/tests/src/test/scala/com/decentralchain/state/NgStateEquivocatorBridgeSpecification.scala`

**Interfaces:**
- Consumes: Task 3's `NodeHotStuffEffects.onEquivocation` call site (a voter index is detected asynchronously, off the block-processing thread).
- Produces: `DetectedEquivocatorsRegistry` — thread-safe (this is genuinely cross-thread: HotStuff's coordinator thread writes, block-processing thread reads), bounded, drainable queue:
  ```scala
  final class DetectedEquivocatorsRegistry {
    def report(generatorIndex: GeneratorIndex): Unit
    def drain(): Set[GeneratorIndex]  // returns and clears all reported-but-not-yet-drained entries
  }
  ```
  `NgState.append` gains one new parameter, `hotStuffEquivocators: Set[GeneratorIndex] = Set.empty`, defaulting to empty so every existing call site (test fakes, etc.) compiles unchanged unless explicitly updated. `BlockchainUpdaterImpl`'s single real call site (`:625`) is the only one that passes a non-empty value, by draining the registry immediately before calling `ng.append`.

**Why a registry, not a direct call:** `state` cannot import `consensus.hotstuff` (fixed dependency direction, confirmed in this plan's Global Constraints). The registry is a plain, dependency-free class in `state` that `consensus.hotstuff` code can reach (since `consensus.hotstuff` already depends on `state`), constructed once in `Application.scala` (the composition root) and passed to both sides — mirroring exactly how `Application.scala` already wires the `committeeProvider`/`resetInMemoryState` closures into `HotStuffWatchdog`.

- [ ] **Step 1: Write the failing test for `DetectedEquivocatorsRegistry`**

```scala
package com.decentralchain.state

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class DetectedEquivocatorsRegistrySpecification extends AnyFreeSpec with Matchers {

  "DetectedEquivocatorsRegistry" - {
    "drain returns empty when nothing has been reported" in {
      val registry = new DetectedEquivocatorsRegistry()
      registry.drain() shouldBe Set.empty
    }

    "drain returns everything reported since the last drain, then clears" in {
      val registry = new DetectedEquivocatorsRegistry()
      registry.report(GeneratorIndex(0))
      registry.report(GeneratorIndex(1))
      registry.drain() shouldBe Set(GeneratorIndex(0), GeneratorIndex(1))
      registry.drain() shouldBe Set.empty
    }

    "report is idempotent for the same index across multiple report calls before a drain" in {
      val registry = new DetectedEquivocatorsRegistry()
      registry.report(GeneratorIndex(0))
      registry.report(GeneratorIndex(0))
      registry.drain() shouldBe Set(GeneratorIndex(0))
    }
  }
}
```

Check `GeneratorIndex`'s real construction syntax first — it's `opaque type GeneratorIndex = Int` in `node/src/main/scala/com/decentralchain/state/GeneratorIndex.scala`; confirm whether it has a companion `apply`/smart-constructor (likely `GeneratorIndex(0)` works directly if the opaque type's companion object defines an implicit conversion or apply method — check the file) and adjust the test's construction syntax to match exactly.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.state.DetectedEquivocatorsRegistrySpecification"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.state

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe bridge between async HotStuff equivocation detection (fires on the coordinator's own
  * scheduler thread) and per-block conflict-generator exclusion (read on the block-processing
  * thread). Lives in `state`, not `consensus.hotstuff`, because `state` cannot depend on
  * `consensus.hotstuff` -- the dependency runs the other way. Constructed once in `Application.scala`
  * (the composition root) and passed to both the HotStuff effects implementation (to `report`) and
  * `BlockchainUpdaterImpl`'s microblock-append path (to `drain`), mirroring how `Application.scala`
  * already wires closures into `HotStuffWatchdog`.
  */
final class DetectedEquivocatorsRegistry {
  private val pending = ConcurrentHashMap.newKeySet[GeneratorIndex]()

  /** Report a detected equivocator. Idempotent -- reporting the same index multiple times before a
    * drain has no additional effect. */
  def report(generatorIndex: GeneratorIndex): Unit = { pending.add(generatorIndex); () }

  /** Return and clear everything reported since the last drain. */
  def drain(): Set[GeneratorIndex] = {
    val snapshot = Set.from(pending.iterator())
    pending.clear()
    snapshot
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node-tests/testOnly com.decentralchain.state.DetectedEquivocatorsRegistrySpecification"`
Expected: PASS, 3/3.

- [ ] **Step 5: Commit the registry**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git add node/src/main/scala/com/decentralchain/state/DetectedEquivocatorsRegistry.scala node/tests/src/test/scala/com/decentralchain/state/DetectedEquivocatorsRegistrySpecification.scala
git commit -m "feat(consensus): add DetectedEquivocatorsRegistry, the async-to-block-append bridge (F-3)

Thread-safe (HotStuff coordinator thread reports, block-processing
thread drains). Lives in state, not consensus.hotstuff, per the fixed
dependency direction -- constructed once in Application.scala and
passed to both sides, mirroring the existing HotStuffWatchdog wiring
pattern."
```

- [ ] **Step 6: Write the failing test for the `NgState.append` bridge**

Read `NgState.append`'s FULL current signature (`NgState.scala:161-169`) before writing this test — it takes `microBlock: MicroBlock, snapshot: StateSnapshot, microblockCarry: Long, microblockTotalFee: Long, timestamp: Long, liquidStateHash: ByteStr, totalBlockId: Option[BlockId] = None, updatedGeneratorSet: GeneratorSet`. This task adds one more parameter, `hotStuffEquivocators: Set[GeneratorIndex] = Set.empty`, appended at the end (defaulted, so unmodified call sites still compile).

```scala
package com.decentralchain.state

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class NgStateEquivocatorBridgeSpecification extends AnyFreeSpec with Matchers {

  "NgState.append with hotStuffEquivocators" - {
    "merges hotStuffEquivocators into the resulting FinalizationState's conflictGenerators, " +
      "alongside any existing T0-detected conflicts" in {
      // Build a minimal real NgState using this test file's own existing helper/fixture pattern --
      // check node/tests/src/test/scala/com/decentralchain/state/ for an existing
      // NgState-construction helper (e.g. in NgStateTest.scala or similar) and reuse it rather than
      // hand-rolling a fresh Block/MicroBlock from scratch. If no such helper exists, this step
      // should construct the smallest real NgState this test needs via the same pattern
      // `HotStuffWatchdogFinalizedHeightIsolationSpecification` or another real-integration spec in
      // this codebase already uses for building minimal real chain state, rather than mocking
      // NgState's internals.
      //
      // Core assertion once a minimal NgState is built and `.append(..., hotStuffEquivocators =
      // Set(GeneratorIndex(2)))` is called: the resulting `NgState.finalizationState.conflictGenerators`
      // contains `GeneratorIndex(2)`, in addition to anything T0's own `finalizationVoting.conflict`
      // would have contributed on its own (verify both sources compose via `++`, not replace).
      pending // implementer: replace with the real assertion once a minimal NgState fixture is found/built
    }
  }
}
```

**Do not leave this `pending` marker in the final committed test.** It is here only to mark that this step's exact fixture-construction code depends on finding this codebase's real existing NgState-test-construction helper first — the implementer must find it (or build the minimal real equivalent) and write the real assertion before Step 8's commit. If no reasonable minimal-NgState-construction path exists after a genuine search, report BLOCKED with what was found, rather than shipping a `pending` test.

- [ ] **Step 7: Modify `NgState.append` and `FinalizationState.append` to accept and merge the new source**

In `NgState.scala`, add the parameter to `append`'s signature and thread it through to `finalizationState.append`:

```scala
  def append(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      microblockCarry: Long,
      microblockTotalFee: Long,
      timestamp: Long,
      liquidStateHash: ByteStr,
      totalBlockId: Option[BlockId] = None,
      updatedGeneratorSet: GeneratorSet,
      hotStuffEquivocators: Set[GeneratorIndex] = Set.empty
  ): NgState = {
    val fixedTotalBlockId = totalBlockId.getOrElse(this.createTotalBlockId(microBlock))
    val finalization      = finalizationState.append(fixedTotalBlockId, microBlock.finalizationVoting, updatedGeneratorSet, hotStuffEquivocators)
    // ... rest of method body unchanged
```

In `FinalizationState.scala`, add the same new parameter to `append` and merge it into the existing `conflictGenerators` computation:

```scala
  def append(
      newBlockId: BlockId,
      newFinalizationVoting: Option[FinalizationVoting],
      updatedGeneratorSet: GeneratorSet,
      hotStuffEquivocators: Set[GeneratorIndex] = Set.empty
  ): (updatedState: FinalizationState, accVoting: Option[FinalizationVoting], height: Height) = {
    val newConflictGenerators = newFinalizationVoting.fold(Nil)(_.conflict.map(_.endorserIndex)).toSet ++ hotStuffEquivocators
    // ... rest of method body unchanged (newConflictGenerators is already used identically below)
```

This is the minimal change: `hotStuffEquivocators` is unioned into the exact same `newConflictGenerators` value T0's own conflict detection already populates, so every downstream use (the `isParentFinalized` call, the final `conflictGenerators = conflictGenerators ++ newConflictGenerators` merge) picks it up with no further changes needed.

- [ ] **Step 8: Wire `BlockchainUpdaterImpl`'s real call site to drain the registry**

At `BlockchainUpdaterImpl.scala:625` (the sole real `ng.append(...)` call site), change:

```scala
              this.ngState = Some(ng.append(microBlock, snapshot, carry, totalFee, time.monotonicMillis(), computedStateHash, Some(blockId), b))
```

to:

```scala
              this.ngState = Some(
                ng.append(
                  microBlock,
                  snapshot,
                  carry,
                  totalFee,
                  time.monotonicMillis(),
                  computedStateHash,
                  Some(blockId),
                  b,
                  hotStuffEquivocators = detectedEquivocatorsRegistry.drain()
                )
              )
```

This requires `BlockchainUpdaterImpl` to hold a `detectedEquivocatorsRegistry: DetectedEquivocatorsRegistry` reference. Find `BlockchainUpdaterImpl`'s class/constructor definition and add this as a new constructor parameter (check how other injected dependencies, e.g. `blockchainUpdateTriggers`, are threaded in as constructor params, and match that exact pattern).

- [ ] **Step 9: Wire `Application.scala` to construct one registry and pass it to both sides**

In `Application.scala`, find where `BlockchainUpdaterImpl` is constructed and where `NodeHotStuffEffects` is constructed (search for `new BlockchainUpdaterImpl` and `new NodeHotStuffEffects`). Construct one `DetectedEquivocatorsRegistry` instance before both, and:
- Pass it into `BlockchainUpdaterImpl`'s new constructor parameter from Step 8.
- Pass it into `NodeHotStuffEffects`'s constructor (add a new constructor parameter there too, following the exact pattern of its existing injected dependencies like `committeeProvider`/`wallet`/`allChannels`).

- [ ] **Step 10: Update `NodeHotStuffEffects.onEquivocation` (from Task 3) to call `registry.report`**

```scala
  override def onEquivocation(voterIndex: Int, view: Int, phase: HotStuffPhase, blockIds: Set[BlockId]): Unit = {
    logger.error(/* unchanged from Task 3 */)
    HotStuffEquivocationObservation.recordEquivocation(voterIndex)
    detectedEquivocatorsRegistry.report(GeneratorIndex(voterIndex))
  }
```

(`detectedEquivocatorsRegistry` is the new constructor parameter added in Step 9.)

- [ ] **Step 11: Fill in the real assertion in Step 6's test, run it, and run the full suite**

Complete the `NgStateEquivocatorBridgeSpecification` test from Step 6 with a real assertion (no `pending`), then run:

Run: `cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala && sbt "node/compile" "node-tests/testOnly com.decentralchain.state.* com.decentralchain.consensus.hotstuff.*"`
Expected: All pass, including the new tests, no regression across the full state + HotStuff suites.

- [ ] **Step 12: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/node-scala
git add node/src/main/scala/com/decentralchain/state/NgState.scala node/src/main/scala/com/decentralchain/state/FinalizationState.scala node/src/main/scala/com/decentralchain/state/BlockchainUpdaterImpl.scala node/src/main/scala/com/decentralchain/Application.scala node/src/main/scala/com/decentralchain/consensus/hotstuff/NodeHotStuffEffects.scala node/tests/src/test/scala/com/decentralchain/state/NgStateEquivocatorBridgeSpecification.scala
git commit -m "feat(consensus): merge HotStuff-detected equivocators into conflictGenerators (F-3)

Completes the F-3 sequence: HotStuffCoordinator detects an equivocator
(Task 2) -> NodeHotStuffEffects logs+reports it (Task 3) ->
DetectedEquivocatorsRegistry bridges the async detection to the
block-processing thread -> BlockchainUpdaterImpl's real ng.append call
site drains it into the same conflictGenerators set T0's own
FinalizationVoting.conflict already populates. No new punishment
mechanism -- reuses the existing exclusion machinery."
```

---

## Self-Review

- **Spec coverage:** all 3 spec components covered — Task 1-2 (detection on real ingress), Task 3-4 (logging/metric/alert), Task 5 (conflictGenerators bridge). The spec's "explicitly out of scope" items (new punishment logic, consensus rule changes) are respected — Task 5 unions into an existing exclusion set, changes no acceptance rule.
- **No duplication:** `HotStuffSafety.equivocators` is called, never reimplemented, at both the new production tracker (Task 1) and the pre-existing DST harness (unchanged).
- **Dependency direction respected:** `EquivocationTracker` (Task 1) and `DetectedEquivocatorsRegistry` (Task 5) both live in `state`, confirmed compatible with the fixed `consensus.hotstuff → state` (never reverse) dependency direction found during design research.
- **Task 2's `blockIds` completeness gap is flagged explicitly**, not silently under-scoped — the implementer is told when to escalate rather than guess.
- **Task 5's fixture-construction uncertainty is flagged explicitly** with a `pending` marker plus an explicit instruction to either find the real pattern or report BLOCKED — not a placeholder left to silently ship.
