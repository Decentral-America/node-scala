package com.decentralchain.consensus.hotstuff

import com.decentralchain.crypto.bls.{BlsPublicKey, BlsUtils}
import com.decentralchain.network.HotStuffVote
import io.decentralchain.protobuf.block.HotStuffPhase

/** Verifiable proof that one committee member double-signed within a single (view, phase, epoch):
  * `voteA` and `voteB` are two real, independently BLS-verifiable `HotStuffVote`s for DIFFERENT
  * blockIds. Design SSOT: docs/superpowers/specs/2026-09-01-hotstuff-equivocation-evidence-design.md.
  *
  * DELIBERATELY stores no top-level voter/view/phase fields: everything is derived from `voteA`, so
  * the index that gets slashed can never disagree with the votes the signatures actually cover
  * (review finding C3 — a stored-but-unchecked top-level index would let a real equivocation pair
  * by voter X be wrapped as evidence against innocent voter Y). The wire message's redundant
  * top-level fields are validated against `vote_a` at PB decode (PBHotStuffEquivocationProofs).
  *
  * The epoch-equality rule in `consistent` is load-bearing (finding C2): `committeeEpoch` is inside
  * each vote's signed bytes (T10), but two independently-signed votes CAN carry different epochs,
  * and the same voterIndex under different epochs may be two different physical generators — a
  * cross-epoch pair proves nothing and must never be treated as equivocation.
  */
case class HotStuffEquivocationProof(voteA: HotStuffVote, voteB: HotStuffVote) {
  def voterIndex: Int      = voteA.voterIndex
  def view: Int            = voteA.view
  def phase: HotStuffPhase = voteA.phase
  def committeeEpoch: Int  = voteA.committeeEpoch

  def consistent: Either[String, Unit] = for {
    _ <- Either.cond(voteA.voterIndex == voteB.voterIndex, (), "proof votes name different voters")
    _ <- Either.cond(voteA.view == voteB.view, (), "proof votes are for different views")
    _ <- Either.cond(voteA.phase == voteB.phase, (), "proof votes are for different phases")
    _ <- Either.cond(voteA.phase != HotStuffPhase.HOTSTUFF_PHASE_UNSPECIFIED, (), "proof votes have unspecified phase")
    _ <- Either.cond(voteA.committeeEpoch == voteB.committeeEpoch, (), "proof votes span committee epochs")
    _ <- Either.cond(voteA.blockId != voteB.blockId, (), "proof votes target the same block -- not an equivocation")
  } yield ()

  /** Verify both signatures against the named voter's BLS key, over the SAME canonical bytes real
    * votes sign (`HotStuffQuorum.voteMessage`) — never a reimplementation of the message format.
    *
    * KNOWN GAP — MUST BE FIXED BEFORE BlsCryptoV2 (feature 30) IS EVER ACTIVATED ON ANY CHAIN
    * (2026-09-02 review of `ed0fbcb69c`, follow-up to Task 8 in
    * docs/superpowers/plans/2026-09-02-bls-crypto-v2.md): this still hardcodes
    * `BlsUtils.BlsDomainSeparationTag` (the legacy DST) below. Task 7 (`ed0fbcb69c`) switched REAL
    * votes to sign under `_HSVOTE_` once feature 30 activates, but this verifier was not updated to
    * match, so every equivocation proof's signature check will start failing as soon as feature 30 is
    * live — proofs are silently rejected (fail-closed: no false accusations, but detection/slashing
    * goes inert without warning) at both call sites: `HotStuffCoordinator.onVote`'s proof-check (logs
    * the rejection at DEBUG only) and `state/appender/package.scala`'s
    * `validateHotStuffEquivocationProofs`. The fix (scoped in Task 8) is for `signaturesValid` to take
    * a `dst` chosen by the CALLER from the PROOF'S CONTAINING BLOCK's height (via
    * `HotStuffQuorum.voteDst(blockchain.supportsBlsCryptoV2(containingBlockHeight))`) — never from live
    * tip, since proofs are block-carried and must re-verify identically on consensus replay/rollback.
    */
  def signaturesValid(blsKeyOf: Int => Option[BlsPublicKey]): Either[String, Unit] = for {
    pk <- blsKeyOf(voterIndex).toRight(s"equivocation proof voter index $voterIndex outside committee")
    _  <- verifyOne(voteA, pk, "voteA")
    _  <- verifyOne(voteB, pk, "voteB")
  } yield ()

  private def verifyOne(v: HotStuffVote, pk: BlsPublicKey, label: String): Either[String, Unit] =
    BlsUtils
      .verifyBasic(
        v.signature.arr,
        HotStuffQuorum.voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt, v.committeeEpoch),
        pk.arr,
        BlsUtils.BlsDomainSeparationTag
      )
      .left
      .map(e => s"equivocation proof $label signature invalid for voter $voterIndex: $e")

  override def toString: String =
    s"HotStuffEquivocationProof(voter=$voterIndex, v=$view, $phase, epoch=$committeeEpoch, a=${voteA.blockId.trim}, b=${voteB.blockId.trim})"
}
