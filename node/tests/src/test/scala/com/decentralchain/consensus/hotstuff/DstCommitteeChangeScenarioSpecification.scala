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
