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
    if (signerIndices.distinct.size != signerIndices.size) {
      // Reject duplicate indices outright: not exploitable for a forged threshold (meetsThreshold sums by
      // distinct membership and the aggregate BLS sig still needs the real keys), but it is malformed and
      // wastes work — fail fast rather than aggregate the same key twice.
      Left(GenericError(s"HotStuff QC has duplicate signer indices: ${signerIndices.mkString(",")}"))
    } else if (signers.size != signerIndices.size) {
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
    // BigInt to match the authoritative T0 path (FinalizationVoting.isFinalized) and avoid *3/*2 Long
    // overflow at extreme total supply. Distinct signer set (index membership) so duplicates can't inflate.
    val signerSet = signerIndices.toSet
    val total     = generatorSet.foldLeft(BigInt(0))((acc, g) => acc + BigInt(g.balance))
    val endorsed  = generatorSet.foldLeft(BigInt(0))((acc, g) => if (signerSet.contains(g.index.toInt)) acc + BigInt(g.balance) else acc)
    endorsed * 3 >= total * 2
  }
}
