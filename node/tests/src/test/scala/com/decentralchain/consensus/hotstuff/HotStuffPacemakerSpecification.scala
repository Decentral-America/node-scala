package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.TestBlsKeyPair
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec

class HotStuffPacemakerSpecification extends FlatSpec {
  private def committeeOf(indexes: Seq[Int]): GeneratorSet =
    indexes.map { i =>
      val bls = TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)).publicKey
      GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, bls, balance = 10L)
    }

  private val committee = committeeOf(Seq(0, 1, 2, 3))

  "leaderFor" should "round-robin deterministically over the committee" in {
    HotStuffPacemaker.leaderFor(0, committee).map(_.toInt) should be(Some(0))
    HotStuffPacemaker.leaderFor(1, committee).map(_.toInt) should be(Some(1))
    HotStuffPacemaker.leaderFor(4, committee).map(_.toInt) should be(Some(0))  // wraps
    HotStuffPacemaker.leaderFor(-1, committee).map(_.toInt) should be(Some(3)) // floorMod, never crashes
  }

  it should "return None for an empty committee" in {
    HotStuffPacemaker.leaderFor(0, Seq.empty).map(_.toInt) should be(None)
  }

  it should "order by generator index regardless of input order" in {
    val shuffled = committeeOf(Seq(3, 1, 0, 2))
    HotStuffPacemaker.leaderFor(2, shuffled).map(_.toInt) should be(Some(2))
  }

  "isLeader" should "agree with leaderFor" in {
    HotStuffPacemaker.isLeader(1, 1, committee) should be(true)
    HotStuffPacemaker.isLeader(2, 1, committee) should be(false)
  }

  "onQC" should "advance to qcView+1 and never regress" in {
    HotStuffPacemaker.onQC(5, PacemakerState(3)).view should be(6)
    HotStuffPacemaker.onQC(2, PacemakerState(7)).view should be(7) // stale QC does not regress
  }

  "onTimeout" should "advance the view by one" in {
    HotStuffPacemaker.onTimeout(PacemakerState(9)).view should be(10)
  }
}
