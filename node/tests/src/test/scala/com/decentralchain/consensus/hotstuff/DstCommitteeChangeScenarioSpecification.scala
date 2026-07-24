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
  *
  * Pre-switch event budget (`4 + nextInt(6)`, i.e. 4-9 fired events before `setCommittee`): calibrated
  * empirically (temporary instrumentation of `DstHarness`, since removed) against this exact 4-node /
  * `FaultProfile(minDelayMillis=1, maxDelayMillis=3)` setup. Findings: (1) the full undisturbed round
  * (leaderTurn -> every node committed) always takes exactly 75 fired events for every one of the 200
  * seeds — so any budget in the single digits is nowhere near full completion; (2) the first PREPARE QC
  * forms (at whichever node hits quorum first) after 4-8 fired events depending on seed/delay draws
  * (distribution roughly: 4:~1%, 5:~5%, 6:~12%, 7:~43%, 8:~40%). A budget of 1-3 (the original, buggy
  * value) is strictly below that floor and can *never* let any QC form first — confirmed empirically:
  * 0/200 seeds saw a QC before the switch. With 4-9, re-measured against the real call sequence (i.e.
  * with the budget's own `nextInt` draw consuming from the same `clock.random` stream that also drives
  * delivery delays, exactly as the production call does): ~109/200 seeds (~55%) still switch committee
  * before any QC forms (exercising the "old" no-QC-yet hazard), and ~91/200 (~45%) switch strictly after
  * at least one node has already formed a PREPARE QC under the OLD committee while other nodes are still
  * mid-flight toward their own QC under what will become a stale-vs-fresh committee view once
  * `refreshCommittee()` re-reads — i.e. the genuine "mid-view, cross-committee QC" hazard this test is
  * meant to probe. Neither bucket is ever ~0%, and the round is never anywhere close to full completion
  * at switch time, so the sweep now exercises both regimes on every run instead of only the pre-QC one.
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
        // Calibrated to genuinely straddle first-QC-formation (see docstring above): ~55% of seeds
        // switch before any QC forms, ~45% switch after one has already formed under the old committee.
        harness.run(maxEvents = 4 + harness.clock.random.nextInt(6))
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
