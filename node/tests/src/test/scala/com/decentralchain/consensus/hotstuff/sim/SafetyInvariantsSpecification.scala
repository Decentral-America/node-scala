package com.decentralchain.consensus.hotstuff.sim

import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

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

  // ---- Audit F-2: vote-level equivocation detection ----

  private def vote(voter: Int, view: Int, blockId: BlockId, height: Int = 100) =
    VoteObservation(
      voter,
      HotStuffVote(view, HotStuffPhase.HOTSTUFF_PHASE_PREPARE, blockId, Height(height), voter, ByteStr(Array.fill[Byte](8)(0))),
      SimTime(view.toLong)
    )

  "SafetyInvariants.noEquivocation" should "fail when a node signs two different blocks at the same (view, phase)" in {
    val result = SafetyInvariants.noEquivocation(Seq(vote(0, 5, B1), vote(0, 5, B2)))
    result.isLeft should be(true)
    result.left.toOption.get should include("EQUIVOCATION")
  }

  it should "pass when a node signs the same block twice at the same (view, phase)" in {
    // Re-broadcasting an identical vote is not equivocation -- only two DIFFERENT blockIds are.
    SafetyInvariants.noEquivocation(Seq(vote(0, 5, B1), vote(0, 5, B1))) should be(Right(()))
  }

  it should "pass when a node signs different blocks at DIFFERENT views" in {
    SafetyInvariants.noEquivocation(Seq(vote(0, 5, B1), vote(0, 6, B2))) should be(Right(()))
  }

  it should "pass when DIFFERENT nodes sign different blocks at the same view" in {
    // Two honest replicas disagreeing across a partition is not equivocation by either of them.
    SafetyInvariants.noEquivocation(Seq(vote(0, 5, B1), vote(1, 5, B2))) should be(Right(()))
  }

  it should "pass on an empty vote log" in {
    SafetyInvariants.noEquivocation(Seq.empty) should be(Right(()))
  }

  "SafetyInvariants.checkAll(commits, votes)" should "surface an equivocation even when the commit-level invariants hold" in {
    val commits = Seq(CommitObservation(0, B1, 100, SimTime(10)))
    SafetyInvariants.checkAll(commits) should be(Right(()))                                        // commits alone look clean...
    SafetyInvariants.checkAll(commits, Seq(vote(0, 5, B1), vote(0, 5, B2))).isLeft should be(true) // ...votes are not
  }
}
