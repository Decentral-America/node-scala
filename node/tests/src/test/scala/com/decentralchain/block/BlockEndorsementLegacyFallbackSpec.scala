package com.decentralchain.block

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsSignature, BlsUtils, TestBlsKeyPair}
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec

/** Regression coverage for the verify-side backward-compatibility fix extending Task 8b's
  * `CommitToGenerationTransaction.verifyPop` fix to `BlockEndorsement.signatureValid`/`verifyAgg`.
  *
  * Commit `448d56557f` deleted `BlockEndorsement.dst(cryptoV2: Boolean)`'s legacy `_NUL_` fallback
  * (kept pre-activation, `_ENDORSE_` post-activation) in favor of an unconditional
  * `Dst = BlsEndorseDomainSeparationTag`, on the same now-falsified "no BLS bytes exist on any chain
  * we keep" premise the PoP fix addressed. `mkMessage`'s layout was never changed by that commit,
  * only the DST selection was -- same pattern as PoP and HotStuff votes -- so `signatureValid`/
  * `verifyAgg` now also try the legacy `_NUL_` DST as a fallback. New endorsement signing
  * (`BlockEndorsement.sign`/`signed`) stays v2-only.
  */
class BlockEndorsementLegacyFallbackSpec extends FlatSpec {
  private val kp: BlsKeyPair = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(7))
  private val kp2: BlsKeyPair = TestBlsKeyPair.unsafe(Array.fill[Byte](32)(8))

  private val finalizedId   = ByteStr(Array.fill[Byte](32)(1))
  private val finalizedHeight = Height(100)
  private val endorsedId    = ByteStr(Array.fill[Byte](32)(2))
  private val message       = BlockEndorsement.mkMessage(finalizedId, finalizedHeight, endorsedId)

  "BlockEndorsement.verify" should "accept a legacy-tagged endorsement signature (pre-v2 on-chain endorsement)" in {
    val legacySig = kp.sign(message, BlsUtils.BlsLegacyDomainSeparationTag)
    BlockEndorsement.verify(legacySig.arr, message, kp.publicKey.arr) shouldBe a[Right[?, ?]]
  }

  it should "still accept a current v2-tagged (_ENDORSE_) endorsement signature (no regression)" in {
    val v2Sig = kp.sign(message, BlsUtils.BlsEndorseDomainSeparationTag)
    BlockEndorsement.verify(v2Sig.arr, message, kp.publicKey.arr) shouldBe a[Right[?, ?]]
  }

  it should "reject a signature that is wrong under both schemes" in {
    val wrongDstSig = kp.sign(message, BlsUtils.BlsPopDomainSeparationTag)
    BlockEndorsement.verify(wrongDstSig.arr, message, kp.publicKey.arr) shouldBe a[Left[?, ?]]
  }

  "BlockEndorsement.signatureValid" should "accept a legacy-tagged signature via the case-class instance method" in {
    val legacySig    = kp.sign(message, BlsUtils.BlsLegacyDomainSeparationTag)
    val endorsement  = BlockEndorsement(com.decentralchain.state.GeneratorIndex(0), finalizedId, finalizedHeight, endorsedId, legacySig)
    endorsement.signatureValid(kp.publicKey) shouldBe a[Right[?, ?]]
  }

  "BlockEndorsement.verifyAgg" should "accept an aggregated legacy-tagged signature (pre-v2 on-chain aggregated endorsement)" in {
    val sigs: Seq[BlsSignature] = Seq(kp, kp2).map(_.sign(message, BlsUtils.BlsLegacyDomainSeparationTag))
    val aggSig                  = BlsSignature.agg(sigs).toOption.get
    BlockEndorsement.verifyAgg(aggSig.arr, message, Seq(kp.publicKey.arr, kp2.publicKey.arr)) shouldBe a[Right[?, ?]]
  }

  it should "still accept an aggregated v2-tagged (_ENDORSE_) signature (no regression)" in {
    val sigs: Seq[BlsSignature] = Seq(kp, kp2).map(_.sign(message, BlsUtils.BlsEndorseDomainSeparationTag))
    val aggSig                  = BlsSignature.agg(sigs).toOption.get
    BlockEndorsement.verifyAgg(aggSig.arr, message, Seq(kp.publicKey.arr, kp2.publicKey.arr)) shouldBe a[Right[?, ?]]
  }

  it should "reject an aggregate that is wrong under both schemes" in {
    val sigs: Seq[BlsSignature] = Seq(kp, kp2).map(_.sign(message, BlsUtils.BlsPopDomainSeparationTag))
    val aggSig                  = BlsSignature.agg(sigs).toOption.get
    BlockEndorsement.verifyAgg(aggSig.arr, message, Seq(kp.publicKey.arr, kp2.publicKey.arr)) shouldBe a[Left[?, ?]]
  }
}
