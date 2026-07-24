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
