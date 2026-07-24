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
