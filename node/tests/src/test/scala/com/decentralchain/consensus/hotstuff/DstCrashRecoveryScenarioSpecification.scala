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
    "satisfy safety (no fork, no regression), for every seed in the sweep" in {
      (0 until SeedCount).foreach { seed =>
        val harness = new DstHarness(seed, nodeCount = 4, FaultProfile(dropProbability = 0.05, duplicateProbability = 0.05))
        harness.leaderTurn(node = 0, view = 0, blockId = B, blockHeight = 100)
        harness.run(maxEvents = 1 + harness.clock.random.nextInt(5))
        harness.crash(3)
        harness.run()

        withClue(s"seed=$seed: ") {
          SafetyInvariants.checkAll(harness.commits.toSeq) should be(Right(()))
        }
      }
    }
}
