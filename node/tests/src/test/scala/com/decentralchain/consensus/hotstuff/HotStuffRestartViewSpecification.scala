package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsSignature, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffProposal, HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

import scala.collection.mutable

/** Live testnet regression, 2026-10-07: every node restarted at once for a binary rollout and HotStuff never
  * committed again (watchdog: "9 consecutive recovery attempts have ALL failed"). Each node restored its
  * persisted `lastVotedView` (382843..382881, `HotStuffLastVotedViewStore`, the M1 anti-double-vote fix) but
  * its pacemaker restarted at view 0, and `HotStuffSafety.safeToVote` requires `proposal.view >
  * lastVotedView`. No replica could vote until round timeouts carried the view past ~382,843 (about five days
  * at 1200 ms). A lone restart recovered only because a peer at a high view pulled it up with a QC.
  *
  * Every view <= the restored `lastVotedView` is unusable for this replica (already voted, or must not vote),
  * so the pacemaker must start just above it, and above a restored lock.
  */
class HotStuffRestartViewSpecification extends FlatSpec {
  private val kp                      = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(7))
  private val me                      = KeyPair(ByteStr(Array.fill[Byte](32)(77))).toAddress
  private val committee: GeneratorSet = Seq(GeneratorInfo(GeneratorIndex(0), me, kp.publicKey, 100))
  private val blockId: BlockId        = ByteStr(Array.fill[Byte](32)(0x5a.toByte))

  private def coordinator(lastVotedView: Int, sent: mutable.ListBuffer[Message]): HotStuffCoordinator.Enabled = {
    val fx = new HotStuffEffects {
      def broadcast(m: Message): Unit                                             = sent += m
      def myVoterIndexes: Set[Int]                                                = Set(0)
      def signVote(msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] = Some(kp.sign(msg, dst))
      def onCommit(blockId: BlockId, height: Int): Unit                           = ()
      def onEquivocation(proof: HotStuffEquivocationProof): Unit                  = ()
    }
    new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true,
      initialLastVotedView = lastVotedView
    )
  }

  "a replica restarted with a persisted lastVotedView" should "start its pacemaker above it, so it can vote again immediately" in {
    val sent = mutable.ListBuffer.empty[Message]
    val r    = coordinator(lastVotedView = 382843, sent)

    withClue("the pacemaker must not restart below views this replica can no longer vote in: ") {
      r.currentView should be > 382843
    }

    // The leader of the current view proposes; the replica must be able to vote for it.
    r.onProposal(HotStuffProposal(r.currentView, blockId, None), 1000)
    sent.collectFirst { case v: HotStuffVote if v.phase == HotStuffPhase.HOTSTUFF_PHASE_PREPARE => v } should not be empty
  }

  "a fresh replica (no persisted view)" should "still start at view 0" in {
    coordinator(lastVotedView = -1, mutable.ListBuffer.empty).currentView shouldBe 0
  }
}
