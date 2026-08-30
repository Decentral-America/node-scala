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

  def aggSign(baseSig: Array[Byte], appendSig: Array[Byte]): Array[Byte] =
    new blst.P2(baseSig).add(new blst.P2(appendSig)).compress()

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
    */
  def verifyAgg(aggSigBytes: Array[Byte], message: Array[Byte], blsPks: Iterable[Array[Byte]]): Either[String, Unit] = for {
    _ <- Either.raiseWhen(blsPks.isEmpty)("Empty BLS public key list")
    aggPk <- Either
      .catchNonFatal(blsPks.map(new blst.P1(_)).reduce(_.add(_)))
      .leftMap(e => s"Error aggregating BLS public keys: ${e.getMessage}")
    res <- verify(aggSigBytes, message, new blst.P1_Affine(aggPk))
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
