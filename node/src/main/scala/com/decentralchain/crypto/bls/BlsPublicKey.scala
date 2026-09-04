package com.decentralchain.crypto.bls

import com.decentralchain.common.state.ByteStr
import com.decentralchain.transaction.TxValidationError.GenericError

opaque type BlsPublicKey = ByteStr

object BlsPublicKey {
  val SizeInBytes = BlsUtils.PublicKeySizeInBytes

  extension (self: BlsPublicKey) {
    def byteStr: ByteStr = self
    def arr: Array[Byte] = byteStr.arr

    def verify(message: Array[Byte], signature: BlsSignature, dst: String): Boolean =
      BlsUtils.verifyBasic(signature.arr, message, arr, dst).isRight

    def base58: String = byteStr.toString

    /** Full curve validation (in-group, not point-at-infinity). We need this once when adding a new
      * endorser -- not on every deserialization, see [[apply]].
      */
    def validated: Either[String, Unit] = BlsUtils.validatePublicKey(arr)
  }

  // Validates (via apply's sanity check) rather than blindly wrapping bytes -- symmetric with
  // BlsSignature.unsafe (audit L2: this used to be a bare cast with no check at all, unlike its
  // signature counterpart). Both production callers (BlsKeyPair.publicKey) pass a freshly-derived
  // BlsUtils.mkBlsPublicKey(sk) compression, which is always exactly SizeInBytes, so this cannot
  // regress a legitimate caller -- it only turns a would-be-silent bad-length bug into a thrown
  // IllegalArgumentException at the point of construction instead of at first use downstream.
  private[bls] def unsafe(byteStr: ByteStr): BlsPublicKey = apply(byteStr) match {
    case Left(e)  => throw new IllegalArgumentException(e.err)
    case Right(r) => r
  }

  def apply(arr: Array[Byte]): Either[GenericError, BlsPublicKey] = apply(ByteStr(arr))
  def apply(byteStr: ByteStr): Either[GenericError, BlsPublicKey] = BlsUtils.sanityCheckPublicKey(byteStr.arr) match {
    case Right(_)  => Right(byteStr)
    case Left(err) => Left(GenericError(err))
  }
}
