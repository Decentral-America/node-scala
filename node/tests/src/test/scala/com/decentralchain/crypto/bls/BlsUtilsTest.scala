package com.decentralchain.crypto.bls

import com.decentralchain.account.KeyPair
import com.decentralchain.common.utils.Base64
import com.decentralchain.crypto.bls.BlsUtils.*
import com.decentralchain.test.FreeSpec
import org.scalatest.EitherValues
import supranational.blst
import supranational.blst.{BLST_ERROR, P1, SecretKey}

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom

class BlsUtilsTest extends FreeSpec with EitherValues {
  private val privateKey1 = mkRandomSecretKey()
  private val publicKey1  = mkBlsPublicKey(privateKey1)

  private val privateKey2 = mkRandomSecretKey()
  private val publicKey2  = mkBlsPublicKey(privateKey2)

  private val privateKey3 = mkRandomSecretKey()
  private val publicKey3  = mkBlsPublicKey(privateKey3)

  private val message = "assertion".getBytes()

  private val sig1 = signBasic(privateKey1, message)
  private val sig2 = signBasic(privateKey2, message)
  private val sig3 = signBasic(privateKey3, message)

  "aggregation in verifyAgg" - {
    "aggregation of two same signatures" in {
      val aggSig = BlsUtils.aggSign(BlsUtils.aggSign(sig1, sig2), sig1)

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey1, publicKey2, publicKey1)) shouldBe a[Right[?, ?]]
      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey1, publicKey2)) shouldBe a[Left[?, ?]]
    }

    "different order of signatures and keys" in {
      val aggSig = BlsUtils.aggSign(sig1, sig2)

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey2, publicKey1)) shouldBe a[Right[?, ?]]
    }

    "associativity" in {
      val aggSig = Seq(sig1, sig2, sig3).reduceLeft(BlsUtils.aggSign)

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey2, publicKey1, publicKey3)) shouldBe a[Right[?, ?]]
    }
  }

  "zero secret/public keys and signatures" - {
    val message = "test".getBytes()

    val zeroSk  = BlsUtils.mkBlsSecretKey(Array.fill[Byte](31)(1))
    val zeroPk  = new blst.P1(zeroSk)
    val zeroSig = new blst.P2()
      .hash_to(message, BlsDomainSeparationTag, Array.emptyByteArray)
      .sign_with(zeroSk)

    val okSk  = BlsUtils.mkBlsSecretKey(Array.fill[Byte](32)(0))
    val okPk  = new blst.P1(okSk)
    val okSig = new blst.P2()
      .hash_to(message, BlsDomainSeparationTag, Array.emptyByteArray)
      .sign_with(okSk)

    "can't create pk from zero bytes" in {
      val bytes = Array.fill[Byte](zeroPk.serialize().length)(0)
      intercept[RuntimeException] { new blst.P1(bytes) }.getMessage should include("point is not on curve")
    }

    "zeroSk" in {
      zeroSk.to_bendian() shouldBe Array.fill[Byte](32)(0)
    }

    "zeroPk in group" in {
      zeroPk.is_inf() shouldBe true
      zeroPk.in_group() shouldBe true
    }

    "zeroSk in group" in {
      zeroSig.is_inf() shouldBe true
      zeroSig.in_group() shouldBe true
    }

    "zeroSig not verified" - {
      "by zeroPk" in {
        BlsUtils.verifyBasic(zeroSig.serialize(), message, zeroPk.serialize()) shouldBe a[Left[?, ?]]
      }

      "by okPk" in {
        BlsUtils.verifyBasic(zeroSig.serialize(), message, okPk.serialize()) shouldBe a[Left[?, ?]]
      }
    }

    "okSig not verified by zeroPk" in {
      BlsUtils.verifyBasic(okSig.serialize(), message, zeroPk.serialize()) shouldBe a[Left[?, ?]]
    }

    "aggregated pk" - {
      "okPk + zeroPk == okPk" in {
        okPk.dup().add(zeroPk).is_equal(okPk) shouldBe true
      }

      "zeroPk + okPk == okPk" in {
        zeroPk.dup().add(okPk).is_equal(okPk) shouldBe true
      }
    }

    "aggSig" - {
      "okSig + zeroSig == okSig" in {
        okSig.dup().add(zeroSig).is_equal(okSig) shouldBe true
      }

      "zeroSig + okSig == okSig" in {
        zeroSig.dup().add(okSig).is_equal(okSig) shouldBe true
      }
    }

    "aggSig verification with zeroSk" in {
      val aggSig = okSig.dup().add(zeroSig)
      BlsUtils.verifyAgg(aggSig.serialize(), message, Seq(okPk.serialize(), zeroPk.serialize())) shouldBe a[Right[?, ?]]
    }
  }

  "expected public keys" in forAll(
    Table(
      ("seed", "expected sk in base64", "expected pk in base64"),
      (
        "-EXACTLY-32-BYTES-LENGTH-STRING-",
        "ELIahWN5dDHoS9hScLMgGSNwF1qpuikaqNrdxZHCIuE=",
        "qSUdS6J92V1nNOdx4TafRu4U17qhqwVXKNyy2IVV9GWnUzUYlk/uH4l8fOoupSJj"
      ),
      (
        "a string longer than 32 bytes is used as the seed here",
        "TmpPD8kiXQtRzvpQ+TJm6RUqjy5N3t9WZlv40iA66cw=",
        "o2DzLHA7PG7BvHXTqnz4c8arX/tjiU11YuHsQnfUH0Lo/+ksy1toSYXFFy5auEJT"
      )
    )
  ) { (seed, expectedSkInBase64, expectedPkInBase64) =>
    val sk = BlsUtils.mkBlsSecretKey(seed.getBytes(StandardCharsets.UTF_8))
    Base64.encode(sk.to_bendian()) shouldBe expectedSkInBase64

    val pk = BlsUtils.mkBlsPublicKey(sk)
    Base64.encode(pk) shouldBe expectedPkInBase64
  }

  "pk restore" in {
    val sk = BlsUtils.mkBlsSecretKey("-EXACTLY-32-BYTES-LENGTH-STRING-".getBytes(StandardCharsets.UTF_8))
    val pk = BlsUtils.mkBlsPublicKey(sk)

    val pkRestored1 = new P1(pk).compress()
    pkRestored1 shouldBe pk

    val skRestored = new SecretKey()
    skRestored.from_bendian(sk.to_bendian())

    val pkRestored2 = BlsUtils.mkBlsPublicKey(skRestored)
    pkRestored2 shouldBe pk
  }

  // --- Task 20 adversarial tests (TDD, security-relevant): written before BlsUtils/BlsPublicKey/
  // BlsSignature are hardened, to prove each gap is real against the CURRENT code, not theoretical.
  //
  // Note on the first case: the real upstream fix (confirmed by pulling BlsPublicKey.scala from
  // c1fcc5e0b58cba6743e2d636da81574291c8068c) does NOT reject a point-at-infinity key in the plain
  // `BlsPublicKey(bytes)` constructor -- that constructor only sanity-checks length, by design,
  // because it's used on every deserialization (e.g. from the wire) where a full curve check would
  // be wasteful. The actual enforcement point is the new `.validated` extension, called once, only
  // when registering a new committed generator (CommitToGenerationTransactionDiff). So the
  // adversarial assertion below targets `.validated`, not `apply` -- targeting `apply` would not
  // match the real upstream contract and would be testing for a fix that was never intended to
  // live there.
  "registering a point-at-infinity BLS public key" - {
    // zeroSk (a secret key derived from an under-length/all-same-byte seed, per mkBlsSecretKey's own
    // doc: "otherwise returns a zero secret key") produces a public key that IS the point at
    // infinity -- reusing the exact fixture already proven as such above ("zeroPk in group":
    // zeroPk.is_inf() shouldBe true).
    val infinityKeyBytes = new blst.P1(BlsUtils.mkBlsSecretKey(Array.fill[Byte](31)(1))).compress()

    "is currently NOT rejected by plain construction (by design -- apply only checks length)" in {
      BlsPublicKey(infinityKeyBytes) shouldBe a[Right[?, ?]]
    }

    "should be rejected by the registration-time `.validated` check (currently does not exist -- RED)" in {
      // This line does not compile against current BlsPublicKey.scala: `.validated` is added by
      // Task 20 Step 2. Confirms the enforcement point is genuinely missing, not just untested.
      BlsPublicKey(infinityKeyBytes).value.validated shouldBe a[Left[?, ?]]
    }
  }

  "verifyBasic" - {
    "the underlying blst pairing aggregate() call flags a point-at-infinity public key" in {
      // Proves the gap is reachable through real blst behavior, not a hypothetical: blst's native
      // Pairing.aggregate() specifically returns BLST_PK_IS_INFINITY (not BLST_SUCCESS) when the
      // public key is the point at infinity.
      val infinityPk = new blst.P1(BlsUtils.mkBlsSecretKey(Array.fill[Byte](31)(1)))
      val ctx        = new blst.Pairing(true, BlsDomainSeparationTag)
      val aggResult = ctx.aggregate(
        new blst.P1_Affine(infinityPk.compress()),
        new blst.P2_Affine(sig1),
        message,
        Array.emptyByteArray
      )
      aggResult shouldBe BLST_ERROR.BLST_PK_IS_INFINITY
    }

    "should reject when the underlying pairing aggregate does not return BLST_SUCCESS (currently ignores the return code entirely -- RED)" in {
      // Current BlsUtils.verifyBasic calls ctx.aggregate(...) and discards its BLST_ERROR return
      // value outright (see the production code: the call's result is never bound to anything),
      // then unconditionally proceeds to ctx.commit()/ctx.finalverify() regardless of what
      // aggregate() reported. It also returns a bare Boolean, so there is no way to observe the
      // discarded error at all. This assertion does not compile against current code (verifyBasic
      // returns Boolean, not Either) -- confirms the fix (checking the return code, changing the
      // return type to Either[String, Unit]) is genuinely missing.
      val infinityPk = new blst.P1(BlsUtils.mkBlsSecretKey(Array.fill[Byte](31)(1)))
      BlsUtils.verifyBasic(sig1, message, infinityPk.compress()) shouldBe a[Left[?, ?]]
    }
  }

  private def mkRandomSecretKey(): SecretKey = mkBlsSecretKey(mkRandomDccKeyPair().privateKey.arr)
  private def mkRandomDccKeyPair(): KeyPair  = KeyPair(Array.fill(32)(ThreadLocalRandom.current().nextInt().toByte))
}
