package com.decentralchain.consensus.hotstuff

import com.google.common.primitives.Ints
import com.decentralchain.block.Block.BlockId
import com.decentralchain.block.FinalizationVoting
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.bls.BlsUtils
import com.decentralchain.network.{HotStuffVote, QuorumCertificate}
import com.decentralchain.state.GeneratorSet
import io.decentralchain.protobuf.block.HotStuffPhase

/** Pure quorum / quorum-certificate (QC) logic for T2 HotStuff (see CONSENSUS.md).
  *
  * Side-effect free and deterministic. Reuses the proven feature-25 threshold
  * (`FinalizationVoting.isFinalized` == ≥ 2/3 of committed stake) and the BLS primitives in
  * `crypto.bls`. Defining/using this does NOT by itself change consensus behaviour — it is invoked
  * only by the (later) engine, which is gated behind `dcc.hotstuff.enabled` (default off).
  *
  * NOTE (audit gate): quorum/QC logic is safety-critical. This is covered by unit tests and internal
  * review, but must still be externally audited before `hotstuff.enabled` is turned on for mainnet.
  */
object HotStuffQuorum {

  /** Canonical bytes a generator signs when voting for `blockId` in a given (`view`, `phase`), under
    * committee epoch `committeeEpoch`. MUST be byte-identical on the signing and verifying sides.
    * Layout: view (4, BE) ++ phase (1) ++ blockId ++ blockHeight (4, BE) ++ committeeEpoch (4, BE).
    *
    * `committeeEpoch` (T10 fix, schema 1.6.5) identifies which committed-generators committee
    * (`state.GenerationPeriod` index — the period's already-existing rotation identifier, reused
    * rather than inventing a new committee-hash scheme) this vote was cast under. Binding it into the
    * signed bytes is what closes the cross-committee-epoch fork hazard `HotStuffCrossEpochForkSpecification`
    * proves: two disjoint committees can no longer each independently produce a signature that
    * verifies as "the same target," because the epoch each side actually signed is baked into what
    * BLS verification checks — a receiver who insists on a specific expected epoch (the
    * transition-gating rule, see `acceptableCommitteeEpoch` below) can reject a QC whose signed epoch
    * doesn't match, and cannot be fooled by relabeling after the fact since that would invalidate the
    * signature. Defaults to `0` (see `HotStuffVote`/`QuorumCertificate` scaladoc) for full backward
    * compatibility with call sites/wire peers that predate this field.
    */
  def voteMessage(view: Int, phase: HotStuffPhase, blockId: BlockId, blockHeight: Int, committeeEpoch: Int = 0): Array[Byte] =
    Ints.toByteArray(view) ++ Array(phase.value.toByte) ++ blockId.arr ++ Ints.toByteArray(blockHeight) ++ Ints.toByteArray(committeeEpoch)

  private def voteMessageOf(v: HotStuffVote): Array[Byte] =
    voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt, v.committeeEpoch)

  /** The transition-gating rule (T10, design doc §6/§8 follow-up (a)): whether a QC/vote whose signed
    * `committeeEpoch` is `qcEpoch` should be ACCEPTED by a replica that currently believes
    * `currentEpoch` is the active committee epoch. Accepts the current epoch, or the immediately
    * preceding one (a defined transition window — the exact single-committee-rotation-over-time case
    * `HotStuffVotePoolCommitteeChangeSpecification` already proves is legitimate and must keep
    * working), and rejects everything else, in particular any epoch further in the past or ANY epoch
    * in the future (a replica that hasn't yet observed/finalized the transition to a future epoch has
    * no basis to trust a QC claiming one — accepting it would reopen exactly the disjoint-committee
    * hazard this fix closes). This is deliberately a NARROW, single-step transition window, not an
    * open-ended one: widening it would let an increasingly-stale committee's QCs keep being honored
    * indefinitely, eroding the guarantee back toward the original gap.
    */
  def acceptableCommitteeEpoch(qcEpoch: Int, currentEpoch: Int): Boolean =
    qcEpoch == currentEpoch || qcEpoch == currentEpoch - 1

  /** True iff `vote.voterIndex` is a real committee member whose BLS signature over the canonical
    * vote message verifies.
    */
  def verifyVote(vote: HotStuffVote, committee: GeneratorSet): Boolean =
    committee.find(_.index.toInt == vote.voterIndex).exists { gi =>
      BlsUtils.verifyBasic(vote.signature.arr, voteMessageOf(vote), gi.blsPublicKey.arr).isRight
    }

  private def stakeOf(indexes: Set[Int], committee: GeneratorSet): BigInt = {
    val balanceByIndex = committee.iterator.map(g => g.index.toInt -> g.balance).toMap
    indexes.foldLeft(BigInt(0))((acc, i) => acc + balanceByIndex.getOrElse(i, 0L))
  }

  private def totalStake(committee: GeneratorSet): BigInt =
    committee.foldLeft(BigInt(0))(_ + _.balance)

  /** Whether `signerIndexes` reach the ≥ 2/3-of-committed-stake quorum (same rule as feature 25). */
  def hasQuorum(signerIndexes: Iterable[Int], committee: GeneratorSet): Boolean =
    FinalizationVoting.isFinalized(stakeOf(signerIndexes.toSet, committee), totalStake(committee))

  /** Build a QC from votes that MUST all target the same (view, phase, blockId, blockHeight).
    * Rejects mixed targets, unknown/invalid signatures, and vote sets below the 2/3 stake quorum;
    * otherwise aggregates the BLS signatures into a single QC. Votes are de-duplicated per voter.
    */
  def formQC(votes: Seq[HotStuffVote], committee: GeneratorSet): Either[String, QuorumCertificate] =
    votes match {
      case Seq()     => Left("no votes")
      case head +: _ =>
        val sameTarget =
          votes.forall(v =>
            v.view == head.view && v.phase == head.phase && v.blockId == head.blockId && v.blockHeight == head.blockHeight
              && v.committeeEpoch == head.committeeEpoch
          )
        if (!sameTarget) Left("votes target different (view, phase, block, committeeEpoch)")
        else {
          val distinct = votes.groupBy(_.voterIndex).values.map(_.head).toSeq
          val invalid  = distinct.filterNot(v => verifyVote(v, committee))
          if (invalid.nonEmpty) Left(s"invalid vote(s) from voter index(es): ${invalid.map(_.voterIndex).sorted.mkString(",")}")
          else {
            val signerIndexes = distinct.map(_.voterIndex).sorted
            if (!hasQuorum(signerIndexes, committee)) Left("signing stake below 2/3 quorum")
            else
              BlsUtils.aggSig(distinct.map(_.signature.arr)).map { aggregatedSignature =>
                QuorumCertificate(
                  head.view,
                  head.phase,
                  head.blockId,
                  head.blockHeight,
                  signerIndexes,
                  ByteStr(aggregatedSignature),
                  head.committeeEpoch
                )
              }
          }
        }
    }

  /** Verify a QC: every signer must be a committee member, the signer set must reach the 2/3 stake
    * quorum, and the aggregated BLS signature must verify against the signers' public keys over the
    * canonical vote message (which now includes `qc.committeeEpoch` — see `voteMessage`'s doc).
    *
    * NOTE: this checks the QC is INTERNALLY self-consistent (the signatures genuinely correspond to
    * `committee` under the epoch `qc` itself claims) — it does NOT by itself decide whether
    * `qc.committeeEpoch` is the epoch a replica SHOULD currently be accepting; that transition-gating
    * decision is `acceptableCommitteeEpoch` above, applied by the caller (`HotStuffEngine.onQC`/
    * `onProposal`) which alone knows what epoch it currently believes is active.
    */
  def verifyQC(qc: QuorumCertificate, committee: GeneratorSet): Either[String, Unit] = {
    val byIndex   = committee.iterator.map(g => g.index.toInt -> g).toMap
    val signerOpt = qc.signerIndexes.map(byIndex.get)
    if (signerOpt.exists(_.isEmpty)) Left("QC references unknown committee member")
    else if (!hasQuorum(qc.signerIndexes, committee)) Left("QC signing stake below 2/3 quorum")
    else
      BlsUtils.verifyAgg(
        qc.aggregatedSignature.arr,
        voteMessage(qc.view, qc.phase, qc.blockId, qc.blockHeight.toInt, qc.committeeEpoch),
        signerOpt.flatten.map(_.blsPublicKey.arr)
      )
  }
}
