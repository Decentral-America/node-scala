package com.decentralchain.state.appender

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.{HotStuffEquivocationProof, HotStuffQuorum}
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsUtils}
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.finalization.BaseFinalizationSpec
import com.decentralchain.network.HotStuffVote
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.test.DomainPresets
import com.decentralchain.test.DomainPresets.DCCSettingsOps
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

/** Task 4: deterministic proof validation wired into `validateFinalizationVoting`.
  *
  * Tests go through the public appender pipeline (`d.appender.appendBlockWithoutFallback`) the same way the existing
  * appender/finalization suites test `validateFinalizationVoting` (see
  * `ConflictEndorserBlocksBasicSuite` / `MultipleConflictEndorserSuite`), so both the proof rules
  * AND their wiring (feature gate, emptiness relaxation, result-set exclusion) are exercised for
  * real -- no blockchain stub/mock.
  */
class HotStuffEquivocationValidationSpecification extends BaseFinalizationSpec {
  private val minerGenerator = TxHelpers.signer(0)
  private val voterA         = TxHelpers.signer(1)
  private val voterB         = TxHelpers.signer(2)
  private val voterC         = TxHelpers.signer(3)

  private val voterAIdx = GeneratorIndex(1)
  private val voterBIdx = GeneratorIndex(2)
  private val voterCIdx = GeneratorIndex(3)

  private val generators = Seq(minerGenerator, voterA, voterB, voterC)

  private val withEvidenceFeature =
    DomainPresets.DeterministicFinality
      .addFeatures(BlockchainFeatures.SmallerMinimalGeneratingBalance)
      .configure(_.copy(generationPeriodLength = 2, lightNodeBlockFieldsAbsenceInterval = 0))

  private val prepare = HotStuffPhase.HOTSTUFF_PHASE_PREPARE

  private def signedVote(signer: KeyPair, voterIndex: Int, view: Int, blockIdByte: Byte, epoch: Int): HotStuffVote = {
    val blockId = ByteStr(Array.fill(32)(blockIdByte))
    val height  = Height(10)
    val msg     = HotStuffQuorum.voteMessage(view, prepare, blockId, height.toInt, epoch)
    val kp      = BlsKeyPair(signer.privateKey)
    HotStuffVote(view, prepare, blockId, height, voterIndex, ByteStr(kp.sign(msg, BlsUtils.BlsDomainSeparationTag).arr), epoch)
  }

  private def proofFor(signer: KeyPair, voterIndex: Int, epoch: Int, view: Int = 5): HotStuffEquivocationProof =
    HotStuffEquivocationProof(
      signedVote(signer, voterIndex, view, 1, epoch),
      signedVote(signer, voterIndex, view, 2, epoch)
    )

  /** Commits `generators`, so at the period covering block height 3 they form the committee at
    * indexes matching `voterAIdx`/`voterBIdx`/`voterCIdx` above (miner is index 0). Returns the
    * domain positioned right after block 2 (commitments), ready to append block 3 carrying the FV
    * under test. Block 3's generation period has `index == 1` (empirically: with
    * `generationPeriodLength = 2` and `DeterministicFinality` activation at height 0, block height 3
    * falls in the SECOND period, index 1 -- see `GenerationPeriod.index`).
    */
  private def withCommittedCommittee(settings: com.decentralchain.settings.DCCSettings)(
      test: (com.decentralchain.history.Domain, Block) => Unit
  ): Unit =
    withDomain(settings, AddrWithBalance.enoughBalances(generators*)) { d =>
      val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
      val block2WithCommitments = d.createBlock(version = Block.ProtoBlockVersion, txs = txs, generator = minerGenerator, strictTime = true)
      d.appender.appendBlock(block2WithCommitments)
      test(d, block2WithCommitments)
    }

  private val periodIndex = 1

  "validateHotStuffEquivocationProofs (via validateFinalizationVoting)" - {

    "1. valid proof (same epoch as block period, real signatures, fresh voter) => Right, excludes the voter" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof = proofFor(voterA, voterAIdx.toInt, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        d.appender.appendBlockWithoutFallback(block3) should beRight
        // Task 5 [C1]: before the liquid/persisted conflictGenerators union fix, this assertion was
        // inverted (`should not contain voterAIdx`) -- the proof validated fine but the exclusion never
        // actually reached `conflictGenerators`, and this test happened to pass against that bug. It
        // must contain the voter -- that is the whole point of a "verified equivocation proof".
        d.blockchain.conflictGenerators(d.blockchain.currentGenerationPeriod.value).all should contain(voterAIdx)
      }

    "3. proofs-only FV (valid=[], conflict=[], one valid proof), post-activation => Right" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof = proofFor(voterA, voterAIdx.toInt, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        d.appender.appendBlockWithoutFallback(block3) should beRight
      }

    "4. proof whose committeeEpoch != block period index => Left" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof = proofFor(voterA, voterAIdx.toInt, periodIndex + 1)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("does not match block generation period")
      }

    "4b. proof whose voterIndex is negative (-1) => Left, no exception (GeneratorIndex.apply guard at conflictingEndorsers)" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof = proofFor(voterA, -1, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("outside committee")
      }

    "5. cross-epoch vote pair (voteA.epoch != voteB.epoch) => Left (consistent fails)" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val badProof = HotStuffEquivocationProof(
          signedVote(voterA, voterAIdx.toInt, 5, 1, periodIndex),
          signedVote(voterA, voterAIdx.toInt, 5, 2, periodIndex + 1)
        )
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(badProof)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("proof votes span committee epochs")
      }

    "6. forged voteB signature => Left (signaturesValid fails)" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val good   = proofFor(voterA, voterAIdx.toInt, periodIndex)
        val forged = good.copy(voteB = good.voteB.copy(signature = ByteStr(Array.fill(96)(7: Byte))))
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(forged)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("signature invalid for voter")
      }

    "7. voter index out of committee bounds => Left" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof = proofFor(voterA, 999, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("outside committee")
      }

    "8. duplicate voter across two proofs in one FV => Left" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val proof1 = proofFor(voterA, voterAIdx.toInt, periodIndex, view = 5)
        val proof2 = proofFor(voterA, voterAIdx.toInt, periodIndex, view = 6)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof1, proof2)))
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("Duplicate equivocation-proof voter indexes")
      }

    "9. voter already in knownConflictGenerators => Left (already excluded)" in
      withCommittedCommittee(withEvidenceFeature) { (d, block2) =>
        // First exclude voterB via a regular conflicting endorsement.
        val blockWithConflict = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().withConflict(voterB, voterBIdx, block2.id()))
        )
        d.appender.appendBlockWithoutFallback(blockWithConflict) should beRight

        val proof = proofFor(voterB, voterBIdx.toInt, periodIndex)
        val nextBlock = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        val result = d.appender.appendBlockWithoutFallback(nextBlock)
        result.isLeft shouldBe true
        result.left.value.toString should include("already excluded")
      }

    "10. voter also present in fv.conflict's endorser indexes (same block) => Left" in
      withCommittedCommittee(withEvidenceFeature) { (d, block2) =>
        val proof = proofFor(voterC, voterCIdx.toInt, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(
            mkFinalizationVoting()
              .withConflict(voterC, voterCIdx, block2.id())
              .copy(hotstuffConflicts = Seq(proof))
          )
        )
        val result = d.appender.appendBlockWithoutFallback(block3)
        result.isLeft shouldBe true
        result.left.value.toString should include("already carries a conflicting endorsement in this voting")
      }

    "11. proof from the MINER's own index => Right (an equivocating leader IS slashable)" in
      withCommittedCommittee(withEvidenceFeature) { (d, _) =>
        val minerIdx = GeneratorIndex(0)
        val proof    = proofFor(minerGenerator, minerIdx.toInt, periodIndex)
        val block3 = d.createBlock(
          version = Block.ProtoBlockVersion,
          txs = Nil,
          generator = minerGenerator,
          strictTime = true,
          finalizationVoting = Some(mkFinalizationVoting().copy(hotstuffConflicts = Seq(proof)))
        )
        d.appender.appendBlockWithoutFallback(block3) should beRight
      }
  }
}
