package com.decentralchain.crypto.bls

import com.decentralchain.common.state.ByteStr
import com.decentralchain.transaction.TxValidationError.GenericError

opaque type BlsSignature = ByteStr

object BlsSignature {
  val SizeInBytes = BlsUtils.SignatureSizeInBytes

  extension (self: BlsSignature) {
    def byteStr: ByteStr = self
    def arr: Array[Byte] = byteStr.arr
    def base58: String   = byteStr.toString

    def verifyBasic(message: Array[Byte], blsPk: BlsPublicKey): Either[String, Unit] =
      BlsUtils.verifyBasic(byteStr.arr, message, blsPk.arr)

    def verifyAgg(message: Array[Byte], blsPks: Iterable[BlsPublicKey]): Either[String, Unit] =
      BlsUtils.verifyAgg(byteStr.arr, message, blsPks.map(_.arr))

    def append(other: BlsSignature): Either[GenericError, BlsSignature] =
      BlsUtils.aggSign(self.arr, other.arr).left.map(GenericError(_)).flatMap(apply)
  }

  // Validates (via apply's sanity check) rather than blindly wrapping bytes.
  private[bls] def unsafe(byteStr: ByteStr): BlsSignature = apply(byteStr) match {
    case Left(e)  => throw new IllegalArgumentException(e.err)
    case Right(r) => r
  }

  def apply(arr: Array[Byte]): Either[GenericError, BlsSignature] = apply(ByteStr(arr))
  def apply(byteStr: ByteStr): Either[GenericError, BlsSignature] = BlsUtils.sanityCheckSignature(byteStr.arr) match {
    case Right(_)  => Right(byteStr)
    case Left(err) => Left(GenericError(err))
  }

  /** Single-pass aggregation of a whole chosen-valid endorser set, replacing a one-at-a-time
    * `aggregatedEndorsement.fold(signature)(_.append(signature))` fold which could not fail.
    */
  def agg(xs: Iterable[BlsSignature]): Either[GenericError, BlsSignature] =
    BlsUtils.aggSig(xs.map(_.arr)).left.map(GenericError(_)).flatMap(apply)
}
