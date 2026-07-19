package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.state.{GeneratorSet, Height}
import com.decentralchain.transaction.TxValidationError.GenericError

/** Accumulates BLS votes for a single (blockId, height, round) triple.
  *
  * Not thread-safe — designed for use inside a single Akka actor turn.
  */
final class HotStuffVoteCollector(
    val blockId: BlockId,
    val height: Height,
    val round: HotStuffRound,
    private val generatorSet: GeneratorSet
) {
  private val votes: scala.collection.mutable.Map[Int, BlsSignature] =
    scala.collection.mutable.Map.empty

  private val totalBalance: BigInt = generatorSet.foldLeft(BigInt(0))((acc, g) => acc + BigInt(g.balance))

  /** Validates and records a vote. Returns the formed QC if ≥2/3 threshold is now met. */
  def add(vote: HotStuffVote): Either[GenericError, Option[HotStuffQC]] = {
    if (vote.blockId != blockId || vote.height != height || vote.round != round)
      return Left(
        GenericError(
          s"Vote (${vote.blockId.trim}, h=${vote.height}, r=${vote.round.name}) does not match expected ($blockId, h=$height, r=${round.name})"
        )
      )

    if (votes.contains(vote.voterIndex))
      return Right(None) // idempotent — ignore duplicate from same validator

    generatorSet.find(_.index.toInt == vote.voterIndex) match {
      case None =>
        Left(GenericError(s"HotStuff vote from unknown generator index ${vote.voterIndex}"))
      case Some(info) =>
        vote.verifySignature(info.blsPublicKey) match {
          case Left(err) => Left(GenericError(s"Invalid BLS signature from index ${vote.voterIndex}: $err"))
          case Right(_) =>
            votes(vote.voterIndex) = vote.signature
            tryFormQC()
        }
    }
  }

  def voteCount: Int = votes.size

  private def endorsedBalance: BigInt =
    generatorSet.foldLeft(BigInt(0))((acc, g) => if (votes.contains(g.index.toInt)) acc + BigInt(g.balance) else acc)

  private def tryFormQC(): Either[GenericError, Option[HotStuffQC]] =
    // BigInt to match HotStuffQC.meetsThreshold / the authoritative T0 path (no *3/*2 Long overflow).
    if (endorsedBalance * 3 < totalBalance * 2)
      Right(None)
    else
      BlsSignature
        .agg(votes.values)
        .map(aggSig => Some(HotStuffQC(blockId, height, round, votes.keys.toSeq.sorted, aggSig)))
}
