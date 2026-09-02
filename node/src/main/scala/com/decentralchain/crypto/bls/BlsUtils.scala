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

  /** Stable error labels (audit L3): `Left` strings returned from this file used to interpolate raw
    * `BLST_ERROR` enum values and `e.getMessage` from blst's native exceptions. Those surface all the
    * way up through block-validation errors and, via `GenericError`, into API responses -- i.e. they
    * are effectively part of this codebase's external error contract, not internal debug output. A
    * raw library-internal string is unnecessary implementation disclosure and is unstable across blst
    * upgrades (a wording change in the native library would silently change API error text). These
    * fixed labels keep the error CATEGORY (which is what an operator needs to act on) without leaking
    * library internals; nothing here involves secret material, so this is a hygiene fix, not a
    * confidentiality one.
    */
  private val ErrEmptySignatureList    = "Empty BLS signature list"
  private val ErrEmptyPublicKeyList    = "Empty BLS public key list"
  private val ErrDuplicatePublicKeys   = "Duplicate BLS public keys in aggregate"
  private val ErrAggregatingSignatures = "Error aggregating BLS signatures"
  private val ErrAggregatingPublicKeys = "Error aggregating BLS public keys"
  private val ErrCreatingPublicKey     = "Error creating BLS public key"
  private val ErrPublicKeyNotInGroup   = "Wrong BLS public key: not in a group"
  private val ErrPublicKeyIsInfinity   = "Wrong BLS public key: point at infinity"
  private val ErrPairingAggregate      = "Error verifying BLS signature: pairing aggregation failed"
  private val ErrSignatureInvalid      = "Wrong BLS signature"
  private val ErrVerifyingSignature    = "Error verifying BLS signature"

  /** Minimum IKM length required by the IETF BLS keygen spec (draft-irtf-cfrg-bls-signature,
    * keygen_v5 / HKDF-based key derivation): >= 32 bytes of input keying material. In production the
    * seed is always a node's 32-byte Curve25519 private key (`BlsKeyPair`), so this is unreachable
    * today -- but `mkBlsSecretKey` is a public method taking an arbitrary `Array[Byte]`, and a
    * too-short/low-entropy seed can silently derive the all-zero scalar (whose public key is the
    * point at infinity), i.e. a node that believes it configured a signing key actually signs
    * nothing verifiable. Fail closed rather than let that happen silently (audit M3).
    */
  private val MinSeedLengthInBytes = 32

  def mkBlsSecretKey(arr: Array[Byte]): blst.SecretKey = {
    require(arr.length >= MinSeedLengthInBytes, s"BLS secret key seed must be at least $MinSeedLengthInBytes bytes, got ${arr.length}")
    val sk = new blst.SecretKey()
    sk.keygen_v5(arr, BlsKeyGenSalt)
    require(!new blst.P1(sk).is_inf(), "Derived BLS secret key is degenerate (zero scalar)")
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
      .leftMap(_ => ErrAggregatingSignatures)
  } yield agg

  /** Single-pass aggregation of the whole signature set, replacing a pairwise `reduceLeft(aggSign)`
    * fold (which cannot report a failure and silently keeps going on empty/invalid input).
    *
    * @param sigs Validated internally
    * @return Not validated, but must be in the group
    */
  def aggSig(sigs: Iterable[Array[Byte]]): Either[String, Array[Byte]] = for {
    _   <- Either.raiseWhen(sigs.isEmpty)(ErrEmptySignatureList)
    agg <- Either
      .catchNonFatal(sigs.map(new blst.P2(_)).reduce(_.add(_)))
      .leftMap(_ => ErrAggregatingSignatures)
  } yield new blst.P2_Affine(agg).compress()

  /** @param aggSigBytes Validated internally
    * @param blsPks Expected to have validated public keys, and MUST be distinct (see below)
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
    *
    * Duplicate-key rejection (audit M4): this is FastAggregateVerify, which aggregates public keys
    * (not signatures-per-key), so a repeated key changes what the aggregate actually asserts --
    * `Seq(pk1, pk2, pk1)` verifies `sig1+sig2+sig1`, silently double-counting pk1's vote/stake. We
    * reject rather than de-duplicate: de-duplicating would silently change the caller's requested
    * semantics, whereas every legitimate caller in this codebase already guarantees distinctness by
    * construction and a duplicate here is therefore always a caller bug or an attacker-supplied
    * multiset. Confirmed callers: the appender's aggregated-endorsement path rejects duplicate
    * endorser indexes before building the pubkey list (`fv.valid.toSet.size != fv.valid.length` in
    * `state/appender/package.scala`); the HotStuff vote pool de-duplicates per voter before forming a
    * QC (`formQC`'s `groupBy(_.voterIndex)`). `HotStuffQuorum.verifyQC`, by contrast, maps
    * `qc.signerIndexes` -- a wire-deserialized `Seq[Int]` with NO distinctness guarantee of its own --
    * straight into the pubkey list, so for that caller this rejection is load-bearing, not merely
    * redundant: a malicious peer could otherwise supply a QC with a repeated signer index to inflate
    * its apparent signing stake past 2/3 quorum without a matching honest signature count.
    */
  def verifyAgg(aggSigBytes: Array[Byte], message: Array[Byte], blsPks: Iterable[Array[Byte]]): Either[String, Unit] = for {
    _     <- Either.raiseWhen(blsPks.isEmpty)(ErrEmptyPublicKeyList)
    _     <- Either.raiseUnless(blsPks.map(_.toSeq).toSet.size == blsPks.size)(ErrDuplicatePublicKeys)
    aggPk <- Either
      .catchNonFatal(blsPks.map(new blst.P1(_)).reduce(_.add(_)))
      .leftMap(_ => ErrAggregatingPublicKeys)
    aggPkAffine = new blst.P1_Affine(aggPk)
    _   <- Either.raiseUnless(aggPkAffine.in_group())(ErrPublicKeyNotInGroup)
    _   <- Either.raiseWhen(aggPkAffine.is_inf())(ErrPublicKeyIsInfinity)
    res <- verify(aggSigBytes, message, aggPkAffine)
  } yield res

  /** Pairing-allocation DoS posture (audit L4): a fresh `blst.Pairing` context is allocated on every
    * call rather than pooled/reused -- deliberately, not merely un-optimized. `Pairing` is a
    * mutable, single-use accumulator: `aggregate()` mutates its internal state, `commit()` finalizes
    * it, and `finalverify()` consumes that state once. Reusing one instance across concurrent
    * verifications would require external synchronization serializing every verification through
    * one lock, which is strictly worse under the exact flood scenario the audit is concerned about
    * (many invalid votes arriving concurrently) -- it would trade a per-call native allocation for a
    * shared bottleneck. blst does expose `Pairing.merge` for combining multiple contexts into a
    * single batched final check, but adopting that is a real verification-flow redesign (accumulate
    * N contexts, then merge + finalverify once), not a cheap fix, and changes the caller-visible
    * shape of a failure (today each vote's `Left` is independently attributable; a merged batch
    * verify only tells you the batch failed, not which member). The audit's suggested mitigation that
    * IS cheap and already lives on the hot path it names: `HotStuffQuorum.verifyVote` checks
    * committee membership (`committee.find(_.index.toInt == vote.voterIndex)`, a map lookup) before
    * ever reaching this pairing-based `verify` call, so an attacker flooding votes from indexes
    * outside the committee never allocates a `Pairing` at all -- only a genuine (if forged) committee
    * member's vote pays the pairing cost. Rate limiting on the vote-ingress path (also named in the
    * audit) is a networking-layer concern outside this file's scope. No code change here.
    */
  private def verify(blsSigBytes: Array[Byte], message: Array[Byte], blsPkBytes: blst.P1_Affine): Either[String, Unit] = try {
    val ctx       = new blst.Pairing(true, BlsDomainSeparationTag)
    val aggResult = ctx.aggregate(blsPkBytes, new blst.P2_Affine(blsSigBytes), message, Array.emptyByteArray)
    if (aggResult != BLST_ERROR.BLST_SUCCESS) ErrPairingAggregate.asLeft
    else {
      ctx.commit()
      if (ctx.finalverify()) Either.unit
      else ErrSignatureInvalid.asLeft
    }
  } catch {
    case NonFatal(_) => ErrVerifyingSignature.asLeft
  }

  /** Full curve validation (in-group + not point-at-infinity). Expensive relative to
    * [[sanityCheckPublicKey]] -- call this only at the point a new key is trusted going forward
    * (e.g. registering a new committed generator), not on every deserialization.
    */
  def validatePublicKey(bytes: Array[Byte]): Either[String, Unit] = for {
    pk <- Either.catchNonFatal(new blst.P1_Affine(bytes)).leftMap(_ => ErrCreatingPublicKey)
    _  <- Either.raiseUnless(pk.in_group())(ErrPublicKeyNotInGroup)
    _  <- Either.raiseWhen(pk.is_inf())(ErrPublicKeyIsInfinity)
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
