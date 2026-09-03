package com.decentralchain.transaction

import com.decentralchain.common.utils.Base64
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey}
import com.decentralchain.state.Height
import com.decentralchain.test.FlatSpec

/** Regression coverage for the verify-side backward-compatibility fix to `448d56557f`.
  *
  * `448d56557f` ("remove feature 30, BLS crypto becomes unconditional") deleted the legacy `_NUL_`
  * domain-separation tag and the legacy (bare `endorserPk ‖ periodStart`) PoP message layout,
  * reasoning that "no BLS bytes exist on any chain we keep, since mainnet never activated feature
  * 25". That premise was factually wrong for the testnet-relaunch chain: it pre-activates feature 25
  * (`DeterministicFinality`) at genesis and carries real `CommitToGenerationTransaction` PoP
  * signatures signed under the legacy scheme from height ~12 onward -- confirmed by direct replay of
  * the real chain data against the frozen `1bd671f8e6` build, which reproduces the live chain's
  * height-2639 state hash byte-for-byte. A replay of `448d56557f`'s HEAD against the same chain data
  * fails deterministically at height 12 with `GenericError(Invalid commitment signature)`, because
  * verification could no longer accept a legacy-shaped/legacy-tagged signature at all.
  *
  * `CommitToGenerationTransaction.verifyPop` is the fix: it tries the v2 scheme first and falls back
  * to the legacy scheme on failure, so historical on-chain commitments stay verifiable forever (e.g.
  * on a full resync from genesis) while new signing (`mkPopSignature`) stays v2-only.
  */
class CommitToGenerationPopLegacyFallbackSpec extends FlatSpec {
  private val sender     = TxHelpers.signer(7)
  private val endorserKp = BlsKeyPair(TxHelpers.signer(8).privateKey)
  private val start      = Height(3000)
  private val chainId    = 'T'.toByte

  "verifyPop" should "accept a legacy-shaped/legacy-tagged signature (pinned historical vector)" in {
    // Pinned vector reused verbatim from the now-deleted BlsLegacyVectorRegressionSpec ("legacy PoP
    // vector", added under the original feature-30 plan, deleted by 448d56557f): pk = endorser's BLS
    // public key; message = legacy popMessage(endorserPk, periodStart=12345) = pk ++ periodStart(4);
    // signature = that endorser's legacy-DST (`_NUL_`) signature over that message. This byte-for-byte
    // reproduces the shape of a real on-chain, pre-v2 CommitToGenerationTransaction PoP.
    //
    // DO NOT regenerate these literals to "fix" a failure: a failure here means an actual regression
    // in the legacy fallback path (BlsUtils.BlsLegacyDomainSeparationTag or
    // CommitToGenerationTransaction's private legacy message layout), not a stale vector.
    val pk = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val signature = Base64.decode(
      "qCjEnAO3kh+PIFnsIOJtKqpJYPnUArDe1VjVvqi6Bygr+kZq68vtLR4IJ9TFuIYUDpM2ua73KFNpp4l7QdQ8Db23AQ+R6WsI4799GnwRgA8P7fBruFRkdRtBHcC4VpOQ"
    )
    val legacyEndorserPublicKey = BlsPublicKey(pk).explicitGet()
    val legacyPeriodStart       = Height(12345)

    // chainId/sender are arbitrary here -- the legacy layout ignores both, that IS the bug this vector
    // proves the fallback tolerates. Use this spec's own fixtures rather than re-deriving the original
    // synthesis seeds, since the legacy message never depends on them.
    CommitToGenerationTransaction.verifyPop(
      signature,
      chainId,
      sender.publicKey,
      legacyEndorserPublicKey,
      legacyPeriodStart
    ) shouldBe true
  }

  it should "still accept a current v2-shaped/v2-tagged signature (no regression on the new path)" in {
    val signature = CommitToGenerationTransaction.mkPopSignature(endorserKp, start, sender.publicKey, chainId)

    CommitToGenerationTransaction.verifyPop(
      signature.arr,
      chainId,
      sender.publicKey,
      endorserKp.publicKey,
      start
    ) shouldBe true
  }

  it should "reject a signature that is wrong under both schemes" in {
    // Signed under a v2 message, but for a DIFFERENT chainId than the one we verify against, and
    // under neither the legacy message shape nor the legacy DST -- must fail both branches.
    val wrongChainId = 'W'.toByte
    val signature     = CommitToGenerationTransaction.mkPopSignature(endorserKp, start, sender.publicKey, wrongChainId)

    CommitToGenerationTransaction.verifyPop(
      signature.arr,
      chainId,
      sender.publicKey,
      endorserKp.publicKey,
      start
    ) shouldBe false
  }

  it should "reject an all-zero/garbage signature under both schemes" in {
    val garbage = Array.fill(96)(0: Byte)

    CommitToGenerationTransaction.verifyPop(
      garbage,
      chainId,
      sender.publicKey,
      endorserKp.publicKey,
      start
    ) shouldBe false
  }
}
