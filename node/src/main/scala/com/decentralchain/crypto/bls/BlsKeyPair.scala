package com.decentralchain.crypto.bls

import com.decentralchain.account.PrivateKey as DccPrivateKey
import com.decentralchain.common.state.ByteStr
import supranational.blst

import java.util

sealed trait BlsKeyPair {
  def publicKey: BlsPublicKey

  def sign(message: Array[Byte]): BlsSignature
}

object BlsKeyPair {
  def apply(dccPrivateKey: DccPrivateKey): BlsKeyPair = new BlsSeedKeyPair(dccPrivateKey.arr)
}

private final class BlsSeedKeyPair(private val wavesPrivateKey: Array[Byte]) extends BlsKeyPair {
  private lazy val sk: blst.SecretKey = BlsUtils.mkSecretKey(wavesPrivateKey)
  lazy val publicKey: BlsPublicKey    = BlsPublicKey.unchecked(ByteStr(BlsUtils.mkPublicKey(sk)))

  def sign(message: Array[Byte]): BlsSignature = BlsSignature.unsafe(ByteStr(BlsUtils.signBasic(sk, message)))

  override def equals(other: Any): Boolean = other match {
    case other: BlsSeedKeyPair => util.Arrays.equals(other.dccPrivateKey, dccPrivateKey)
    case _                     => false
  }

  private lazy val hc          = util.Arrays.hashCode(dccPrivateKey)
  override def hashCode(): Int = hc
}
