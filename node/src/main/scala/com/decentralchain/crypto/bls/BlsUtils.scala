package com.decentralchain.crypto.bls

import cats.syntax.either.*
import supranational.blst
import supranational.blst.BLST_ERROR

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

object BlsUtils {
  val BlsDomainSeparationTag = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_NUL_"           // We have a non-standard PoP
  private val BlsKeyGenSalt  = "BLS-SIG-KEYGEN-SALT-".getBytes(StandardCharsets.UTF_8) // From v4

  val PublicKeySizeInBytes = 48
  val SignatureSizeInBytes = 96

  def mkBlsSecretKey(arr: Array[Byte]): blst.SecretKey = {
    val sk = new blst.SecretKey()
    sk.keygen_v5(arr, BlsKeyGenSalt)
    sk
  }

  def mkBlsPublicKey(sk: blst.SecretKey): Array[Byte] = new blst.P1(sk).compress()

  def signBasic(sk: blst.SecretKey, message: Array[Byte]): Array[Byte] =
    new blst.P2()
      .hash_to(message, BlsDomainSeparationTag, Array.emptyByteArray)
      .sign_with(sk)
      .compress()

  /** @param blsSigBytes Validated internally https://github.com/supranational/blst#signature-verification
    * @param blsPkBytes Expected to be validated
    */
  def verifyBasic(blsSigBytes: Array[Byte], message: Array[Byte], blsPkBytes: Array[Byte]): Either[String, Unit] =
    verify(blsSigBytes, message, new blst.P1_Affine(blsPkBytes))

  /** Pairwise signature aggregation (audit L1): the only non-fail-closed primitive in this file until
    * this fix -- it took raw `Array[Byte]` and threw out of `new blst.P2(...)` on malformed input
    * instead of returning `Left` like its siblings (`aggSig`, `verifyAgg`). Kept for the pairwise-fold
    * call shape (`reduceLeft(aggSign)`); prefer [[aggSig]] for aggregating a whole set in one pass.
    */
  def aggSign(baseSig: Array[Byte], appendSig: Array[Byte]): Either[String, Array[Byte]] = for {
    _   <- sanityCheckSignature(baseSig)
    _   <- sanityCheckSignature(appendSig)
    agg <- Either
      .catchNonFatal(new blst.P2(baseSig).add(new blst.P2(appendSig)).compress())
      .leftMap(e => s"Error aggregating BLS signatures: ${e.getMessage}")
  } yield agg

  /** Single-pass aggregation of the whole signature set, replacing a pairwise `reduceLeft(aggSign)`
    * fold (which cannot report a failure and silently keeps going on empty/invalid input).
    *
    * @param sigs Validated internally
    * @return Not validated, but must be in the group
    */
  def aggSig(sigs: Iterable[Array[Byte]]): Either[String, Array[Byte]] = for {
    _ <- Either.raiseWhen(sigs.isEmpty)("Empty BLS signature list")
    agg <- Either
      .catchNonFatal(sigs.map(new blst.P2(_)).reduce(_.add(_)))
      .leftMap(e => s"Error aggregating BLS signatures: ${e.getMessage}")
  } yield new blst.P2_Affine(agg).compress()

  /** @param aggSigBytes Validated internally
    * @param blsPks Expected to have validated public keys
    * @see https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-bls-signature-05#name-fastaggregateverify
    *
    * Defense-in-depth (audit H1): the individual-key validation this function's contract relies on
    * ("Expected to have validated public keys") is enforced only at registration time
    * (`CommitToGenerationTransactionDiff`), not here. `new blst.P1(bytes)` decompresses and checks
    * the point is on the curve, but deliberately does NOT run the (expensive) `in_group()` subgroup
    * test, and a small-subgroup element summed with others need not trip the aggregate's own
    * `is_inf()`/`BLST_PK_IS_INFINITY` check inside `verify`. So this function re-validates on the
    * *aggregate* (one `in_group()`/`is_inf()` pair instead of one per key) rather than trusting the
    * caller's contract, closing the gap for any caller that cannot itself guarantee validated inputs
    * (see C1/H1 in docs/hotstuff-bls-crypto-audit-2026-08-31.md).
    */
  def verifyAgg(aggSigBytes: Array[Byte], message: Array[Byte], blsPks: Iterable[Array[Byte]]): Either[String, Unit] = for {
    _ <- Either.raiseWhen(blsPks.isEmpty)("Empty BLS public key list")
    aggPk <- Either
      .catchNonFatal(blsPks.map(new blst.P1(_)).reduce(_.add(_)))
      .leftMap(e => s"Error aggregating BLS public keys: ${e.getMessage}")
    aggPkAffine = new blst.P1_Affine(aggPk)
    _   <- Either.raiseUnless(aggPkAffine.in_group())("Wrong BLS public key: aggregate not in a group")
    _   <- Either.raiseWhen(aggPkAffine.is_inf())("Wrong BLS public key: aggregate is point at infinity")
    res <- verify(aggSigBytes, message, aggPkAffine)
  } yield res

  private def verify(blsSigBytes: Array[Byte], message: Array[Byte], blsPkBytes: blst.P1_Affine): Either[String, Unit] = try {
    val ctx       = new blst.Pairing(true, BlsDomainSeparationTag)
    val aggResult = ctx.aggregate(blsPkBytes, new blst.P2_Affine(blsSigBytes), message, Array.emptyByteArray)
    if (aggResult != BLST_ERROR.BLST_SUCCESS) s"Can't aggregate during verification of BLS signature: $aggResult".asLeft
    else {
      ctx.commit()
      if (ctx.finalverify()) Either.unit
      else "Wrong BLS signature".asLeft
    }
  } catch {
    case NonFatal(e) => s"Error verifying BLS signature: ${e.getMessage}".asLeft
  }

  /** Full curve validation (in-group + not point-at-infinity). Expensive relative to
    * [[sanityCheckPublicKey]] -- call this only at the point a new key is trusted going forward
    * (e.g. registering a new committed generator), not on every deserialization.
    */
  def validatePublicKey(bytes: Array[Byte]): Either[String, Unit] = for {
    pk <- Either.catchNonFatal(new blst.P1_Affine(bytes)).leftMap(e => s"Error in creating BLS public key: ${e.getMessage}")
    _  <- Either.raiseUnless(pk.in_group())("Wrong BLS public key: not in a group")
    _  <- Either.raiseWhen(pk.is_inf())("Wrong BLS public key: point at infinity")
  } yield ()

  def sanityCheckPublicKey(bytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(bytes.length == PublicKeySizeInBytes) {
      s"Unexpected BLS public key length: ${bytes.length}, expected $PublicKeySizeInBytes"
    }

  // Not validating like public key, because it is validated internally during pairing verification.
  def sanityCheckSignature(bytes: Array[Byte]): Either[String, Unit] =
    Either.raiseUnless(bytes.length == SignatureSizeInBytes) {
      s"Unexpected BLS signature length: ${bytes.length}, expected $SignatureSizeInBytes"
    }
}
