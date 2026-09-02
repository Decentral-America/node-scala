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
      val aggSig = BlsUtils.aggSign(BlsUtils.aggSign(sig1, sig2).value, sig1).value

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey1, publicKey2, publicKey1)) shouldBe a[Right[?, ?]]
      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey1, publicKey2)) shouldBe a[Left[?, ?]]
    }

    "different order of signatures and keys" in {
      val aggSig = BlsUtils.aggSign(sig1, sig2).value

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey2, publicKey1)) shouldBe a[Right[?, ?]]
    }

    "associativity" in {
      val aggSig = Seq(sig1, sig2, sig3).reduceLeft((a, b) => BlsUtils.aggSign(a, b).value)

      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey2, publicKey1, publicKey3)) shouldBe a[Right[?, ?]]
    }

    // audit L1: aggSign must fail closed (Left), never throw, on malformed input.
    "rejects malformed input instead of throwing" in {
      BlsUtils.aggSign(Array.fill[Byte](10)(0), sig2) shouldBe a[Left[?, ?]]
      BlsUtils.aggSign(sig1, Array.emptyByteArray) shouldBe a[Left[?, ?]]
    }
  }

  "zero secret/public keys and signatures" - {
    val message = "test".getBytes()

    // A 31-byte (under the IETF minimum) seed is documented to make keygen_v5 return the zero
    // secret key -- exactly the degenerate case mkBlsSecretKey now rejects (audit M3). This section
    // still needs to construct that degenerate key to test downstream behavior against it, so it
    // uses mkDegenerateSecretKey (raw blst calls) rather than the now-guarded mkBlsSecretKey.
    val zeroSk  = mkDegenerateSecretKey()
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

  // A degenerate (all-zero-scalar) secret key, constructed via raw blst calls rather than
  // BlsUtils.mkBlsSecretKey: that entry point now rejects the under-length seed which used to
  // produce this key (audit M3), so tests that need the degenerate key itself as a fixture -- to
  // exercise downstream defenses against it -- build it directly instead.
  private def mkDegenerateSecretKey(): blst.SecretKey = {
    val sk = new blst.SecretKey()
    sk.keygen_v5(Array.fill[Byte](31)(1), "BLS-SIG-KEYGEN-SALT-".getBytes(StandardCharsets.UTF_8))
    sk
  }

  // --- Task 20 / audit regression tests: a point-at-infinity BLS public key must be rejected at
  // registration time, and the underlying pairing verification must not silently ignore blst's
  // error code for it. Both fixes are live in current code (see BlsPublicKey.validated and
  // BlsUtils.verify's aggResult check below) -- these assert the invariant they protect, not that
  // the fix is merely absent.
  "registering a point-at-infinity BLS public key" - {
    // mkDegenerateSecretKey's public key IS the point at infinity -- reusing the exact fixture
    // already proven as such above ("zeroPk in group": zeroPk.is_inf() shouldBe true).
    val infinityKeyBytes = new blst.P1(mkDegenerateSecretKey()).compress()

    "is accepted by plain construction (by design -- apply only checks length, not curve validity)" in {
      // BlsPublicKey.apply is used on every deserialization (e.g. from the wire), where a full
      // curve check would be wasteful; it intentionally only sanity-checks length.
      BlsPublicKey(infinityKeyBytes) shouldBe a[Right[?, ?]]
    }

    "is rejected by the registration-time `.validated` check" in {
      // The actual enforcement point: called once, only when registering a new committed generator
      // (CommitToGenerationTransactionDiff), not on every deserialization.
      BlsPublicKey(infinityKeyBytes).value.validated shouldBe a[Left[?, ?]]
    }
  }

  "verifyBasic" - {
    "the underlying blst pairing aggregate() call flags a point-at-infinity public key" in {
      // Proves the gap is reachable through real blst behavior, not a hypothetical: blst's native
      // Pairing.aggregate() specifically returns BLST_PK_IS_INFINITY (not BLST_SUCCESS) when the
      // public key is the point at infinity.
      val infinityPk = new blst.P1(mkDegenerateSecretKey())
      val ctx        = new blst.Pairing(true, BlsDomainSeparationTag)
      val aggResult = ctx.aggregate(
        new blst.P1_Affine(infinityPk.compress()),
        new blst.P2_Affine(sig1),
        message,
        Array.emptyByteArray
      )
      aggResult shouldBe BLST_ERROR.BLST_PK_IS_INFINITY
    }

    "rejects when the underlying pairing aggregate does not return BLST_SUCCESS" in {
      // BlsUtils.verifyBasic binds ctx.aggregate(...)'s BLST_ERROR return value and short-circuits
      // to Left before ever reaching ctx.commit()/ctx.finalverify() -- the discarded-error-code /
      // bare-Boolean-return gap this section originally targeted is closed.
      val infinityPk = new blst.P1(mkDegenerateSecretKey())
      BlsUtils.verifyBasic(sig1, message, infinityPk.compress()) shouldBe a[Left[?, ?]]
    }
  }

  // --- H1 (audit): verifyAgg must defend itself against a non-subgroup / infinity aggregate,
  // rather than relying solely on the "callers only ever pass validated keys" contract -- a contract
  // that C1 shows does not hold on every path (e.g. a light-node snapshot path). These probe the
  // production entry point (BlsUtils.verifyAgg), not blst internals directly.
  "verifyAgg defense-in-depth (audit H1)" - {
    "rejects an aggregate that sums to the point at infinity (pk + (-pk))" in {
      // publicKey1's negation, compressed: on-curve, in-group, but pk1 + (-pk1) == infinity.
      val negPk1 = new blst.P1(publicKey1).neg().compress()
      BlsUtils.verifyAgg(sig1, message, Seq(publicKey1, negPk1)) shouldBe a[Left[?, ?]]
    }

    "rejects a public key that is on-curve but not in the correct subgroup" in {
      // A hand-picked BLS12-381 G1 point (x=4, smallest x with a valid y on E(Fp): y^2 = x^3 + 4)
      // that is on-curve (blst's decompression itself enforces that) but, verified independently via
      // plain-Python double-and-add, NOT a member of the prime-order-r subgroup: r*(x,y) != infinity.
      // This is exactly the "on curve, wrong subgroup" shape H1 targets -- blst's decompression alone
      // does not run the (separate, expensive) in_group() check.
      val notInGroupPkBytes =
        "800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004"
          .grouped(2)
          .map(Integer.parseInt(_, 16).toByte)
          .toArray
      val candidate = new blst.P1_Affine(notInGroupPkBytes)
      assume(candidate.on_curve(), "test fixture must be on-curve to be a meaningful probe")
      assume(!candidate.in_group(), "test fixture must actually be outside the subgroup to be meaningful")
      BlsUtils.verifyAgg(sig1, message, Seq(publicKey1, notInGroupPkBytes)) shouldBe a[Left[?, ?]]
    }

    "still verifies an honest aggregate (no regression)" in {
      val aggSig = BlsUtils.aggSig(Seq(sig1, sig2)).value
      BlsUtils.verifyAgg(aggSig, message, Seq(publicKey1, publicKey2)) shouldBe a[Right[?, ?]]
    }
  }

  // --- M3 (audit): mkBlsSecretKey must fail closed on degenerate/too-short seeds instead of
  // silently deriving the zero scalar (whose public key is the point at infinity -- a node
  // "configured" this way would silently sign nothing verifiable). IETF BLS keygen requires >= 32
  // bytes of IKM; the production caller (BlsKeyPair) always supplies a 32-byte Curve25519 key, so
  // this is unreachable today, but mkBlsSecretKey is a public method taking arbitrary bytes.
  "mkBlsSecretKey degenerate-seed rejection (audit M3)" - {
    "rejects an empty seed" in {
      intercept[IllegalArgumentException](BlsUtils.mkBlsSecretKey(Array.emptyByteArray))
    }

    "rejects a 31-byte seed (one short of the IETF minimum)" in {
      intercept[IllegalArgumentException](BlsUtils.mkBlsSecretKey(Array.fill[Byte](31)(1)))
    }

    "accepts a 32-byte seed (the production shape) and derives a non-degenerate key" in {
      val sk = BlsUtils.mkBlsSecretKey(Array.fill[Byte](32)(1))
      new blst.P1(sk).is_inf() shouldBe false
    }
  }

  private def mkRandomSecretKey(): SecretKey = mkBlsSecretKey(mkRandomDccKeyPair().privateKey.arr)
  private def mkRandomDccKeyPair(): KeyPair  = KeyPair(Array.fill(32)(ThreadLocalRandom.current().nextInt().toByte))
}
