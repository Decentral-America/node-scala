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
