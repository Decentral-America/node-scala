package com.decentralchain.crypto.bls

import com.decentralchain.common.state.ByteStr
import com.decentralchain.transaction.TxValidationError.GenericError

opaque type BlsPublicKey = ByteStr

object BlsPublicKey {
  val SizeInBytes = BlsUtils.PublicKeySizeInBytes

  extension (self: BlsPublicKey) {
    def byteStr: ByteStr = self
    def arr: Array[Byte] = byteStr.arr

    def verify(message: Array[Byte], signature: BlsSignature): Boolean = BlsUtils.verifyBasic(signature.arr, message, arr).isRight

    def base58: String = byteStr.toString

    /** Full curve validation (in-group, not point-at-infinity). We need this once when adding a new
      * endorser -- not on every deserialization, see [[apply]].
      */
    def validated: Either[String, Unit] = BlsUtils.validatePublicKey(arr)
  }

  private[bls] def unsafe(byteStr: ByteStr): BlsPublicKey = byteStr

  def apply(arr: Array[Byte]): Either[GenericError, BlsPublicKey] = apply(ByteStr(arr))
  def apply(byteStr: ByteStr): Either[GenericError, BlsPublicKey] = BlsUtils.sanityCheckPublicKey(byteStr.arr) match {
    case Right(_)  => Right(byteStr)
    case Left(err) => Left(GenericError(err))
  }
}
