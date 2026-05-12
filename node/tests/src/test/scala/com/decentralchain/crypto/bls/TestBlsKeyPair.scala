package com.decentralchain.crypto.bls

object TestBlsKeyPair {
  def unsafe(wavesPrivateKey: Array[Byte]): BlsKeyPair = new BlsSeedKeyPair(wavesPrivateKey)
}
