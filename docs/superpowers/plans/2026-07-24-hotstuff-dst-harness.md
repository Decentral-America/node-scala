# HotStuff Deterministic Simulation Testing (DST) Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an in-process, seeded, fully deterministic simulation harness for node-scala's HotStuff finality/consensus logic (Tier 1 of the [E2E testing strategy](../../../../infra/docs/superpowers/specs/2026-07-24-e2e-testing-strategy-design.md)), extending the existing `HotStuffSimulationSpecification` seam rather than building parallel infrastructure, so that ordering/timing consensus bugs become reproducible from a single seed and run on every push in milliseconds instead of requiring Docker-based `node-it` runs.

**Architecture:** A virtual-time event scheduler (`SimClock`) drives a fault-injecting in-memory network (`SimNetwork`) that delivers `HotStuffProposal`/`HotStuffVote`/`QuorumCertificate` messages between real `HotStuffCoordinator.Enabled` instances (the production consensus state machine, unmodified) wired through hand-written `HotStuffEffects` fakes — exactly the seam `HotStuffSimulationSpecification` already uses, extended with delay/drop/duplicate/partition injection, a virtual clock driving `onTimeout()` (never exercised today), and a mutable committee that scenarios can change mid-round. A `SafetyInvariants` checker asserts no-fork and no-regression over every recorded commit; scenarios run across a seed sweep so a failure is reported with its exact reproducing seed.

**Tech Stack:** Scala 3, ScalaTest (`AnyFlatSpec` via `com.decentralchain.test.FlatSpec`, already used by all HotStuff unit specs), sbt module `node-tests` (no new dependencies — everything here is plain stdlib + existing test deps).

## Global Constraints

- All new code is test-only, inside `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/` — do not modify `node/src/main/scala/com/decentralchain/consensus/hotstuff/*` (production HotStuff code) or `HotStuffEffects`'s existing contract.
- Scala 3 syntax throughout (this is a Scala 3 codebase — `given`/`*` varargs conventions where idiomatic, but prefer the safer `mutable.Set.from(...)` / explicit `Ordering` forms shown in this plan over spread-operator varargs to avoid Scala 2/3 ambiguity).
- No new sbt dependencies. No new sbt module — everything lands in the existing `node-tests` module (confirmed already run via `"node-tests/test"` in `.github/workflows/ci.yml`, in the `test` job that runs on every push and PR).
- Every new spec must extend `com.decentralchain.test.FlatSpec` (the existing base class — brings in `should`-matchers, `ScalaCheckPropertyChecks`, etc.) to match the file style already used by `HotStuffEngineSpecification`, `HotStuffSimulationSpecification`, and siblings in the same package.
- Runtime budget: the `test` job is already at ~45 of its 60-minute timeout. Default seed-sweep counts in every new scenario spec must keep total added runtime well under a minute combined (target: 200 seeds per scenario, each run being a few thousand in-process events — no Docker, no real I/O, so this is expected to be fast; if a run after Task 8 shows otherwise, reduce the default count rather than raising the CI timeout).
- After every new file, run `sbt --batch "node-tests/compile"` before the test steps — Scala 3 has syntax nuances (this plan's code is grounded in real signatures pulled from the codebase but was not run through the compiler while writing this plan).
- Do NOT weaken or "fix" a failing assertion in Task 7 (the committee-mid-round scenario) by loosening the invariant or special-casing the scenario. Research grounding this plan (see spec) predicts this scenario may reveal a real consensus-safety gap (no joint-consensus-style atomic committee transition exists today — confirmed by code audit, see spec §3 Tier 1). If it fails, that is the intended, valuable outcome: stop, document the failing seed and the exact invariant violated, and escalate as a new finding rather than silencing the test.

---

### Task 1: `SimClock` — deterministic virtual-time event scheduler

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimClock.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimClockSpecification.scala`

**Interfaces:**
- Produces: `SimTime(millis: Long)` (ordered value class), `class SimClock(seed: Long)` with `def currentTime: SimTime`, `def random: scala.util.Random`, `def schedule(delayMillis: Long)(run: => Unit): Unit`, `def runToQuiescence(maxEvents: Int = 200000): Int`. All later tasks depend on exactly these four members.

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.test.FlatSpec

import scala.collection.mutable

class SimClockSpecification extends FlatSpec {
  "SimClock" should "fire scheduled events in time order, tie-broken by schedule order" in {
    val clock = new SimClock(seed = 1L)
    val fired = mutable.ListBuffer.empty[String]

    clock.schedule(30)(fired += "c")
    clock.schedule(10)(fired += "a")
    clock.schedule(10)(fired += "b") // same time as "a", scheduled after it -> fires after "a"
    clock.schedule(20)(fired += "d")

    val count = clock.runToQuiescence()

    count should be(4)
    fired.toList should be(List("a", "b", "d", "c"))
    clock.currentTime should be(SimTime(30))
  }

  it should "be perfectly reproducible for a fixed seed across two independent runs" in {
    def runOnce(): List[Int] = {
      val clock = new SimClock(seed = 42L)
      val fired = mutable.ListBuffer.empty[Int]
      (0 until 50).foreach { i =>
        val delay = clock.random.nextInt(100)
        clock.schedule(delay)(fired += i)
      }
      clock.runToQuiescence()
      fired.toList
    }

    runOnce() should be(runOnce())
  }
}
```

- [ ] **Step 2: Run test to verify it fails (file doesn't exist yet)**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SimClockSpecification"`
Expected: FAIL — compile error, `SimClock` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.consensus.hotstuff.sim

import scala.collection.mutable

/** Virtual time in the simulation, in whole milliseconds. Never derived from the wall clock. */
final case class SimTime(millis: Long) extends Ordered[SimTime] {
  def +(delta: Long): SimTime    = SimTime(millis + delta)
  def compare(that: SimTime): Int = millis.compareTo(that.millis)
}

private final case class ScheduledEvent(at: SimTime, seq: Long, run: () => Unit)

/** Deterministic virtual-time event loop: no threads, no wall clock. Replaying the same seed against
  * the same sequence of `schedule` calls always produces the same firing order, because ties at the
  * same `SimTime` are broken by `seq` (schedule call order), not by any hash-based or thread-based
  * nondeterminism.
  */
final class SimClock(seed: Long) {
  private val rng    = new scala.util.Random(seed)
  private var now     = SimTime(0)
  private var seqCtr  = 0L
  private val ordering = Ordering.by[ScheduledEvent, (Long, Long)](e => (e.at.millis, e.seq)).reverse
  private val queue    = mutable.PriorityQueue.empty[ScheduledEvent](ordering)

  def currentTime: SimTime      = now
  def random: scala.util.Random = rng

  /** Schedule `run` to fire after `delayMillis` of virtual time (must be >= 0). */
  def schedule(delayMillis: Long)(run: => Unit): Unit = {
    require(delayMillis >= 0, s"delayMillis must be >= 0, got $delayMillis")
    seqCtr += 1
    queue.enqueue(ScheduledEvent(now + delayMillis, seqCtr, () => run))
  }

  /** Drain events until the queue is empty or `maxEvents` have fired (a runaway-loop guard). Returns
    * the number of events actually fired.
    */
  def runToQuiescence(maxEvents: Int = 200000): Int = {
    var fired = 0
    while (queue.nonEmpty && fired < maxEvents) {
      val ev = queue.dequeue()
      now = ev.at
      ev.run()
      fired += 1
    }
    fired
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SimClockSpecification"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimClock.scala \
        node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimClockSpecification.scala
git commit -m "test: add SimClock deterministic virtual-time scheduler for HotStuff DST harness"
```

---

### Task 2: `SimNetwork` — fault-injecting deterministic message delivery

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimNetwork.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimNetworkSpecification.scala`

**Interfaces:**
- Consumes: `SimClock` (Task 1) — `clock.random`, `clock.schedule`.
- Produces: `FaultProfile(dropProbability: Double = 0.0, duplicateProbability: Double = 0.0, minDelayMillis: Long = 1, maxDelayMillis: Long = 5)`; `class SimNetwork[Msg](clock: SimClock, nodeCount: Int, faultProfile: FaultProfile)` with `def partition(from: Set[Int], to: Set[Int]): Unit`, `def healPartition(from: Set[Int], to: Set[Int]): Unit`, `def send(from: Int, to: Set[Int])(msg: Msg)(deliver: (Int, Msg) => Unit): Unit`. Task 3 depends on exactly this `send` signature.

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.test.FlatSpec

import scala.collection.mutable

class SimNetworkSpecification extends FlatSpec {
  "SimNetwork" should "deliver to every recipient except the sender when there are no faults" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile(dropProbability = 0.0, duplicateProbability = 0.0))
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.send(from = 0, to = Set(0, 1, 2, 3))("hello") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()

    delivered.map(_._1).sorted.toList should be(List(1, 2, 3))
  }

  it should "deliver nothing when dropProbability is 1.0" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile(dropProbability = 1.0))
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.send(from = 0, to = Set(0, 1, 2, 3))("hello") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()

    delivered shouldBe empty
  }

  it should "block delivery across an active partition and resume once healed" in {
    val clock     = new SimClock(seed = 7L)
    val network   = new SimNetwork[String](clock, nodeCount = 4, FaultProfile())
    val delivered = mutable.ListBuffer.empty[(Int, String)]

    network.partition(Set(0), Set(3))
    network.send(from = 0, to = Set(1, 2, 3))("a") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()
    delivered.map(_._1).sorted.toList should be(List(1, 2)) // node 3 dropped by the partition

    delivered.clear()
    network.healPartition(Set(0), Set(3))
    network.send(from = 0, to = Set(1, 2, 3))("b") { case (to, m) => delivered += ((to, m)) }
    clock.runToQuiescence()
    delivered.map(_._1).sorted.toList should be(List(1, 2, 3))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SimNetworkSpecification"`
Expected: FAIL — compile error, `SimNetwork`/`FaultProfile` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.consensus.hotstuff.sim

/** Per-link fault-injection knobs, all probabilities in [0.0, 1.0]. */
final case class FaultProfile(
    dropProbability: Double = 0.0,
    duplicateProbability: Double = 0.0,
    minDelayMillis: Long = 1,
    maxDelayMillis: Long = 5
) {
  require(dropProbability >= 0.0 && dropProbability <= 1.0, "dropProbability must be in [0,1]")
  require(duplicateProbability >= 0.0 && duplicateProbability <= 1.0, "duplicateProbability must be in [0,1]")
  require(minDelayMillis >= 0 && maxDelayMillis >= minDelayMillis, "delay range must be non-negative and ordered")
}

/** Deterministic, fault-injecting point-to-point network over a [[SimClock]]. Peers are indexed
  * `0 until nodeCount`. `deliver` is invoked (possibly more than once, possibly never) for each `send`
  * call, always via `clock.schedule`, so delivery order is governed entirely by the clock's seed.
  */
final class SimNetwork[Msg](clock: SimClock, nodeCount: Int, faultProfile: FaultProfile) {
  private val partitioned = scala.collection.mutable.Set.empty[(Int, Int)]

  def partition(from: Set[Int], to: Set[Int]): Unit =
    for (a <- from; b <- to if a != b) { partitioned += ((a, b)); partitioned += ((b, a)) }

  def healPartition(from: Set[Int], to: Set[Int]): Unit =
    for (a <- from; b <- to if a != b) { partitioned -= ((a, b)); partitioned -= ((b, a)) }

  /** Send `msg` from `from` to every peer in `to` (the sender is skipped even if present in `to`),
    * subject to fault injection (partition block, drop, duplicate, random delay).
    */
  def send(from: Int, to: Set[Int])(msg: Msg)(deliver: (Int, Msg) => Unit): Unit =
    to.foreach { recipient =>
      if (recipient != from && !partitioned.contains((from, recipient))) {
        if (clock.random.nextDouble() >= faultProfile.dropProbability) {
          val copies    = if (clock.random.nextDouble() < faultProfile.duplicateProbability) 2 else 1
          val delaySpan = (faultProfile.maxDelayMillis - faultProfile.minDelayMillis + 1).toInt
          (1 to copies).foreach { _ =>
            val delay = faultProfile.minDelayMillis + clock.random.nextInt(delaySpan)
            clock.schedule(delay)(deliver(recipient, msg))
          }
        }
      }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SimNetworkSpecification"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimNetwork.scala \
        node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SimNetworkSpecification.scala
git commit -m "test: add SimNetwork fault-injecting deterministic transport for HotStuff DST harness"
```

---

### Task 3: `DstHarness` — wire real `HotStuffCoordinator.Enabled` instances over `SimNetwork`

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/DstHarness.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/DstHarnessSpecification.scala`

**Interfaces:**
- Consumes: `SimClock`, `SimNetwork[Message]`, `FaultProfile` (Tasks 1-2); production `com.decentralchain.consensus.hotstuff.{HotStuffCoordinator, HotStuffEffects}`, `com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}`, `com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}`, `com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}` (all confirmed present and used identically in the existing `HotStuffSimulationSpecification.scala:1-22`).
- Produces: `case class CommitObservation(node: Int, blockId: BlockId, height: Int, at: SimTime)`; `class DstHarness(seed: Long, nodeCount: Int, faultProfile: FaultProfile = FaultProfile())` with `val clock: SimClock`, `val commits: mutable.ListBuffer[CommitObservation]`, `def leaderTurn(node: Int, view: Int, blockId: BlockId, blockHeight: Int): Unit`, `def tickTimeout(node: Int): Unit`, `def crash(node: Int): Unit`, `def restart(node: Int): Unit`, `def isLive(node: Int): Boolean`, `def setCommittee(next: GeneratorSet): Unit`, `def partition(a: Set[Int], b: Set[Int]): Unit`, `def healPartition(a: Set[Int], b: Set[Int]): Unit`, `def run(maxEvents: Int = 200000): Int`. Tasks 4-7 depend on exactly these members.

**Known scope limitation (document, don't paper over):** this harness models consensus **message safety** only. A "restarted" node keeps whatever in-memory `engine`/`pool` state it had before crashing — there is no block-sync/state-catch-up simulated here (that already exists as real behavior in `node-it`'s Docker-based `FourNodeHotStuffTestSuite`, which restarts real containers and does real sync). Do not write a scenario that asserts a crashed-then-restarted node "catches up" — that claim is out of scope for this harness.

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.test.FlatSpec

class DstHarnessSpecification extends FlatSpec {
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))

  "DstHarness" should "finalize the leader's block on every honest node with zero faults" in {
    val harness = new DstHarness(seed = 1L, nodeCount = 4, FaultProfile(dropProbability = 0.0))
    harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
    harness.run()

    harness.commits.map(c => c.node -> (c.blockId, c.height)).toMap should be(
      (0 to 3).map(_ -> (B, 100)).toMap
    )
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.DstHarnessSpecification"`
Expected: FAIL — compile error, `DstHarness` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffCoordinator, HotStuffEffects}
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}

import scala.collection.mutable

/** One committed-block observation, recorded for later invariant checking by [[SafetyInvariants]]. */
final case class CommitObservation(node: Int, blockId: BlockId, height: Int, at: SimTime)

/** Deterministic in-process HotStuff cluster simulation: `nodeCount` real `HotStuffCoordinator.Enabled`
  * instances (unmodified production consensus code) wired over a fault-injecting [[SimNetwork]] and
  * driven by a [[SimClock]]. No threads, no wall clock, no real networking — everything is reproducible
  * from `seed`. See the scope limitation note in this task's plan entry regarding restart/resync.
  */
final class DstHarness(seed: Long, nodeCount: Int, faultProfile: FaultProfile = FaultProfile()) {
  val clock: SimClock = new SimClock(seed)
  val commits: mutable.ListBuffer[CommitObservation] = mutable.ListBuffer.empty

  private val network       = new SimNetwork[Message](clock, nodeCount, faultProfile)
  private val kps           = (0 until nodeCount).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private var committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val live         = mutable.Set.from(0 until nodeCount)
  private val heightOfView = mutable.Map.empty[Int, Int]

  private class SimEffects(self: Int) extends HotStuffEffects {
    def broadcast(m: Message): Unit =
      network.send(from = self, to = live.toSet)(m) { case (to, msg) => deliver(to, msg) }
    def myVoterIndexes: Set[Int]                                   = Set(self)
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
    def onCommit(blockId: BlockId, height: Int): Unit              = commits += CommitObservation(self, blockId, height, clock.currentTime)
  }

  private val nodes: Map[Int, HotStuffCoordinator.Enabled] =
    (0 until nodeCount).map(i => i -> new HotStuffCoordinator.Enabled(() => committee, new SimEffects(i), (_, _) => true)).toMap

  private def deliver(to: Int, msg: Message): Unit =
    if (live.contains(to)) msg match {
      case p: HotStuffProposal   => nodes(to).onProposal(p, heightOfView.getOrElse(p.view, 0))
      case v: HotStuffVote       => nodes(to).onVote(v)
      case qc: QuorumCertificate => nodes(to).onQC(qc)
      case _                     => ()
    }

  /** Drive `node`'s turn to lead `view`, proposing `blockId` at `blockHeight`. Records `blockHeight`
    * for `view` so that receiving nodes' `onProposal` calls (which need an externally supplied height —
    * `HotStuffProposal` itself does not carry one) get the correct value.
    */
  def leaderTurn(node: Int, view: Int, blockId: BlockId, blockHeight: Int): Unit = {
    heightOfView(view) = blockHeight
    nodes(node).onLeaderTurn(view, blockId, blockHeight)
  }

  def tickTimeout(node: Int): Unit = nodes(node).onTimeout()

  def crash(node: Int): Unit   = live -= node
  def restart(node: Int): Unit = live += node
  def isLive(node: Int): Boolean = live.contains(node)

  def setCommittee(next: GeneratorSet): Unit = committee = next

  def partition(a: Set[Int], b: Set[Int]): Unit     = network.partition(a, b)
  def healPartition(a: Set[Int], b: Set[Int]): Unit = network.healPartition(a, b)

  /** Drain up to `maxEvents` scheduled events; returns the number actually fired. Call repeatedly with
    * a small `maxEvents` to interleave harness actions (crash, partition, committee change) mid-round.
    */
  def run(maxEvents: Int = 200000): Int = clock.runToQuiescence(maxEvents)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.DstHarnessSpecification"`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/DstHarness.scala \
        node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/DstHarnessSpecification.scala
git commit -m "test: add DstHarness wiring real HotStuffCoordinator over SimNetwork/SimClock"
```

---

### Task 4: `SafetyInvariants` — no-fork / no-regression checker

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SafetyInvariants.scala`
- Test: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SafetyInvariantsSpecification.scala`

**Interfaces:**
- Consumes: `CommitObservation` (Task 3).
- Produces: `object SafetyInvariants { def noFork(commits: Seq[CommitObservation]): Either[String, Unit]; def noRegression(commits: Seq[CommitObservation]): Either[String, Unit]; def checkAll(commits: Seq[CommitObservation]): Either[String, Unit] }`. Tasks 5-7 depend on `checkAll`.

- [ ] **Step 1: Write the failing test**

```scala
package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.test.FlatSpec

class SafetyInvariantsSpecification extends FlatSpec {
  private val B1: BlockId = ByteStr(Array.fill[Byte](32)(1))
  private val B2: BlockId = ByteStr(Array.fill[Byte](32)(2))

  "SafetyInvariants.noFork" should "pass when every node commits the same block at a given height" in {
    val commits = Seq(
      CommitObservation(0, B1, 100, SimTime(10)),
      CommitObservation(1, B1, 100, SimTime(11))
    )
    SafetyInvariants.noFork(commits) should be(Right(()))
  }

  it should "fail when two nodes commit different blocks at the same height" in {
    val commits = Seq(
      CommitObservation(0, B1, 100, SimTime(10)),
      CommitObservation(1, B2, 100, SimTime(11))
    )
    SafetyInvariants.noFork(commits).isLeft should be(true)
  }

  "SafetyInvariants.noRegression" should "fail when a node's committed height decreases" in {
    val commits = Seq(
      CommitObservation(0, B1, 100, SimTime(10)),
      CommitObservation(0, B1, 99, SimTime(20))
    )
    SafetyInvariants.noRegression(commits).isLeft should be(true)
  }

  it should "pass when a node's committed height is non-decreasing" in {
    val commits = Seq(
      CommitObservation(0, B1, 100, SimTime(10)),
      CommitObservation(0, B2, 101, SimTime(20))
    )
    SafetyInvariants.noRegression(commits) should be(Right(()))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SafetyInvariantsSpecification"`
Expected: FAIL — compile error, `SafetyInvariants` not found.

- [ ] **Step 3: Write minimal implementation**

```scala
package com.decentralchain.consensus.hotstuff.sim

/** Post-run SAFETY checks over a [[DstHarness]]'s recorded commits. Deliberately scoped to safety
  * (never two conflicting values agreed, never regresses), not liveness (whether/how fast progress is
  * made) — mirroring the same deliberate scope restriction already documented in node-it's
  * `FourNodeHotStuffTestSuite`.
  */
object SafetyInvariants {

  /** No two nodes ever commit different blockIds at the same height. */
  def noFork(commits: Seq[CommitObservation]): Either[String, Unit] =
    commits.groupBy(_.height).foldLeft[Either[String, Unit]](Right(())) {
      case (Right(()), (height, obs)) =>
        val distinctBlocks = obs.map(_.blockId).distinct
        if (distinctBlocks.size > 1)
          Left(s"FORK at height $height: conflicting blocks $distinctBlocks (observations: $obs)")
        else Right(())
      case (left, _) => left
    }

  /** Per node, committed height must never regress across the run (observations are in delivery
    * order within the sequence, since [[DstHarness]] appends to `commits` as `onCommit` fires).
    */
  def noRegression(commits: Seq[CommitObservation]): Either[String, Unit] =
    commits.groupBy(_.node).foldLeft[Either[String, Unit]](Right(())) {
      case (Right(()), (node, obs)) =>
        val heights = obs.map(_.height)
        heights.sliding(2).collectFirst { case Seq(a, b) if b < a => (a, b) } match {
          case Some((a, b)) => Left(s"REGRESSION on node $node: committed height went from $a to $b")
          case None         => Right(())
        }
      case (left, _) => left
    }

  def checkAll(commits: Seq[CommitObservation]): Either[String, Unit] =
    for {
      _ <- noFork(commits)
      _ <- noRegression(commits)
    } yield ()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.SafetyInvariantsSpecification"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SafetyInvariants.scala \
        node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/sim/SafetyInvariantsSpecification.scala
git commit -m "test: add SafetyInvariants no-fork/no-regression checker for HotStuff DST harness"
```

---

### Task 5: Crash-recovery seed-sweep scenario

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstCrashRecoveryScenarioSpecification.scala`

**Interfaces:**
- Consumes: `DstHarness`, `FaultProfile`, `SafetyInvariants` (Tasks 2-4).

- [ ] **Step 1: Write the test (this task has no separate "minimal implementation" step — the scenario spec IS the deliverable)**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.test.FlatSpec

/** DST scenario: a 4-node HotStuff cluster where one node crashes partway through a round, under
  * randomized message delay/drop/duplication, swept across many seeds. Mirrors node-it's
  * `FourNodeHotStuffTestSuite` "keeps safety when a generator crashes" scenario, but at in-process
  * simulation speed with a seed sweep instead of one Docker run.
  *
  * Scope: message-safety only. Does not assert the crashed node "catches up" after restart — see the
  * limitation documented on `DstHarness`.
  */
class DstCrashRecoveryScenarioSpecification extends FlatSpec {
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))
  private val SeedCount  = 200

  "a 4-node cluster with one node crashing mid-round" should
    "satisfy safety (no fork, no regression) and let the surviving 3 finalize, for every seed in the sweep" in {
      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(dropProbability = 0.05, duplicateProbability = 0.05))
        harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
        harness.run(maxEvents = 1 + harness.clock.random.nextInt(5))
        harness.crash(3)
        harness.run()

        withClue(s"seed=$seed: ") {
          SafetyInvariants.checkAll(harness.commits.toSeq) should be(Right(()))
          harness.commits.filter(_.node != 3).map(_.node).toSet should be(Set(0, 1, 2))
        }
      }
    }
}
```

- [ ] **Step 2: Run and confirm it passes (or, if it fails, that failure is itself the finding)**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.DstCrashRecoveryScenarioSpecification"`
Expected: PASS, 1 test (200 seeds checked inside it). If it fails, the `withClue(s"seed=$seed: ")` wrapper prints the exact failing seed — do not loosen the assertion; record the seed and escalate per Global Constraints.

- [ ] **Step 3: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstCrashRecoveryScenarioSpecification.scala
git commit -m "test: add DST crash-recovery seed-sweep scenario for HotStuff"
```

---

### Task 6: Network-partition seed-sweep scenario

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstPartitionScenarioSpecification.scala`

**Interfaces:**
- Consumes: `DstHarness`, `FaultProfile`, `SafetyInvariants` (Tasks 2-4).

- [ ] **Step 1: Write the test**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.test.FlatSpec

/** DST scenario: a 4-node HotStuff cluster where node 3 is partitioned away from the other 3 partway
  * through a round, then the partition heals. Mirrors node-it's `FourNodeHotStuffTestSuite`
  * "keeps safety across a network partition" scenario, swept across many seeds.
  */
class DstPartitionScenarioSpecification extends FlatSpec {
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))
  private val SeedCount  = 200

  "a 4-node cluster with node 3 partitioned away mid-round, then healed" should
    "satisfy safety (no fork, no regression) throughout, for every seed in the sweep" in {
      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(minDelayMillis = 1, maxDelayMillis = 3))
        harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
        harness.run(maxEvents = 1 + harness.clock.random.nextInt(5))
        harness.partition(Set(3), Set(0, 1, 2))
        harness.run(maxEvents = 5 + harness.clock.random.nextInt(20))
        harness.healPartition(Set(3), Set(0, 1, 2))
        harness.run()

        withClue(s"seed=$seed: ") {
          SafetyInvariants.checkAll(harness.commits.toSeq) should be(Right(()))
        }
      }
    }
}
```

- [ ] **Step 2: Run and confirm it passes**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.DstPartitionScenarioSpecification"`
Expected: PASS, 1 test (200 seeds checked inside it). Same non-negotiable-on-failure rule as Task 5.

- [ ] **Step 3: Commit**

```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstPartitionScenarioSpecification.scala
git commit -m "test: add DST network-partition seed-sweep scenario for HotStuff"
```

---

### Task 7: Committee-mid-round-change exploratory scenario (targets the suspected safety gap)

**Files:**
- Create: `node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstCommitteeChangeScenarioSpecification.scala`

**Interfaces:**
- Consumes: `DstHarness.setCommittee`, `SafetyInvariants` (Tasks 3-4); `com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}`, `com.decentralchain.account.KeyPair`, `com.decentralchain.common.state.ByteStr`, `com.decentralchain.crypto.bls.TestBlsKeyPair`.

**Why this scenario exists:** the code audit backing this plan (see spec, Tier 1) found that `HotStuffCoordinator.Enabled` re-reads `committeeProvider()` independently on every single event callback (`refreshCommittee()`, called at the top of `onProposal`/`onVote`/`onQC`/`onLeaderTurn`) with no requirement that all three phases (PREPARE/PRE_COMMIT/COMMIT) of one view agree on the same committee snapshot. This is structurally the same class of bug CockroachDB found and fixed via joint consensus in etcd/raft. This scenario is exploratory: it changes the committee between votes within a single view and checks whether `SafetyInvariants` still holds.

- [ ] **Step 1: Write the test**

```scala
package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec

/** EXPLORATORY DST scenario: changes the committee (via `DstHarness.setCommittee`) partway through a
  * single view's PREPARE -> PRE_COMMIT -> COMMIT sequence, then checks `SafetyInvariants`. Per the
  * code audit backing this plan, there is no atomic/joint-consensus-style committee transition in
  * `HotStuffCoordinator` today — `refreshCommittee()` re-reads the committee independently on every
  * event. If this test fails, that is a genuine finding, not a harness bug: STOP, record the failing
  * seed and the exact `SafetyInvariants` violation, and open a follow-up task to add an atomic
  * committee-transition mechanism to `HotStuffCoordinator` before HotStuff is enabled on mainnet. Do
  * not loosen this assertion to make it pass.
  */
class DstCommitteeChangeScenarioSpecification extends FlatSpec {
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))
  private val SeedCount  = 200

  private def committeeOf(stakes: Seq[Long]): GeneratorSet =
    stakes.zipWithIndex.map { case (stake, i) =>
      val kp = TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte))
      GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, stake)
    }

  "a 4-node cluster whose committee stake changes mid-view (between PREPARE and PRE_COMMIT)" should
    "still satisfy safety (no fork, no regression), for every seed in the sweep" in {
      var firstFailure: Option[(Long, String)] = None

      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(minDelayMillis = 1, maxDelayMillis = 3))
        harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
        harness.run(maxEvents = 1 + harness.clock.random.nextInt(3)) // let PREPARE votes start flowing
        harness.setCommittee(committeeOf(Seq(25L, 25L, 25L, 100L)))  // stake redistribution mid-round
        harness.run()

        SafetyInvariants.checkAll(harness.commits.toSeq) match {
          case Left(reason) if firstFailure.isEmpty => firstFailure = Some((seed, reason))
          case _                                     => ()
        }
      }

      firstFailure match {
        case None                  => succeed
        case Some((seed, reason)) =>
          fail(
            s"DST found a committee-mid-round-change safety violation at seed=$seed: $reason\n" +
              "This is the predicted finding from the code audit (no atomic committee transition in " +
              "HotStuffCoordinator). Do not silence this test — open a follow-up task to add one."
          )
      }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.DstCommitteeChangeScenarioSpecification"`
Two valid outcomes, both are a correct completion of this task:
- PASS: no violation found in 200 seeds at this fault-injection intensity. Note in the commit message that the scan was clean at `SeedCount = 200` and this should be revisited with a larger sweep as a nightly-tier extension (Tier 3 of the spec), since a clean 200-seed run does not prove the gap doesn't exist, only that it wasn't hit at this sample size.
- FAIL with a specific seed and reason: this is a real, valuable finding. Do not modify the assertion. Proceed to Step 3.

- [ ] **Step 3: Record the outcome and commit**

If PASS:
```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstCommitteeChangeScenarioSpecification.scala
git commit -m "test: add DST committee-mid-round-change exploratory scenario (clean at 200 seeds)"
```

If FAIL, first add a short note to this plan file recording the failing seed and violation text, then commit both:
```bash
git add node/tests/src/test/scala/com/decentralchain/consensus/hotstuff/DstCommitteeChangeScenarioSpecification.scala \
        docs/superpowers/plans/2026-07-24-hotstuff-dst-harness.md
git commit -m "test: add DST committee-mid-round-change scenario; found real safety violation, seed <N>"
```
and treat "add an atomic committee-transition mechanism to `HotStuffCoordinator`" as a new, separate finding to brainstorm and plan on its own — it is a production-code change to safety-critical consensus logic and must not be bundled into this test-only plan.

---

### Task 8: Final verification — full module run, runtime budget check

**Files:**
- No new files. Verifies Tasks 1-7 together.

- [ ] **Step 1: Run the entire HotStuff DST package**

Run: `sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.* com.decentralchain.consensus.hotstuff.Dst*"`
Expected: PASS (or the documented, escalated FAIL from Task 7 only — no other failures).

- [ ] **Step 2: Time the new tests to confirm the CI runtime budget is respected**

Run: `time sbt --batch "node-tests/testOnly com.decentralchain.consensus.hotstuff.sim.* com.decentralchain.consensus.hotstuff.Dst*"`
Expected: combined wall time well under 60 seconds (in-process simulation, no Docker, no real I/O — if this is not the case, reduce `SeedCount` in Tasks 5-7 before proceeding, per Global Constraints).

- [ ] **Step 3: Run the full existing `node-tests` module to confirm nothing else broke**

Run: `sbt --batch "node-tests/test"`
Expected: PASS, same test count as before plus the new specs from Tasks 1-7 (the pre-existing `HotStuffSimulationSpecification` and all other `node-tests` specs are untouched and must still pass).

- [ ] **Step 4: Update the design spec's status**

Edit `infra/docs/superpowers/specs/2026-07-24-e2e-testing-strategy-design.md`, changing the Tier 1 row's "New or extend" cell from "**new**" to "**done**", and add one line under §5 Open Questions recording whatever Task 7 found (clean sweep, or the real finding and its follow-up task).

- [ ] **Step 5: Commit**

```bash
cd /Users/jourlez/Documents/Code/Blockchain/Ecosystem/infra
git add docs/superpowers/specs/2026-07-24-e2e-testing-strategy-design.md
git commit -m "docs: mark E2E strategy Tier 1 (HotStuff DST harness) complete"
```

---

## What comes after this plan

This plan covers Tier 1 only (node-scala DST). Tiers 2-7 from the design spec — wiring matcher's `fullCheck` into CI, the cross-repo settlement E2E spec in DecentralChain, chaos/nemesis on real containers, Cosmos-SDK-style Operation fuzzing for the full tx pipeline, matcher fixed-point property tests, contract/schema conformance, and live canary monitoring — each need the same treatment this plan got: real codebase research first (matcher's actual REST handlers, DecentralChain's actual `e2e-blockchain` spec structure, the TS SDK's actual type-generation setup), then a dedicated plan per tier, following the same brainstorming-already-done -> writing-plans pattern. Do not write those plans from assumption; repeat the Explore-then-plan process this plan followed.
