package com.decentralchain.block

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey, BlsSignature, BlsUtils}
import com.decentralchain.state.{GeneratorIndex, Height}

case class BlockEndorsement(
    endorserIndex: GeneratorIndex,
    finalizedId: BlockId,
    finalizedHeight: Height,
    endorsedId: BlockId,
    signature: BlsSignature
) {
  /** `dst` is DELIBERATELY not defaulted (audit H2, task 6): the caller must name the era the
    * endorsement is being verified under. On-chain callers derive it from the containing block's
    * height (`blockchain.supportsBlsCryptoV2(blockHeight)`), never a bare tip read -- see
    * `state/appender/package.scala`'s `validateFinalizationVoting`/`validateConflictingEndorsement`.
    */
  def signatureValid(endorserPublicKey: BlsPublicKey, dst: String): Either[String, Unit] =
    signature.verifyBasic(BlockEndorsement.mkMessage(finalizedId, finalizedHeight, endorsedId), endorserPublicKey, dst)
}

object BlockEndorsement {
  /** Era selector for endorsement signing/verification (task 6). Mirrors the PoP/aggregated-endorsement
    * split from task 5: legacy `_NUL_` tag pre-activation, dedicated `_ENDORSE_` tag post-activation.
    */
  def dst(cryptoV2: Boolean): String = if (cryptoV2) BlsUtils.BlsEndorseDomainSeparationTagV2 else BlsUtils.BlsDomainSeparationTag

  def signed(
      endorserAccount: BlsKeyPair,
      endorserIndex: GeneratorIndex,
      finalizedId: BlockId,
      finalizedHeight: Height,
      endorsedId: BlockId,
      cryptoV2: Boolean
  ): BlockEndorsement =
    BlockEndorsement(
      endorserIndex,
      finalizedId,
      finalizedHeight,
      endorsedId,
      sign(endorserAccount, finalizedId, finalizedHeight, endorsedId, cryptoV2)
    )

  def sign(kp: BlsKeyPair, finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId, cryptoV2: Boolean): BlsSignature =
    kp.sign(mkMessage(finalizedId, finalizedHeight, endorsedId), dst(cryptoV2))

  def mkMessage(finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId): Array[Byte] =
    finalizedId.arr ++ finalizedHeight.toByteArray ++ endorsedId.arr
}
