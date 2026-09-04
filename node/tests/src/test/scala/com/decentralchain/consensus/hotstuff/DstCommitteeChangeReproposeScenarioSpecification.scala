package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.sim.{DstHarness, FaultProfile, SafetyInvariants}
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec

/** Adversarial-review Finding 2 on `consensus/hotstuff-repropose-locked-branch`: none of the three
  * existing DST scenario specs (`DstCrashRecoveryScenarioSpecification`, `DstPartitionScenarioSpecification`,
  * `DstCommitteeChangeScenarioSpecification`) ever exercised `HotStuffCoordinator.Enabled.onRoundTimerTick`
  * -- and therefore never exercised its `inFlightBranch` re-propose logic (commit 1347c83367) or Finding 1's
  * bounded-retry escape valve on top of it either -- because `DstHarness.tickTimeout` called bare
  * `onTimeout()` directly, and no scenario spec even called `tickTimeout` at all (confirmed by grep before
  * this fix: zero call sites outside `DstHarness` itself).
  *
  * This is now fixed at the harness level (`DstHarness.tickTimeout`/`tickTimeoutAll` now go through
  * `onRoundTimerTick`, with a per-node `blockSource` wired to that node's own last-committed tip -- see
  * `DstHarness`'s doc comments), which is a safe, zero-regression change for the 3 existing specs (none of
  * them called `tickTimeout` before, so none of them are affected; re-run and confirmed green). This spec
  * additionally closes the coverage gap directly: it drives a real, QC'd-but-not-yet-committed branch
  * through `onRoundTimerTick` while the committee changes mid-round (reusing `DstCommitteeChangeScenarioSpecification`'s
  * stake-redistribution pattern), instead of letting the round complete via ordinary vote delivery alone.
  *
  * Scope: safety only (no fork, no regression), exactly like its three siblings -- see `SafetyInvariants`'s
  * doc comment for why liveness is deliberately out of scope for this harness's checks.
  */
class DstCommitteeChangeReproposeScenarioSpecification extends FlatSpec {
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))
  private val SeedCount  = 100

  private def committeeOf(stakes: Seq[Long]): GeneratorSet =
    stakes.zipWithIndex.map { case (stake, i) =>
      val kp = TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte))
      GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, stake)
    }

  "a 4-node cluster whose committee stake changes mid-view, with the remainder of the round driven by onRoundTimerTick leader-timeouts (not further organic vote delivery)" should
    "still satisfy safety (no fork, no regression), for every seed in the sweep" in {
      var firstFailure: Option[(Long, String)] = None
      var anyCommitObserved                    = false

      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(minDelayMillis = 1, maxDelayMillis = 3))
        harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
        // Same pre-switch calibration as `DstCommitteeChangeScenarioSpecification`: a short budget that
        // straddles first-QC-formation, so roughly half the seeds switch committee before B's first QC
        // forms and roughly half switch just after -- i.e. B is a genuine QC'd-but-not-yet-committed
        // in-flight branch (`SafetyState.prepareQC`) at the moment the committee changes underneath it.
        harness.run(maxEvents = 4 + harness.clock.random.nextInt(6))
        harness.setCommittee(committeeOf(Seq(25L, 25L, 25L, 100L))) // stake redistribution mid-round

        // THE GAP THIS SPEC CLOSES: instead of `harness.run()` (unbounded organic delivery, which never
        // touches `onRoundTimerTick` at all), repeatedly fire the shared round-timer on every live node
        // and flush whatever it broadcasts. Any node whose round has stalled (no QC progress since the
        // last tick) re-evaluates `inFlightBranch` under the NEW committee and either re-proposes B (if
        // still QC'd-but-uncommitted and under Finding 1's retry cap) or falls back to its own committed
        // tip once the cap is exhausted -- exactly the leader-timeout path production uses, now actually
        // exercised end-to-end under a real mid-round committee change instead of only in the narrower,
        // single-node `HotStuffViewChangeSpecification` unit tests.
        (1 to 40).foreach { _ =>
          harness.tickTimeoutAll()
          harness.run(maxEvents = 50)
        }
        harness.run() // drain anything still in flight

        if (harness.commits.nonEmpty) anyCommitObserved = true

        SafetyInvariants.checkAll(harness.commits.toSeq, harness.votes.toSeq) match {
          case Left(reason) if firstFailure.isEmpty => firstFailure = Some((seed, reason))
          case _                                    => ()
        }
      }

      // Sanity: this scenario must not be vacuous -- at least some seeds in the sweep must actually reach
      // a commit (proving `onRoundTimerTick`'s re-propose path is genuinely exercised to a conclusion,
      // not merely invoked and then discarded every time).
      anyCommitObserved should be(true)

      firstFailure match {
        case None                 => succeed
        case Some((seed, reason)) =>
          fail(
            s"DST found a committee-mid-round-change safety violation at seed=$seed while driven by " +
              s"onRoundTimerTick leader-timeouts: $reason\n" +
              "Do not silence this test -- this is the exact path Finding 2 (adversarial review) required " +
              "coverage for."
          )
      }
    }
}
