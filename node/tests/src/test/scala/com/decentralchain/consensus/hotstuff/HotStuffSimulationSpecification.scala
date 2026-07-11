package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message, QuorumCertificate}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec

import scala.collection.mutable

/** Deterministic in-process simulation of the full 3-phase HotStuff loop across 4 coordinators
  * exchanging messages over a fake bus. Validates the `HotStuffCoordinator` orchestration
  * (propose → PREPARE → PRE_COMMIT → COMMIT → finalize) without real network/timers. Multi-node
  * IT under real faults/partitions is step 5.
  */
class HotStuffSimulationSpecification extends FlatSpec {
  private val kps                     = (0 until 4).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }
  private val B: BlockId = ByteStr(Array.fill[Byte](32)(42))
  private val H          = 100

  /** Runs the sim with `live` = the set of participating node indexes; returns the commit result per node. */
  private def run(live: Set[Int]): Map[Int, Option[(BlockId, Int)]] = {
    val inbox     = mutable.Queue.empty[(Int, Message)]
    val committed = mutable.Map.empty[Int, Option[(BlockId, Int)]]
    live.foreach(committed(_) = None)

    class SimEffects(self: Int) extends HotStuffEffects {
      def broadcast(m: Message): Unit                                = inbox.enqueue((self, m))
      def myVoterIndexes: Set[Int]                                   = Set(self)
      def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = if (idx == self) Some(kps(self).sign(msg)) else None
      def onCommit(blockId: BlockId, height: Int): Unit              = committed(self) = Some((blockId, height))
    }

    val nodes: Map[Int, HotStuffCoordinator.Enabled] =
      live.map(i => i -> new HotStuffCoordinator.Enabled(() => committee, new SimEffects(i), (_, _) => true)).toMap

    // View-0 leader (node 0) forges block B at height H and proposes.
    nodes(0).onLeaderTurn(0, B, H)

    var steps = 0
    while (inbox.nonEmpty && steps < 100000) {
      val (sender, msg) = inbox.dequeue()
      steps += 1
      nodes.foreach { case (j, node) =>
        if (j != sender) msg match {
          case p: HotStuffProposal   => node.onProposal(p, H)
          case v: HotStuffVote       => node.onVote(v)
          case qc: QuorumCertificate => node.onQC(qc)
          case _                     => ()
        }
      }
    }
    committed.toMap
  }

  "a 4-node HotStuff run" should "finalize the proposed block on every honest node" in {
    val result = run(Set(0, 1, 2, 3))
    (0 to 3).foreach(i => result(i) should be(Some((B, H))))
  }

  "a run with one crashed node" should "still finalize on the remaining 3 (75% >= 2/3)" in {
    val result = run(Set(0, 1, 2)) // node 3 absent
    (0 to 2).foreach(i => result(i) should be(Some((B, H))))
  }
}
