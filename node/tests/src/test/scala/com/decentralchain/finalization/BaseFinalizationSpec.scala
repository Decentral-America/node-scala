package com.decentralchain.finalization

import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.{BlockEndorsement, FinalizationVoting}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature}
import com.decentralchain.db.WithDomain
import com.decentralchain.history.Domain
import com.decentralchain.state.{BalanceSnapshot, ConflictGenerators, GeneratorIndex, GenesisBlockHeight, Height}
import com.decentralchain.test.{FreeSpec, WithResourceManager}
import com.decentralchain.transaction.{CommitToGenerationTransaction, TxHelpers}
import org.scalactic.source.Position
import org.scalatest.EitherValues

trait BaseFinalizationSpec extends FreeSpec, WithDomain, WithResourceManager, EitherValues {
  protected def mkConflictGenerators(h: Int, idxs: Int*): ConflictGenerators =
    ConflictGenerators.empty.appendAll(Height(h), GeneratorIndex.seq(idxs)*)

  protected def mkFinalizationVoting(
      valid: Seq[GeneratorIndex] = Nil,
      finalizedHeight: Height = GenesisBlockHeight,
      conflict: Seq[BlockEndorsement] = Nil
  ): FinalizationVoting = FinalizationVoting(valid, finalizedHeight, aggregatedEndorsement = None, conflict)

  protected def mkConflictEndorsement(
      dccAcc: KeyPair,
      idx: GeneratorIndex,
      endorsedId: BlockId,
      finalizedHeight: Height = GenesisBlockHeight,
      finalizedId: BlockId = TxHelpers.randomBlockId
  ): BlockEndorsement = BlockEndorsement.signed(
    BlsKeyPair(dccAcc.privateKey),
    idx,
    finalizedId,
    finalizedHeight = finalizedHeight,
    endorsedId = endorsedId
  )

  protected def bs(height: Int, regularBalance: Long, deposits: Int = 0): BalanceSnapshot =
    BalanceSnapshot(Height(height), regularBalance, 0L, 0L, CommitToGenerationTransaction.DepositInDcclets * deposits)

  extension (self: FinalizationVoting) {
    def withConflict(
        dccAcc: KeyPair,
        idx: GeneratorIndex,
        endorsedId: BlockId,
        finalizedHeight: Height = GenesisBlockHeight,
        finalizedId: BlockId = TxHelpers.randomBlockId
    ): FinalizationVoting =
      self.copy(conflict = self.conflict :+ mkConflictEndorsement(dccAcc, idx, endorsedId, finalizedHeight, finalizedId))

    def signed(endorsedId: BlockId, finalizedId: BlockId, validEndorsers: KeyPair*): FinalizationVoting = {
      val aggSig = validEndorsers
        .map { kp =>
          BlockEndorsement.sign(
            BlsKeyPair(kp.privateKey),
            finalizedId = finalizedId,
            finalizedHeight = GenesisBlockHeight,
            endorsedId = endorsedId
          )
        }
        .foldLeft(Option.empty[BlsSignature]) {
          case (None, s)    => Some(s)
          case (Some(r), s) => Some(r.append(s).explicitGet())
        }

      self.copy(aggregatedEndorsement = aggSig)
    }
  }

  extension (d: Domain)(using Position) {
    def finalizedHeightIsEmpty(): Domain = withClue("finalizedHeightIsEmpty: ") {
      d.blockchain.finalizedHeight shouldBe empty
      d
    }

    def finalizedHeightIs(h: Int): Domain = withClue("finalizedHeightIs: ") {
      d.blockchain.finalizedHeight.value.toInt shouldBe h
      d
    }

    def finalizedHeightAtPrevIsEmpty(): Domain = withClue("finalizedHeightAtIsEmpty: ") {
      val prevHeight = Height(d.blockchain.height - 1)
      if (prevHeight >= GenesisBlockHeight) d.blockchain.finalizedHeightAt(prevHeight) shouldBe empty
      d
    }

    def finalizedHeightAtPrevIs(h: Int): Domain = withClue("finalizedHeightAtIs: ") {
      val prevHeight = Height(d.blockchain.height - 1)
      d.blockchain.finalizedHeightAt(prevHeight).value.toInt shouldBe h
      d
    }

    def allFinalizedHeightIs(h: Int): Domain = d
      .finalizedHeightIs(h)
      .finalizedHeightAtPrevIs(h)
  }
}
