package com.decentralchain.crypto.bls

object TestBlsKeyPair {
  def unsafe(dccPrivateKey: Array[Byte]): BlsKeyPair = new BlsSeedKeyPair(dccPrivateKey)
}
