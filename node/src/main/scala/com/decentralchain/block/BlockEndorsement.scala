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
  /** Verifies this endorsement's signature. Tries the current `_ENDORSE_` DST first; if that fails,
    * falls back to the legacy `_NUL_` DST (message layout is unchanged either way -- only the DST
    * ever differed, see `BlockEndorsement.legacyFallbackVerify`'s doc) so historical, already-on-chain
    * endorsements signed before the per-context-DST switch still verify. New signing
    * (`BlockEndorsement.sign`/`signed`) stays v2-only.
    */
  def signatureValid(endorserPublicKey: BlsPublicKey): Either[String, Unit] =
    BlockEndorsement.verify(signature.arr, mkMessageBytes, endorserPublicKey.arr)

  private def mkMessageBytes: Array[Byte] = BlockEndorsement.mkMessage(finalizedId, finalizedHeight, endorsedId)
}

object BlockEndorsement {
  val Dst: String = BlsUtils.BlsEndorseDomainSeparationTag

  def signed(
      endorserAccount: BlsKeyPair,
      endorserIndex: GeneratorIndex,
      finalizedId: BlockId,
      finalizedHeight: Height,
      endorsedId: BlockId
  ): BlockEndorsement =
    BlockEndorsement(
      endorserIndex,
      finalizedId,
      finalizedHeight,
      endorsedId,
      sign(endorserAccount, finalizedId, finalizedHeight, endorsedId)
    )

  def sign(kp: BlsKeyPair, finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId): BlsSignature =
    kp.sign(mkMessage(finalizedId, finalizedHeight, endorsedId), Dst)

  def mkMessage(finalizedId: BlockId, finalizedHeight: Height, endorsedId: BlockId): Array[Byte] =
    finalizedId.arr ++ finalizedHeight.toByteArray ++ endorsedId.arr

  /** Verify-only bimodal check for a single endorsement signature: current `_ENDORSE_` DST first,
    * then the legacy `_NUL_` DST as a fallback (audit follow-up to the PoP fix -- commit `448d56557f`
    * made the identical unconditional-delete change here, deleting the legacy fallback that
    * `BlockEndorsement.dst(cryptoV2: Boolean)` used to provide, on the same now-falsified premise
    * that no chain this codebase supports carries bytes signed under it; `mkMessage`'s layout was
    * NEVER changed by that commit -- only the DST selection was, so the legacy branch here reuses
    * the exact same `message` bytes, just under the old domain). Never used for new signing.
    */
  def verify(signatureBytes: Array[Byte], message: Array[Byte], endorserPublicKeyBytes: Array[Byte]): Either[String, Unit] =
    BlsUtils.verifyBasic(signatureBytes, message, endorserPublicKeyBytes, Dst) match {
      case Right(())   => Right(())
      case Left(_)     => BlsUtils.verifyBasic(signatureBytes, message, endorserPublicKeyBytes, BlsUtils.BlsLegacyDomainSeparationTag)
    }

  /** Verify-only bimodal check for an AGGREGATED endorsement signature (multiple endorsers, one
    * signature) -- same v2-then-legacy fallback as [[verify]], via `BlsUtils.verifyAgg` instead of
    * `verifyBasic`. Used at the block-validation aggregate-endorsement site
    * (`state/appender/package.scala`'s `validateFinalizationVoting`), which mirrors `EndorsementStorage`'s
    * pairwise checks but re-verifies the whole aggregate unconditionally on replay.
    */
  def verifyAgg(aggSignatureBytes: Array[Byte], message: Array[Byte], endorserPublicKeys: Iterable[Array[Byte]]): Either[String, Unit] =
    BlsUtils.verifyAgg(aggSignatureBytes, message, endorserPublicKeys, Dst) match {
      case Right(())   => Right(())
      case Left(_)     => BlsUtils.verifyAgg(aggSignatureBytes, message, endorserPublicKeys, BlsUtils.BlsLegacyDomainSeparationTag)
    }
}
