package com.decentralchain.consensus.hotstuff

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.network.{HotStuffVote, Message}
import com.decentralchain.state.{GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.FlatSpec
import io.decentralchain.protobuf.block.HotStuffPhase

/** T5 rev.2: coordinator-side equivocation detection + verified-evidence retention.
  * Design SSOT: docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md.
  *
  * Committee: 2 real BLS keypairs so votes verify for real (not mocked). Fake `HotStuffEffects`
  * records `onEquivocation` calls so this spec can assert the hook fires exactly once per
  * verified proof.
  */
class HotStuffEquivocationDetectionSpecification extends FlatSpec {

  private val kps                     = (0 until 2).map(i => TestBlsKeyPair.unsafe(Array.fill[Byte](32)((i + 1).toByte)))
  private val committee: GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), KeyPair(ByteStr(Array.fill[Byte](32)((100 + i).toByte))).toAddress, kp.publicKey, 25L)
  }

  private val prepare = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private def blockId(b: Byte): BlockId = ByteStr(Array.fill[Byte](32)(b))

  private def signedVote(voter: Int, view: Int, phase: HotStuffPhase, b: Byte, epoch: Int, keyPair: BlsKeyPair): HotStuffVote = {
    val bId    = blockId(b)
    val height = Height(10)
    val msg    = HotStuffQuorum.voteMessage(view, phase, bId, height.toInt, epoch)
    HotStuffVote(view, phase, bId, height, voter, ByteStr(keyPair.sign(msg, BlsUtils.BlsDomainSeparationTag).arr), epoch)
  }

  /** Same shape as a real vote but with a garbage signature -- never verifies against the named
    * voter's real key, exactly the "forged conflicting vote" scenario.
    */
  private def forgedVote(voter: Int, view: Int, phase: HotStuffPhase, b: Byte, epoch: Int): HotStuffVote =
    HotStuffVote(view, phase, blockId(b), Height(10), voter, ByteStr(Array.fill[Byte](96)(9: Byte)), epoch)

  private class RecordingEffects extends HotStuffEffects {
    var equivocations: Vector[HotStuffEquivocationProof] = Vector.empty
    def broadcast(m: Message): Unit                                = ()
    def myVoterIndexes: Set[Int]                                   = Set.empty // purely a receiver in this spec
    def signVote(msg: Array[Byte], idx: Int): Option[BlsSignature] = None
    def onCommit(blockId: BlockId, height: Int): Unit              = ()
    override def onEquivocation(proof: HotStuffEquivocationProof): Unit = equivocations :+= proof
  }

  private def newCoordinator(fx: RecordingEffects, epoch: Int = 0): HotStuffCoordinator.Enabled =
    new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true,
      committeeEpochProvider = () => epoch
    )

  "HotStuffCoordinator.Enabled" should "detect two verified conflicting votes from voter 0 at the same (view, phase, epoch)" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(signedVote(0, 5, prepare, 2, 0, kps(0)))

    coordinator.detectedEquivocations.size should be(1)
    coordinator.detectedEquivocations.head.voterIndex should be(0)
    fx.equivocations.size should be(1)
  }

  it should "not record a second proof or call onEquivocation again when either vote is re-delivered (dedup by voter/view/phase)" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    val voteA = signedVote(0, 5, prepare, 1, 0, kps(0))
    val voteB = signedVote(0, 5, prepare, 2, 0, kps(0))

    coordinator.onVote(voteA)
    coordinator.onVote(voteB)
    coordinator.onVote(voteA) // re-deliver
    coordinator.onVote(voteB) // re-deliver

    coordinator.detectedEquivocations.size should be(1)
    fx.equivocations.size should be(1)
  }

  it should "NOT record a proof for two votes from the same voter/view/phase but DIFFERENT committeeEpoch" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(signedVote(0, 5, prepare, 2, 1, kps(0))) // different epoch

    coordinator.detectedEquivocations should be(empty)
    fx.equivocations should be(empty)
  }

  it should "NOT record a proof when one vote is real and the conflicting one has a forged signature (cannot frame via forgery)" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(forgedVote(0, 5, prepare, 2, 0))

    coordinator.detectedEquivocations should be(empty)
    fx.equivocations should be(empty)
  }

  it should "prune proofs for a voter that is already excluded on-chain via pruneEquivocations(alreadyExcluded)" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(signedVote(0, 5, prepare, 2, 0, kps(0)))
    coordinator.detectedEquivocations.size should be(1)

    coordinator.pruneEquivocations(alreadyExcluded = _ == 0, currentPeriodIndex = 0)

    coordinator.detectedEquivocations should be(empty)
  }

  it should "prune proofs whose committeeEpoch is older than currentPeriodIndex (stale-epoch expiry)" in {
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(signedVote(0, 5, prepare, 2, 0, kps(0)))
    val proofEpoch = coordinator.detectedEquivocations.head.committeeEpoch
    coordinator.detectedEquivocations.size should be(1)

    coordinator.pruneEquivocations(alreadyExcluded = _ => false, currentPeriodIndex = proofEpoch + 1)

    coordinator.detectedEquivocations should be(empty)
  }

  it should "detect equivocation regardless of any slashing-enabled setting (detection is a coordinator behavior, no settings dependency)" in {
    // Constructed via newCoordinator above with no HotStuffSettings/slashingEnabled reference anywhere
    // -- detection is unconditional whenever HotStuff is enabled; the gate lives in the miner (Task 8).
    val fx          = new RecordingEffects
    val coordinator = newCoordinator(fx)

    coordinator.onVote(signedVote(0, 5, prepare, 1, 0, kps(0)))
    coordinator.onVote(signedVote(0, 5, prepare, 2, 0, kps(0)))

    coordinator.detectedEquivocations.size should be(1)
  }

  "HotStuffCoordinator.Disabled" should "return no detected equivocations and a no-op pruneEquivocations" in {
    HotStuffCoordinator.Disabled.detectedEquivocations should be(empty)
    HotStuffCoordinator.Disabled.pruneEquivocations(_ => true, 0) // must not throw
  }
}
