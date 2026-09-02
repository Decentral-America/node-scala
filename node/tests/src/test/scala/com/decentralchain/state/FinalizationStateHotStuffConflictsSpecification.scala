package com.decentralchain.state

import com.decentralchain.block.FinalizationVoting
import com.decentralchain.common.state.ByteStr
import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.crypto.bls.BlsKeyPair
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.network.HotStuffVote
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.TxHelpers
import io.decentralchain.protobuf.block.HotStuffPhase

/** Task 5 [C1]: verified equivocation-proof voters must feed `conflictGenerators` at the liquid
  * layer (`FinalizationState.append`), the same way `fv.conflict`'s endorser indexes already do.
  * `.append` never re-verifies proof signatures (Task 4's appender-layer validation already
  * guarantees any proof reaching here has a non-negative, in-committee `voterIndex`) -- so these
  * proofs carry dummy signature bytes; only the derived `voterIndex` matters here.
  */
class FinalizationStateHotStuffConflictsSpecification extends FreeSpec {
  private val minerSigner = TxHelpers.signer(0)
  private val miner       = minerSigner.toAddress

  private def mkGenerator(i: Int, balance: Long): GeneratorInfo = {
    val kp = TxHelpers.signer(i)
    GeneratorInfo(GeneratorIndex(i), kp.toAddress, BlsKeyPair(kp.privateKey).publicKey, balance)
  }

  private def dummyVote(voterIndex: Int, blockIdByte: Byte): HotStuffVote =
    HotStuffVote(
      view = 1,
      phase = HotStuffPhase.HOTSTUFF_PHASE_PREPARE,
      blockId = ByteStr(Array.fill(32)(blockIdByte)),
      blockHeight = Height(10),
      voterIndex = voterIndex,
      signature = ByteStr(Array.fill(96)(0: Byte))
    )

  private def proofFor(voterIndex: Int): HotStuffEquivocationProof =
    HotStuffEquivocationProof(dummyVote(voterIndex, 1), dummyVote(voterIndex, 2))

  private def mkFv(
      conflict: Seq[com.decentralchain.block.BlockEndorsement] = Nil,
      hotstuffConflicts: Seq[HotStuffEquivocationProof] = Nil
  ): FinalizationVoting =
    FinalizationVoting(valid = Nil, finalizedHeight = GenesisBlockHeight, aggregatedEndorsement = None, conflict = conflict, hotstuffConflicts = hotstuffConflicts)

  private def mkConflictEndorsement(voterIndex: GeneratorIndex): com.decentralchain.block.BlockEndorsement = {
    val kp = TxHelpers.signer(voterIndex.toInt)
    com.decentralchain.block.BlockEndorsement.signed(
      BlsKeyPair(kp.privateKey),
      voterIndex,
      TxHelpers.randomBlockId,
      finalizedHeight = GenesisBlockHeight,
      endorsedId = TxHelpers.randomBlockId,
      cryptoV2 = false
    )
  }

  "FinalizationState.append" - {
    "1. a verified hotstuff equivocation proof for voter 2 lands in conflictGenerators" in {
      val generatorSet = Seq(mkGenerator(0, 1000L), mkGenerator(1, 1000L), mkGenerator(2, 1000L))
      val state         = FinalizationState.notActivated(miner).copy(generatorSet = generatorSet)
      val fv            = mkFv(hotstuffConflicts = Seq(proofFor(2)))

      val (updatedState, _, _) = state.append(TxHelpers.randomBlockId, Some(fv), generatorSet)

      updatedState.conflictGenerators should contain(GeneratorIndex(2))
    }

    "2. voter 3's balance is excluded from isParentFinalized's stake denominator, flipping the outcome" in {
      // Four generators: miner(idx0)=1, voter1(idx1)=1, voter2(idx2)=1, voter3(idx3)=3, total=6.
      // Miner + voter1 endorse => endorsed=2. Without exclusion: 2/6 = 33% < 2/3 => NOT finalized.
      // With voter3 (a silent, non-endorsing generator) excluded via a hotstuff equivocation proof,
      // the denominator drops to the 3 remaining generators' balance (3): 2/3 exactly => finalized.
      // This isolates the denominator effect from the numerator: voter3 never endorses either way.
      val generatorSet = Seq(mkGenerator(0, 1L), mkGenerator(1, 1L), mkGenerator(2, 1L), mkGenerator(3, 3L))
      val state         = FinalizationState.notActivated(miner).copy(generatorSet = generatorSet)

      val fvWithoutProof = mkFv().copy(valid = Seq(GeneratorIndex(1)))
      val (withoutProofState, _, _) = state.append(TxHelpers.randomBlockId, Some(fvWithoutProof), generatorSet)
      withoutProofState.parentFinalized shouldBe false

      val fvWithProof = mkFv(hotstuffConflicts = Seq(proofFor(3))).copy(valid = Seq(GeneratorIndex(1)))
      val (withProofState, _, _) = state.append(TxHelpers.randomBlockId, Some(fvWithProof), generatorSet)
      withProofState.parentFinalized shouldBe true
    }

    "3. composition: a T0 conflict (voter 1) and a hotstuff proof (voter 2) both land in conflictGenerators" in {
      val generatorSet = Seq(mkGenerator(0, 1000L), mkGenerator(1, 1000L), mkGenerator(2, 1000L))
      val state         = FinalizationState.notActivated(miner).copy(generatorSet = generatorSet)
      val fv = mkFv(
        conflict = Seq(mkConflictEndorsement(GeneratorIndex(1))),
        hotstuffConflicts = Seq(proofFor(2))
      )

      val (updatedState, _, _) = state.append(TxHelpers.randomBlockId, Some(fv), generatorSet)

      updatedState.conflictGenerators should contain allOf (GeneratorIndex(1), GeneratorIndex(2))
    }

    "4. a proofs-only voting (no T0 conflict) still excludes the proof voter from THIS append's isParentFinalized denominator" in {
      // Append-path regression, incl. the proofs-only short-circuit on the `.filterNot` guard at
      // the top of `append` (a proofs-only fv must NOT be filtered out just because it carries no
      // `conflict` entries). This does NOT isolate isParentFinalized's internal re-derivation of
      // allConflictIndexes (FinalizationState.scala:106) from append's own `knownConflict`
      // plumbing: `append` always folds `newFinalizationVoting`'s hotstuffConflicts into
      // `newConflictGenerators` before calling `isParentFinalized`, via `updatedGeneratorSet`'s
      // caller-supplied `conflictGenerators` argument, so the exclusion below is guaranteed twice
      // over -- reverting line 106 alone would NOT turn this test red. See case 5 for the
      // FinalizationState.init path, where line 106 is the only source of the exclusion.
      // Four generators: miner(idx0)=1, voter1(idx1)=1, voter2(idx2)=1, voter3(idx3)=3, total=6.
      // Miner + voter1 endorse => endorsed=2. Without exclusion: 2/6 = 33% < 2/3 => NOT finalized.
      // With voter3 excluded via a proofs-only hotstuff equivocation proof (no T0 conflict at all),
      // the denominator drops to 3: 2/3 exactly => finalized, in this same append.
      val generatorSet = Seq(mkGenerator(0, 1L), mkGenerator(1, 1L), mkGenerator(2, 1L), mkGenerator(3, 3L))
      val state         = FinalizationState.notActivated(miner).copy(generatorSet = generatorSet)

      val fvProofsOnly = mkFv(hotstuffConflicts = Seq(proofFor(3))).copy(valid = Seq(GeneratorIndex(1)))
      val (updatedState, _, _) = state.append(TxHelpers.randomBlockId, Some(fvProofsOnly), generatorSet)

      updatedState.parentFinalized shouldBe true
    }
  }

  "FinalizationState.init" - {
    "5. a proofs-only base block excludes the proof voter from isParentFinalized's denominator with an empty knownConflict arg" in {
      // Isolates isParentFinalized's own internal re-derivation of allConflictIndexes
      // (FinalizationState.scala:106, `knownConflict ++ voting.fold(...)(_.allConflictGeneratorIndexes...)`)
      // from `init`'s caller-supplied `conflictGenerators` arg. On the real `init` call path
      // (BlockchainUpdaterImpl), `conflictGenerators` comes from persisted prior state and never
      // contains the base key block's own proofs -- so we pass it EMPTY here, matching production.
      // If line 106 is reverted to `_.conflict.view.map(_.endorserIndex)` (dropping the
      // `hotstuffConflicts` term), voter3's balance would no longer be excluded and this test fails.
      //
      // Same boundary-quorum fixture as case 2/4: miner(idx0)=1, voter1(idx1)=1, voter2(idx2)=1,
      // voter3(idx3)=3, total=6. Miner + voter1 endorse => endorsed=2. Without exclusion:
      // 2/6 = 33% < 2/3 => NOT finalized. With voter3 excluded via a proofs-only hotstuff
      // equivocation proof (no T0 conflict at all), the denominator drops to 3: 2/3 exactly =>
      // finalized.
      val generatorSet = Seq(mkGenerator(0, 1L), mkGenerator(1, 1L), mkGenerator(2, 1L), mkGenerator(3, 3L))

      def baseBlockWith(fv: FinalizationVoting) = {
        val b = TestBlock.create(minerSigner, Seq.empty).block
        b.copy(header = b.header.copy(finalizationVoting = Some(fv)))
      }

      val fvProofsOnly = mkFv(hotstuffConflicts = Seq(proofFor(3))).copy(valid = Seq(GeneratorIndex(1)))
      val withProofState =
        FinalizationState.init(generatorSet, conflictGenerators = Set.empty, base = baseBlockWith(fvProofsOnly))
      withProofState.parentFinalized shouldBe true

      val fvWithoutProof = mkFv().copy(valid = Seq(GeneratorIndex(1)))
      val withoutProofState =
        FinalizationState.init(generatorSet, conflictGenerators = Set.empty, base = baseBlockWith(fvWithoutProof))
      withoutProofState.parentFinalized shouldBe false
    }
  }
}
