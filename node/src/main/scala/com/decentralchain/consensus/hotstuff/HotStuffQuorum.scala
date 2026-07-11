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

  /** Canonical bytes a generator signs when voting for `blockId` in a given (`view`, `phase`).
    * MUST be byte-identical on the signing and verifying sides.
    * Layout: view (4, big-endian) ++ phase (1) ++ blockId ++ blockHeight (4, big-endian).
    */
  def voteMessage(view: Int, phase: HotStuffPhase, blockId: BlockId, blockHeight: Int): Array[Byte] =
    Ints.toByteArray(view) ++ Array(phase.value.toByte) ++ blockId.arr ++ Ints.toByteArray(blockHeight)

  private def voteMessageOf(v: HotStuffVote): Array[Byte] =
    voteMessage(v.view, v.phase, v.blockId, v.blockHeight.toInt)

  /** True iff `vote.voterIndex` is a real committee member whose BLS signature over the canonical
    * vote message verifies.
    */
  def verifyVote(vote: HotStuffVote, committee: GeneratorSet): Boolean =
    committee.find(_.index.toInt == vote.voterIndex).exists { gi =>
      BlsUtils.verifyBasic(vote.signature.arr, voteMessageOf(vote), gi.blsPublicKey.arr)
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
          votes.forall(v => v.view == head.view && v.phase == head.phase && v.blockId == head.blockId && v.blockHeight == head.blockHeight)
        if (!sameTarget) Left("votes target different (view, phase, block)")
        else {
          val distinct = votes.groupBy(_.voterIndex).values.map(_.head).toSeq
          val invalid  = distinct.filterNot(v => verifyVote(v, committee))
          if (invalid.nonEmpty) Left(s"invalid vote(s) from voter index(es): ${invalid.map(_.voterIndex).sorted.mkString(",")}")
          else {
            val signerIndexes = distinct.map(_.voterIndex).sorted
            if (!hasQuorum(signerIndexes, committee)) Left("signing stake below 2/3 quorum")
            else {
              val aggregatedSignature = distinct.map(_.signature.arr).reduceLeft(BlsUtils.aggSign)
              Right(QuorumCertificate(head.view, head.phase, head.blockId, head.blockHeight, signerIndexes, ByteStr(aggregatedSignature)))
            }
          }
        }
    }

  /** Verify a QC: every signer must be a committee member, the signer set must reach the 2/3 stake
    * quorum, and the aggregated BLS signature must verify against the signers' public keys over the
    * canonical vote message.
    */
  def verifyQC(qc: QuorumCertificate, committee: GeneratorSet): Either[String, Boolean] = {
    val byIndex   = committee.iterator.map(g => g.index.toInt -> g).toMap
    val signerOpt = qc.signerIndexes.map(byIndex.get)
    if (signerOpt.exists(_.isEmpty)) Left("QC references unknown committee member")
    else if (!hasQuorum(qc.signerIndexes, committee)) Left("QC signing stake below 2/3 quorum")
    else
      BlsUtils.verifyAgg(
        qc.aggregatedSignature.arr,
        voteMessage(qc.view, qc.phase, qc.blockId, qc.blockHeight.toInt),
        signerOpt.flatten.map(_.blsPublicKey.arr)
      )
  }
}
