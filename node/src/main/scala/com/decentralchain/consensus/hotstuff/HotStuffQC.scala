package com.decentralchain.consensus.hotstuff

import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.state.{GeneratorSet, Height}
import com.decentralchain.transaction.TxValidationError.GenericError

/** Quorum Certificate: an aggregated BLS signature over a (blockId, height, round) tuple
  * from ≥2/3 of the committed validator set's balance weight.
  */
final case class HotStuffQC(
    blockId: BlockId,
    height: Height,
    round: HotStuffRound,
    signerIndices: Seq[Int],
    aggregatedSignature: BlsSignature
) {
  def verify(generatorSet: GeneratorSet): Either[GenericError, Unit] = {
    val signers = signerIndices.flatMap(i => generatorSet.find(_.index.toInt == i))
    if (signers.size != signerIndices.size) {
      val unknown = signerIndices.filterNot(i => generatorSet.exists(_.index.toInt == i))
      Left(GenericError(s"HotStuff QC references unknown validator indices: ${unknown.mkString(",")}"))
    } else {
      val msg = HotStuffVote.signingMessage(blockId, height, round)
      aggregatedSignature
        .verifyAgg(msg, signers.map(_.blsPublicKey))
        .left
        .map(GenericError(_))
    }
  }

  def meetsThreshold(generatorSet: GeneratorSet): Boolean = {
    val total    = generatorSet.map(_.balance).sum
    val endorsed = generatorSet.filter(g => signerIndices.contains(g.index.toInt)).map(_.balance).sum
    endorsed * 3L >= total * 2L
  }
}
