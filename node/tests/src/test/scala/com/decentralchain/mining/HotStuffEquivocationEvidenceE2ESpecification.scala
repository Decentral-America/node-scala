package com.decentralchain.mining

import com.decentralchain.account.KeyPair
import com.decentralchain.block.{Block, FinalizationVoting}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffCoordinator, HotStuffEffects, HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature}
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.network.{HotStuffVote, Message}
import com.decentralchain.state.{FinalizationState, GeneratorIndex, GeneratorInfo, GeneratorSet, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.{HotStuffPhase, PBFinalizationVoting}
import io.decentralchain.protobuf.block.PBFinalizationVotings

/** Task 10: end-to-end determinism proof for HotStuff equivocation evidence (T5 rev.2, deliverable
  * Critical #2) -- two independent verifier paths, fed the SAME wire bytes, must converge on the
  * SAME `conflictGenerators` exclusion.
  *
  * Chain of custody exercised here, each link a real production entrypoint (no stubs/mocks of the
  * consensus-critical pieces):
  *
  *   1. DETECTING node: `HotStuffCoordinator.Enabled` ingests two conflicting real-BLS-signed votes
  *      from the same committee member -> `detectedEquivocations` yields a verified proof `P`
  *      (see `HotStuffEquivocationDetectionSpecification`).
  *   2. MINER: `Miner.foldHotStuffConflicts` (production-gated by `slashingEnabled`) folds `P` into
  *      a `FinalizationVoting`, which is then serialized to real protobuf WIRE BYTES via
  *      `PBFinalizationVotings.protobuf(...).toByteArray` and immediately DESERIALIZED back
  *      (`PBFinalizationVoting.parseFrom` + `PBFinalizationVotings.vanilla`) -- standing in for the
  *      network hop.
  *   3. RECEIVING node: a domain that NEVER saw the votes appends a block carrying the deserialized
  *      `FinalizationVoting` through the real appender (`appendBlockWithoutFallback`, feature 29
  *      active) -- see `HotStuffEquivocationValidationSpecification`, whose fixture this reuses.
  *   4. Both the detecting side's `FinalizationState.append` and the receiving side's produce
  *      IDENTICAL `conflictGenerators` sets from the same `FinalizationVoting`.
  *   5. Negative: flip one byte of voteB's signature in the serialized proof bytes -> the receiving
  *      node's `validateFinalizationVoting` rejects the whole block.
  */
class HotStuffEquivocationEvidenceE2ESpecification extends BaseFinalizationSpec {

  private val minerGenerator = TxHelpers.signer(0)
  private val voterA         = TxHelpers.signer(1)
  private val voterB         = TxHelpers.signer(2)
  private val voterC         = TxHelpers.signer(3)

  private val voterBIdx = GeneratorIndex(2)

  private val generators = Seq(minerGenerator, voterA, voterB, voterC)

  private val withEvidenceFeature =
    DomainPresets.DeterministicFinality
      .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
      .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0))

  /** Task 9 step (d) / Task 8 review carry-over: BlsCryptoV2 PRE-activated (from height 1, alongside
    * every other feature here) -- there is no activation-period boundary to navigate, so every vote
    * cast anywhere in this chain's history is unambiguously v2-era, and `validateHotStuffEquivocationProofs`'s
    * "refuse every proof carried by a block in the activation period" rule (see
    * `state/appender/package.scala`) simply never triggers.
    */
  private val withEvidenceFeatureV2 =
    withEvidenceFeature.addFeatures(BlockchainFeatures.BlsCryptoV2)

  private val prepare = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  // Same period-index arithmetic as HotStuffEquivocationValidationSpecification: block height 3
  // falls in the SECOND generation period (index 1) under generationPeriodLength = 2.
  private val periodIndex = 1

  private def signedVote(signer: KeyPair, voterIndex: Int, view: Int, blockIdByte: Byte, epoch: Int, cryptoV2: Boolean): HotStuffVote = {
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val height  = Height(10)
    val msg     = HotStuffQuorum.voteMessage(view, prepare, blockId, height.toInt, epoch)
    val kp      = BlsKeyPair(signer.privateKey)
    HotStuffVote(view, prepare, blockId, height, voterIndex, ByteStr(kp.sign(msg, HotStuffQuorum.voteDst(cryptoV2)).arr), epoch)
  }

  private def withCommittedCommittee(test: (com.decentralchain.history.Domain, Block) => Unit): Unit =
    withCommittedCommittee(withEvidenceFeature)(test)

  private def withCommittedCommittee(settings: com.decentralchain.settings.DCCSettings)(
      test: (com.decentralchain.history.Domain, Block) => Unit
  ): Unit =
    withDomain(settings, AddrWithBalance.enoughBalances(generators*)) { d =>
      // The commitment block lands at height 2 -- PoP era must match whatever `settings` says is live
      // at that height (BlsCryptoV2 pre-activated from height 1 in `withEvidenceFeatureV2`, absent in
      // `withEvidenceFeature`), or CommitToGenerationTransactionDiff rejects the whole setup block.
      val commitmentsAreV2      = d.blockchain.supportsBlsCryptoV2(2)
      val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x, cryptoV2 = commitmentsAreV2))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = minerGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)
      test(d, block2WithCommitments)
    }

  private class RecordingEffects extends HotStuffEffects {
    var equivocations: Vector[HotStuffEquivocationProof]                        = Vector.empty
    def broadcast(m: Message): Unit                                             = ()
    def myVoterIndexes: Set[Int]                                                = Set.empty
    def signVote(msg: Array[Byte], idx: Int, dst: String): Option[BlsSignature] = None
    def onCommit(blockId: Block.BlockId, height: Int): Unit                     = ()
    override def onEquivocation(proof: HotStuffEquivocationProof): Unit         = equivocations :+= proof
  }

  /** Round-trips a `FinalizationVoting` through REAL protobuf wire bytes -- the receiving node in
    * this test never saw the votes directly, only these bytes.
    */
  private def throughWire(fv: FinalizationVoting): FinalizationVoting = {
    val bytes = PBFinalizationVotings.protobuf(fv).toByteArray
    PBFinalizationVotings.vanilla(PBFinalizationVoting.parseFrom(bytes)).get
  }

  private def committeeOf(kps: Seq[KeyPair]): GeneratorSet = kps.zipWithIndex.map { case (kp, i) =>
    GeneratorInfo(GeneratorIndex(i), kp.toAddress, BlsKeyPair(kp.privateKey).publicKey, 25L)
  }

  /** Steps 1-2: DETECTING node's coordinator ingests voterB's two conflicting votes, then the
    * MINER folds the resulting proof into a `FinalizationVoting` and round-trips it through real
    * protobuf wire bytes. Returns (detectedProof, foldedFv, wire-round-tripped fv).
    */
  private def detectFoldAndSend(cryptoV2: Boolean = false): (HotStuffEquivocationProof, FinalizationVoting, FinalizationVoting) = {
    val committee   = committeeOf(generators)
    val fx          = new RecordingEffects
    val coordinator = new HotStuffCoordinator.Enabled(
      committeeProvider = () => committee,
      effects = fx,
      extendsBranch = (_, _) => true,
      committeeEpochProvider = () => periodIndex,
      cryptoV2 = () => cryptoV2
    )

    val voteA = signedVote(voterB, voterBIdx.toInt, view = 5, blockIdByte = 1, epoch = periodIndex, cryptoV2 = cryptoV2)
    val voteB = signedVote(voterB, voterBIdx.toInt, view = 5, blockIdByte = 2, epoch = periodIndex, cryptoV2 = cryptoV2)
    coordinator.onVote(voteA)
    coordinator.onVote(voteB)

    coordinator.detectedEquivocations.size shouldBe 1
    val detectedProof = coordinator.detectedEquivocations.head
    detectedProof.voterIndex shouldBe voterBIdx.toInt
    fx.equivocations shouldBe Vector(detectedProof)

    val folded = Miner.foldHotStuffConflicts(
      slashingEnabled = true,
      pending = Seq(detectedProof),
      voting = None,
      forgeHeightPeriodIndex = periodIndex,
      alreadyExcluded = _ => false,
      fallbackFinalizedHeight = () => com.decentralchain.state.GenesisBlockHeight
    )
    folded shouldBe defined
    val foldedFv = folded.value
    foldedFv.hotstuffConflicts shouldBe Seq(detectedProof)

    val receivedFv = throughWire(foldedFv)
    receivedFv shouldBe foldedFv // same logical content -- only the byte hop differs

    (detectedProof, foldedFv, receivedFv)
  }

  "HotStuff equivocation evidence" - {

    "produces identical conflictGenerators exclusion via detection -> miner fold -> wire -> receiving-node validation, " +
      "and identical FinalizationState.append results on both sides" in
      withCommittedCommittee { (d, _) =>
        val (detectedProof, _, receivedFv) = detectFoldAndSend()
        val committee                      = committeeOf(generators)

        // --- Step 3: RECEIVING node -- never saw the votes, only `receivedFv`'s bytes. Appends a
        // block carrying it through the real appender pipeline (feature 29 active).
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = receivedFv.hotstuffConflicts))
        )
        d.appender.appendBlockWithoutFallback(block3) should beRight
        d.blockchain.conflictGenerators(d.blockchain.currentGenerationPeriod.value).all should contain(voterBIdx)

        // --- Step 4: both a "detecting" and a "receiving" FinalizationState.append, fed the SAME
        // FinalizationVoting content, produce IDENTICAL conflictGenerators sets.
        val detectingState = FinalizationState.notActivated(minerGenerator.toAddress)
        val receivingState = FinalizationState.notActivated(minerGenerator.toAddress)

        val votingForDetectingSide = mkFinalizationVoting().copy(hotstuffConflicts = Seq(detectedProof))
        val votingForReceivingSide = mkFinalizationVoting().copy(hotstuffConflicts = receivedFv.hotstuffConflicts)

        val (detectingAfter, _, _) = detectingState.append(block3.id(), Some(votingForDetectingSide), committee)
        val (receivingAfter, _, _) = receivingState.append(block3.id(), Some(votingForReceivingSide), committee)

        detectingAfter.conflictGenerators shouldBe receivingAfter.conflictGenerators
        detectingAfter.conflictGenerators should contain(voterBIdx)
      }

    "NEGATIVE: a flipped byte in voteB's serialized signature is rejected by the receiving node's " +
      "validateFinalizationVoting (whole block rejected)" in
      withCommittedCommittee { (d, _) =>
        val (detectedProof, _, _) = detectFoldAndSend()

        // Flip one byte of voteB's signature, THEN send through real wire bytes -- the receiving
        // node only ever sees the corrupted bytes, exactly like a bit-flip in transit or a forgery
        // attempt.
        val corruptedProof  = detectedProof.copy(voteB = detectedProof.voteB.copy(signature = flipOneByte(detectedProof.voteB.signature)))
        val corruptedFv     = FinalizationVoting(Seq.empty, com.decentralchain.state.GenesisBlockHeight, None, Seq.empty, Seq(corruptedProof))
        val corruptedWireFv = throughWire(corruptedFv)

        val block3b = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = corruptedWireFv.hotstuffConflicts))
        )
        val result = d.appender.appendBlockWithoutFallback(block3b)
        result.isLeft shouldBe true
        result.left.value.toString should include("signature invalid for voter")
      }

    // --- Task 9 step (d) / Task 8 review carry-over: the whole chain above runs entirely under the
    // legacy DST. Repeat the SAME chain-of-custody proof with BlsCryptoV2 activated: detecting
    // coordinator configured `cryptoV2 = () => true`, votes signed under
    // `HotStuffQuorum.voteDst(true)` (the `_HSVOTE_` v2 tag), and the receiving node's
    // `validateHotStuffEquivocationProofs` deriving the SAME v2 dst from the containing block's
    // height (BlsCryptoV2 pre-activated from height 1, so every height in this chain is v2-era).
    "under BlsCryptoV2 (v2 DST)" - {
      "produces identical conflictGenerators exclusion via detection -> miner fold -> wire -> receiving-node validation, " +
        "and identical FinalizationState.append results on both sides, all under the v2 DST" in
        withCommittedCommittee(withEvidenceFeatureV2) { (d, _) =>
          val (detectedProof, _, receivedFv) = detectFoldAndSend(cryptoV2 = true)
          val committee                      = committeeOf(generators)

          val block3 = d.createBlock(
            version = Block.ProtoBlockVersion,
            txs = Nil,
            generator = minerGenerator,
            strictTime = true,
            finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = receivedFv.hotstuffConflicts))
          )
          d.appender.appendBlockWithoutFallback(block3) should beRight
          d.blockchain.conflictGenerators(d.blockchain.currentGenerationPeriod.value).all should contain(voterBIdx)

          val detectingState = FinalizationState.notActivated(minerGenerator.toAddress)
          val receivingState = FinalizationState.notActivated(minerGenerator.toAddress)

          val votingForDetectingSide = mkFinalizationVoting().copy(hotstuffConflicts = Seq(detectedProof))
          val votingForReceivingSide = mkFinalizationVoting().copy(hotstuffConflicts = receivedFv.hotstuffConflicts)

          val (detectingAfter, _, _) = detectingState.append(block3.id(), Some(votingForDetectingSide), committee)
          val (receivingAfter, _, _) = receivingState.append(block3.id(), Some(votingForReceivingSide), committee)

          detectingAfter.conflictGenerators shouldBe receivingAfter.conflictGenerators
          detectingAfter.conflictGenerators should contain(voterBIdx)
        }

      "NEGATIVE: a legacy-DST-signed equivocation proof is rejected in a v2-activated chain" in
        withCommittedCommittee(withEvidenceFeatureV2) { (d, _) =>
          // Detect + fold under the LEGACY dst (cryptoV2 = false everywhere in detection), but the
          // RECEIVING chain has BlsCryptoV2 active from height 1 -- so `validateHotStuffEquivocationProofs`
          // derives dst = voteDst(true) for the containing block, and this legacy-signed proof's
          // signatures must fail that verification.
          val (_, legacyFoldedFv, _) = detectFoldAndSend(cryptoV2 = false)
          val legacyWireFv           = throughWire(legacyFoldedFv)

          val block3v2 = d.createBlock(
            version = Block.ProtoBlockVersion,
            txs = Nil,
            generator = minerGenerator,
            strictTime = true,
            finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = legacyWireFv.hotstuffConflicts))
          )
          val result = d.appender.appendBlockWithoutFallback(block3v2)
          result.isLeft shouldBe true
          result.left.value.toString should include("signature invalid for voter")
        }
    }
  }

  private def flipOneByte(bs: ByteStr): ByteStr = {
    val arr = bs.arr.clone()
    arr(0) = (arr(0) ^ 0x01).toByte
    ByteStr(arr)
  }
}
