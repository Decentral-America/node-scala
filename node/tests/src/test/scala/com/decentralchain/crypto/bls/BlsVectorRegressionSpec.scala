package com.decentralchain.crypto.bls

import com.decentralchain.common.utils.Base64
import com.decentralchain.test.FreeSpec
import org.scalatest.EitherValues

/** Pinned byte vectors for the three BLS contexts (PoP / endorsement / HotStuff vote), synthesized from
  * fixed seeds (documented below) and pasted as literals. They pin TODAY'S message layouts + domain tags
  * so an accidental change to either fails loudly. Regenerate ONLY as part of a deliberate, reviewed
  * encoding change -- which is a chain-identity change (every node must ship it from genesis). Never
  * regenerate to make a red test green.
  *
  * SEEDS (each = the 31-ASCII-byte string below + one trailing 0x00 byte = 32 bytes):
  *   - PoP signer seed: "task9-legacy-vector-signer1-see"
  *   - Endorsement signer 1 seed: "task9-legacy-vector-signer1-see" (INTENTIONALLY the same string as
  *     the PoP signer seed above -- do not "fix" that)
  *   - Endorsement signer 2 seed: "task9-legacy-vector-signer2-see"
  *   - DCC sender seed (PoP message binding): "task9-legacy-vector-sender-seed"
  *   - generationPeriodStart = 12345, chainId = 'D' (0x44 = 68)
  *   - Endorsement: finalizedId = 32 bytes of 0x07, finalizedHeight = 100, endorsedId = 32 bytes of 0x09
  *   - HotStuff vote: view = 5, phase = PREPARE, blockId = 32 bytes of 0x0b, blockHeight = 200,
  *     committeeEpoch = 7
  *
  * The printer referenced by an earlier revision of this file's comment was already deleted before
  * that revision was committed -- a throwaway script is regenerated (and deleted again, uncommitted)
  * only when a deliberate, reviewed encoding change requires new vectors.
  */
class BlsVectorRegressionSpec extends FreeSpec with EitherValues {

  private val tags: Seq[(String, String)] = Seq(
    "POP"     -> BlsUtils.BlsPopDomainSeparationTag,
    "ENDORSE" -> BlsUtils.BlsEndorseDomainSeparationTag,
    "HSVOTE"  -> BlsUtils.BlsHsVoteDomainSeparationTag
  )
  private def otherTags(own: String): Seq[(String, String)] = tags.filterNot(_._1 == own)

  "PoP vector" - {
    // pk = signer's BLS public key; message = popMessage(chainId='D', senderPk, endorserPk, periodStart=12345)
    val pk        = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val message   = Base64.decode("RAyiK++EnXPXm3XQjxg1Q4DPaMj+INI+BTqntUwqB9YRsY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFcAAAwOQ==")
    val signature = Base64.decode(
      "pJfGTl4Katu4tU7IOVMUrec0aZ1e2ch+4t0UrKdUoLXrM0KPLii5jaJj9CvIqZSBADc6bmrJ+YLUH760ylqIeern5NcZ/ErRowxU7qYFuh7VZOKfPETdrhwOMEdZuyHh"
    )

    "verifies under the POP DST" in {
      BlsUtils.verifyBasic(signature, message, pk, BlsUtils.BlsPopDomainSeparationTag) shouldBe a[Right[?, ?]]
    }

    "fails under the other two DSTs" in {
      otherTags("POP").foreach { case (label, tag) =>
        withClue(s"verified under $label: ") {
          BlsUtils.verifyBasic(signature, message, pk, tag) shouldBe a[Left[?, ?]]
        }
      }
    }
  }

  "aggregated endorsement vector (2-signer known set)" - {
    // pk1, pk2 = the two signers' BLS public keys; message = BlockEndorsement.mkMessage(finalizedId
    // = 32 bytes of 0x07, finalizedHeight = 100, endorsedId = 32 bytes of 0x09); aggSig = the
    // FastAggregateVerify-style aggregate of both signers' ENDORSE-DST signatures over that message.
    val pk1     = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val pk2     = Base64.decode("ufAt+MwEJbR3hVEdco3aTWFgFCPnSi40GH/igBlwEWJ515I7/LNQVupwX7EIVWOD")
    val message = Base64.decode("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcAAABkCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=")
    val aggSig  = Base64.decode(
      "k5ZIrhx5UYiM88e0yfJqJPrQ3oKRmAVIjIRfkwP9vIIVCMKBiOLJFt4ZxEciQPdDFA6RxvL00WSGo6W3vORp9YkxkQtspaTZgbN3l/LoP02X64G4czbzTve4pyVtr21m"
    )

    "verifies under the ENDORSE DST" in {
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2), BlsUtils.BlsEndorseDomainSeparationTag) shouldBe a[Right[?, ?]]
    }

    "fails under the other two DSTs" in {
      otherTags("ENDORSE").foreach { case (label, tag) =>
        withClue(s"verified under $label: ") {
          BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2), tag) shouldBe a[Left[?, ?]]
        }
      }
    }

    "fails if the signer set is wrong (single signer instead of the aggregate pair)" in {
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1), BlsUtils.BlsEndorseDomainSeparationTag) shouldBe a[Left[?, ?]]
    }
  }

  "HotStuff vote vector" - {
    // pk = signer's BLS public key; message = HotStuffQuorum.voteMessage(view=5, PREPARE, blockId=32
    // bytes of 0x0b, blockHeight=200, committeeEpoch=7)
    val pk        = Base64.decode("sY4xoEmpBuvbi8CRPeRuMYfJ8DjrAL7vfmuC3D5lu9WSl6f7Q10o6j4G+8lksaFc")
    val message   = Base64.decode("AAAABQELCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwsLCwAAAMgAAAAH")
    val signature = Base64.decode(
      "rDEN7Ffvwxh7TC57jLNZ0Zw1OJijamTawKiei9wzw7vK5Z5loLZ1Q3laFUEsWSocBOVPhxKlMD88MMVGNSZ6VwPmvD15cKVv90AFnt/kFaPxlsOygyyVY0pWePgYIcri"
    )

    "verifies under the HSVOTE DST" in {
      BlsUtils.verifyBasic(signature, message, pk, BlsUtils.BlsHsVoteDomainSeparationTag) shouldBe a[Right[?, ?]]
    }

    "fails under the other two DSTs" in {
      otherTags("HSVOTE").foreach { case (label, tag) =>
        withClue(s"verified under $label: ") {
          BlsUtils.verifyBasic(signature, message, pk, tag) shouldBe a[Left[?, ?]]
        }
      }
    }
  }
}
