package com.decentralchain.crypto.bls

import com.decentralchain.account.PrivateKey as DccPrivateKey
import com.decentralchain.common.state.ByteStr
import supranational.blst

import java.util

sealed trait BlsKeyPair {
  def publicKey: BlsPublicKey

  def sign(message: Array[Byte]): BlsSignature
  def verify(message: Array[Byte], signature: BlsSignature): Boolean = publicKey.verify(message, signature)
}

object BlsKeyPair {
  def apply(dccPrivateKey: DccPrivateKey): BlsKeyPair = new BlsSeedKeyPair(dccPrivateKey.arr)

  /** Test-only escape hatch: builds a `BlsKeyPair` directly from an already-derived `blst.SecretKey`,
    * bypassing `BlsUtils.mkBlsSecretKey`'s seed validation (audit M3) entirely. Needed so tests can
    * still construct a deliberately degenerate key (e.g. the zero secret key) to exercise the
    * on-chain defenses against it, independent of whether `mkBlsSecretKey`'s own guard would also
    * catch it.
    */
  private[bls] def unsafeFromSecretKey(sk: blst.SecretKey): BlsKeyPair = new BlsKeyPair {
    val publicKey: BlsPublicKey                   = BlsPublicKey.unsafe(ByteStr(BlsUtils.mkBlsPublicKey(sk)))
    def sign(message: Array[Byte]): BlsSignature = BlsSignature.unsafe(ByteStr(BlsUtils.signBasic(sk, message)))
  }
}

private final class BlsSeedKeyPair(private val dccPrivateKey: Array[Byte]) extends BlsKeyPair {
  private lazy val sk: blst.SecretKey = BlsUtils.mkBlsSecretKey(dccPrivateKey)
  lazy val publicKey: BlsPublicKey    = BlsPublicKey.unsafe(ByteStr(BlsUtils.mkBlsPublicKey(sk)))

  def sign(message: Array[Byte]): BlsSignature = BlsSignature.unsafe(ByteStr(BlsUtils.signBasic(sk, message)))

  override def equals(other: Any): Boolean = other match {
    case other: BlsSeedKeyPair => util.Arrays.equals(other.dccPrivateKey, dccPrivateKey)
    case _                     => false
  }

  private lazy val hc          = util.Arrays.hashCode(dccPrivateKey)
  override def hashCode(): Int = hc
}
