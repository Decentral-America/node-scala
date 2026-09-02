package com.decentralchain.crypto.bls

import com.decentralchain.common.utils.Base64
import com.decentralchain.test.FreeSpec
import org.scalatest.EitherValues

/** Task 9, Step 1-2 (feature-30 BlsCryptoV2 plan): pinned legacy-DST regression vectors.
  *
  * PROVENANCE: the live testnet named in the task-9 brief (`http://localhost:6869`) is not reachable
  * from this environment. As a fallback, `https://testnet-node.decentralchain.io` (the DecentralChain
  * public testnet REST API, per this repo's own operational memory) WAS reachable and was queried
  * read-only:
  *
  *   - `GET /activation/status` at chain height 2639 shows this deployed build has no `BlsCryptoV2`
  *     feature entry at all (it predates this branch) -- every signature that build could ever have
  *     produced is unconditionally legacy-DST by construction.
  *   - However, a scan of `GET /blocks/at/<h>` across heights {50, 100, 200, 500, 1000, 1500, 2000,
  *     2500, 2600} found `transactionCount: 0` at every sampled height (an idle chain), and
  *     `GET /blocks/headers/at/<h>` carries no `finalizationVoting` field at all -- so no real
  *     on-chain `CommitToGenerationTransaction` (type 20) or `FinalizationVoting.aggregatedEndorsement`
  *     was available to harvest.
  *
  * Per the brief's explicit fallback ("If no node is reachable, synthesize instead" -- generalized
  * here to "if no qualifying on-chain data is reachable"), the vectors below are SYNTHESIZED under
  * fixed, hard-coded seeds and printed once (see the now-deleted one-shot printer used to produce
  * this file's literals; the seeds themselves are reproduced in comments below for auditability, but
  * are NOT re-derived at test run time):
  *
  *   - PoP signer seed:  the 32 ASCII bytes "task9-legacy-vector-signer1-see"
  *   - Endorsement signer 1 seed: the 32 ASCII bytes "task9-legacy-vector-signer1-see"
  *   - Endorsement signer 2 seed: the 32 ASCII bytes "task9-legacy-vector-signer2-see"
  *   - DCC sender seed (PoP message binding, legacy layout does not use it): "task9-legacy-vector-sender-seed"
  *   - generationPeriodStart = 12345, chainId = 'D' (0x44 = 68)
  *   - Endorsement: finalizedId = 32 bytes of 0x07, finalizedHeight = 100, endorsedId = 32 bytes of 0x09
  *
  * THIS FILE MUST NEVER BE REGENERATED TO "FIX" A FAILURE. A failure here means a legacy DST vector
  * that used to verify (or fail) no longer does, i.e. an actual regression in `BlsUtils`,
  * `CommitToGenerationTransaction.popMessage`/`popDst`, or `BlockEndorsement.mkMessage`/`dst` --
  * regenerating the literals from current code would make the test tautological and blind to exactly
  * that regression. If a change to the message layout or DST scheme is intentional, that is a
  * consensus-breaking event that needs its own migration plan, not an update to this file's bytes.
  */
class BlsLegacyVectorRegressionSpec extends FreeSpec with EitherValues {

  private val v2Tags = Seq(
    "POP"     -> BlsUtils.BlsPopDomainSeparationTagV2,
    "ENDORSE" -> BlsUtils.BlsEndorseDomainSeparationTagV2,
    "HSVOTE"  -> BlsUtils.BlsHsVoteDomainSeparationTagV2
  )

  "legacy PoP vector" - {
    // pk = endorser's BLS public key; message = popMessage(chainId='D', senderPk, endorserPk, periodStart=12345, cryptoV2=false)
    val pk        = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val message   = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFcAAAwOQ==")
    val signature = Base64.decode("qCjEnAO3kh+PIFnsIOJtKqpJYPnUArDe1VjVvqi6Bygr+kZq68vtLR4IJ9TFuIYUDpM2ua73KFNpp4l7QdQ8Db23AQ+R6WsI4799GnwRgA8P7fBruFRkdRtBHcC4VpOQ")

    "verifies under the legacy DST" in {
      BlsUtils.verifyBasic(signature, message, pk, BlsUtils.BlsDomainSeparationTag) shouldBe a[Right[?, ?]]
    }

    "verifies under the BlsUtils-level default (which IS the legacy DST)" in {
      BlsUtils.verifyBasic(signature, message, pk) shouldBe a[Right[?, ?]]
    }

    "fails under every v2 DST" in {
      v2Tags.foreach { case (label, tag) =>
        withClue(s"verified under $label: ") {
          BlsUtils.verifyBasic(signature, message, pk, tag) shouldBe a[Left[?, ?]]
        }
      }
    }
  }

  "legacy aggregated endorsement vector (2-signer known set)" - {
    // pk1, pk2 = the two endorsers' BLS public keys; message = BlockEndorsement.mkMessage(finalizedId
    // = 32 bytes of 0x07, finalizedHeight = 100, endorsedId = 32 bytes of 0x09); aggSig = the
    // FastAggregateVerify-style aggregate of both signers' legacy-DST signatures over that message.
    val pk1    = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val pk2    = Base64.decode("ufAt+MwEJbR3hVEdco3aTWFgFCPnSi40GH/igBlwEWJ515I7/LNQVupwX7EIVWOD")
    val message = Base64.decode("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcAAABkCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=")
    val aggSig  = Base64.decode("oGRF/2FMhl4NHXwvG0h1Lr4Dlq6pAwvU8c4vUek1cVNbaPFPDaNSd0Ekz1Hbgc5MDEoNL2sMJQQFnAu3aw7fmskiQqLauYnweOqxuXeCXL7ryBz5nwqemNsFYot9S49u")

    "verifies under the legacy DST" in {
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2), BlsUtils.BlsDomainSeparationTag) shouldBe a[Right[?, ?]]
    }

    "verifies under the BlsUtils-level default (which IS the legacy DST)" in {
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2)) shouldBe a[Right[?, ?]]
    }

    "fails under every v2 DST" in {
      v2Tags.foreach { case (label, tag) =>
        withClue(s"verified under $label: ") {
          BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2), tag) shouldBe a[Left[?, ?]]
        }
      }
    }

    "fails if the signer set is wrong (single signer instead of the aggregate pair)" in {
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1), BlsUtils.BlsDomainSeparationTag) shouldBe a[Left[?, ?]]
    }
  }
}
