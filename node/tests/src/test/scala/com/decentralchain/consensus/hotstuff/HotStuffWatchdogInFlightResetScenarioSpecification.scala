package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.test.FlatSpec

import java.nio.file.{Files, Paths}

/** DST scenario for audit finding F-2 (HIGH, 2026-08-31): fire the watchdog with realistic traffic
  * GENUINELY IN FLIGHT, and assert no node ever double-signs a `(view, phase)`.
  *
  * WHY A NEW SCENARIO WAS NEEDED. The audit's central complaint about the existing coverage is that
  * `HotStuffWatchdogDstReproductionSpecification` only ever fires the watchdog while node 0 is FULLY
  * PARTITIONED (`harness.partition(Set(0), Set(1,2,3))`). Inside a full partition no competing proposal
  * can reach the recovering node during or after its reset, so that scenario is STRUCTURALLY incapable
  * of exercising this bug class -- not merely unlucky with seeds. Two further harness defaults
  * compounded it: `extendsBranch = (_, _) => true` and `proposalValid = _ => true`, which between them
  * disable both of the guards that would otherwise shape which proposals are admissible.
  *
  * WHAT THIS SCENARIO DOES DIFFERENTLY:
  *   1. NO partition. Node 0's round genuinely stalls because the other replicas' PREPARE votes are
  *      delayed in the network, not because it was cut off -- so its own votes for view `v` are truly
  *      in flight (no QC back, so nothing ever calls `recordProgress()`) exactly when the watchdog
  *      fires. This is the interleaving the audit traced, and it cannot be reached through a partition.
  *   2. A REALISTIC `proposalValid`, wired via the harness's new per-node hook, mirroring production's
  *      `Application.scala` guard (`blockchainUpdater.heightOf(blockId).isDefined`): a node votes only
  *      for a block it independently recognizes as resident on its OWN chain. Both competing blocks
  *      below are deliberately made chain-resident at DIFFERENT heights, which is the audit's path (b)
  *      -- the one needing NO reorg and NO fabricated block, only a leader re-proposing a different but
  *      entirely real, canonical block under the same view number. `proposalValid` is deliberately
  *      view-agnostic (documented at `HotStuffCoordinator.scala`), so it CANNOT reject this by design;
  *      the only thing that ever could is `lastVotedView`, which is precisely what the bug cleared.
  *   3. Two COMPETING candidate blockIds at the SAME view -- unlike every pre-existing DST scenario,
  *      which injects only one candidate per round and therefore leaves the fork/equivocation checks
  *      structurally vacuous (a limitation stated plainly in
  *      `DstEmptyCommitteeSourceScenarioSpecification`'s own docstring).
  *
  * Pre-fix this test FAILS at `noEquivocation` (node 0 signs PREPARE for two different blocks at the
  * same view). Post-fix `lastVotedView` survives the reset, the second proposal is refused, and the
  * run is clean.
  */
class HotStuffWatchdogInFlightResetScenarioSpecification extends FlatSpec {

  private def blockAt(n: Byte): BlockId = ByteStr(Array.fill[Byte](32)(n))

  private def tempLockPath(node: Int) = {
    val dir = Files.createTempDirectory(s"hotstuff-inflight-reset-$node")
    dir.toFile.deleteOnExit()
    Paths.get(dir.toString, "locked-qc.dat")
  }

  "the watchdog firing while this replica's own votes are genuinely in flight (no partition)" should
    "never cause it to sign two conflicting blocks at the same (view, phase)" in {
      // Both blocks are real and canonical on every node's chain, at DIFFERENT heights -- audit path
      // (b). `settledDepth` means several recent settled heights are all legitimately chain-resident
      // simultaneously in production, so this needs no reorg and no Byzantine fabrication.
      val blockA       = blockAt(1)
      val blockB       = blockAt(2)
      val chainHeights = Map(blockA -> 100, blockB -> 101)

      var harness: DstHarness = null
      val watchdog            = new HotStuffWatchdog(
        committeeNonEmpty = () => harness.currentCommittee().nonEmpty,
        lockPath = tempLockPath(0),
        resetInMemoryState = () => harness.resetLocalSafetyState(0),
        // Fire fast: the whole point is to land the reset INSIDE the in-flight window, before the
        // round's votes have had a chance to come back and form a QC.
        stallThreshold = 2
      )

      harness = new DstHarness(
        seed = 7L,
        nodeCount = 4,
        // Long, jittery delays: votes stay genuinely in flight across the watchdog's firing window
        // rather than resolving instantly. This is what replaces the partition as the stall source.
        FaultProfile(minDelayMillis = 50, maxDelayMillis = 200),
        onAction = (node, action) =>
          if (node == 0) action match {
            case _: HotStuffAction.Rejected => () // same Rejected-excluding filter as production
            case _                          => watchdog.recordProgress()
          },
        // The realistic guard the audit asked for: chain-membership only, exactly as production wires
        // it. Note both competing blocks PASS this -- it is view-agnostic and both blocks are real.
        proposalValid = (_, blockId) => chainHeights.contains(blockId)
      )

      // Round at view 0: node 0 leads and proposes block A. Every node votes PREPARE for A; those
      // votes are now in flight under the long network delays above.
      harness.leaderTurn(node = 0, view = 0, blockId = blockA, blockHeight = chainHeights(blockA))
      // Drain only a FEW events: enough for the proposal to land and votes to be cast, but NOT enough
      // for the votes to be delivered and a QC to form. This is the in-flight window.
      harness.run(maxEvents = 4)

      // The watchdog fires mid-flight. No QC has formed, so `recordProgress()` never ran.
      var fired = false
      (1 to 3).foreach { _ =>
        if (watchdog.check()) fired = true
        harness.run(maxEvents = 2) // keep the window narrow; do not let the round resolve
      }
      fired should be(true) // the scenario genuinely exercised the reset path

      // A competing proposal for block B arrives at the SAME view 0, from a different leader, naming a
      // real block at a different (also canonical) height. Pre-fix, node 0's `lastVotedView` was -1 and
      // its `lockedQC` None, so `0 > -1` admitted this and it double-signed view 0.
      harness.leaderTurn(node = 1, view = 0, blockId = blockB, blockHeight = chainHeights(blockB))
      harness.run()

      // THE F-2 ASSERTION. Every node here runs unmodified production coordinator code and is honest by
      // construction, so any equivocation is self-inflicted by the recovery path.
      SafetyInvariants.noEquivocation(harness.votes.toSeq) should be(Right(()))

      // Node 0 specifically: at most one distinct block signed per (view, phase).
      harness.votes.toSeq
        .filter(_.node == 0)
        .groupBy(o => (o.vote.view, o.vote.phase))
        .foreach { case (k, os) =>
          withClue(s"node 0 signed multiple blocks at $k: ") {
            os.map(_.vote.blockId).distinct.size should be(1)
          }
        }

      // And the commit-level invariants still hold across the whole run.
      SafetyInvariants.checkAll(harness.commits.toSeq, harness.votes.toSeq) should be(Right(()))
    }

  it should "not freeze the recovered replica out of a later view -- the preserved lastVotedView is a bound, not a halt" in {
    // Guards against over-correcting: the fix must not freeze a recovered node out of future rounds.
    // The pacemaker's view (`PacemakerState`) is a SEPARATE `EngineState` field that
    // `resetLocalSafetyState` never touches, and `HotStuffPacemaker.onTimeout` bumps it on every
    // stalled tick -- so genuine post-recovery traffic always arrives above `lastVotedView`.
    //
    // SCOPE, STATED HONESTLY (mutation-tested post-review): what this test genuinely establishes is the
    // `lastVotedView` half -- that preserving the bound does not freeze the replica out of a later,
    // higher view. It does NOT establish the "lockedQC was actually cleared" half, and would still pass
    // against a `resetLocalSafetyState` that cleared nothing at all. The reason is structural to this
    // harness, not fixable by reordering this test: `DstHarness` hardcodes `extendsBranch = (_, _) =>
    // true`, so `HotStuffSafety.safeToVote`'s lock branch is satisfied by the `safety` (extends-the-
    // locked-branch) clause for ANY block, meaning a held lock never blocks anything here and its
    // clearing is unobservable. The lock-clearing property is covered behaviourally instead by
    // `HotStuffResetDoubleVoteSpecification`'s "should still clear lockedQC/prepareQC..." case (which
    // builds a real PRE_COMMIT-derived lock under `extendsBranch = false` and fails if the reset stops
    // clearing it) and by `HotStuffWatchdogSpecification`'s "should actually clear the coordinator's
    // in-memory lockedQC ... proven BEHAVIORALLY" case. Both catch the no-op mutation this one cannot.
    val blockA       = blockAt(1)
    val blockB       = blockAt(2)
    val chainHeights = Map(blockA -> 100, blockB -> 101)

    var harness: DstHarness = null
    val watchdog            = new HotStuffWatchdog(
      committeeNonEmpty = () => harness.currentCommittee().nonEmpty,
      lockPath = tempLockPath(1),
      resetInMemoryState = () => harness.resetLocalSafetyState(0),
      stallThreshold = 2
    )

    harness = new DstHarness(
      seed = 11L,
      nodeCount = 4,
      FaultProfile(minDelayMillis = 1, maxDelayMillis = 3),
      onAction = (node, action) =>
        if (node == 0) action match {
          case _: HotStuffAction.Rejected => ()
          case _                          => watchdog.recordProgress()
        },
      proposalValid = (_, blockId) => chainHeights.contains(blockId)
    )

    harness.leaderTurn(node = 0, view = 0, blockId = blockA, blockHeight = chainHeights(blockA))
    harness.run()
    val votesBefore = harness.votes.size

    watchdog.check()
    watchdog.check() // fires -> resetLocalSafetyState(0)

    // A LEGITIMATE later round at a strictly higher view: this must still be votable.
    harness.leaderTurn(node = 1, view = 1, blockId = blockB, blockHeight = chainHeights(blockB))
    harness.run()

    harness.votes.size should be > votesBefore // recovery preserved: node(s) still voting
    harness.votes.toSeq.exists(o => o.node == 0 && o.vote.view == 1 && o.vote.blockId == blockB) should be(true)
    SafetyInvariants.noEquivocation(harness.votes.toSeq) should be(Right(()))
  }
}
