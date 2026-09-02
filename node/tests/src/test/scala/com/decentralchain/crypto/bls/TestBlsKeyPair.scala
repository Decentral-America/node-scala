package com.decentralchain.crypto.bls

import supranational.blst

object TestBlsKeyPair {
  def unsafe(dccPrivateKey: Array[Byte]): BlsKeyPair = new BlsSeedKeyPair(dccPrivateKey)

  /** A BLS key pair whose secret scalar is degenerate (zero), and whose public key is therefore the
    * point at infinity -- for exercising the on-chain rejection path (PoP verify / `.validated`) that
    * defends against exactly this key, independent of whether `mkBlsSecretKey`'s own seed-length/
    * non-zero-scalar guard (audit M3) would also catch it. Bypasses `mkBlsSecretKey` entirely (raw
    * blst calls) so it stays constructible even now that `mkBlsSecretKey` fails closed on the
    * short/degenerate seed that used to produce this same key.
    */
  def zero(): BlsKeyPair = {
    val sk = new blst.SecretKey()
    sk.keygen_v5(Array.fill[Byte](31)(1), "BLS-SIG-KEYGEN-SALT-".getBytes)
    BlsKeyPair.unsafeFromSecretKey(sk)
  }
}
