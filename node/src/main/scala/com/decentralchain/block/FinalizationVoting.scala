package com.decentralchain.block

import com.decentralchain.consensus.hotstuff.HotStuffEquivocationProof
import com.decentralchain.crypto.bls.BlsSignature
import com.decentralchain.state.{GeneratorIndex, Height}
import com.decentralchain.transaction.TxValidationError.GenericError

/** @param aggregatedEndorsement Empty if there is no valid endorsement (except miner's one)
  */
case class FinalizationVoting(
    valid: Seq[GeneratorIndex],
    finalizedHeight: Height,
    aggregatedEndorsement: Option[BlsSignature],
    conflict: Seq[BlockEndorsement],
    hotstuffConflicts: Seq[HotStuffEquivocationProof] = Seq.empty
) {
  def withValid(endorserIdxs: Iterable[GeneratorIndex], endorserSigs: Iterable[BlsSignature]): Either[GenericError, FinalizationVoting] =
    BlsSignature.agg(Iterable.concat(aggregatedEndorsement, endorserSigs)).map { agg =>
      copy(
        valid = valid ++ endorserIdxs,
        aggregatedEndorsement = Some(agg)
      )
    }

  def nonEmpty: Boolean = valid.nonEmpty || conflict.nonEmpty || hotstuffConflicts.nonEmpty

  /** Union of both conflict sources as generator indexes, T0 endorsement conflicts first (this ordering is
    * persisted, see Keys.conflictGenerators). Safe to wrap hotstuffConflicts' raw voterIndex in GeneratorIndex.apply
    * without a bounds check: PBHotStuffEquivocationProofs.vanilla rejects any wire proof with voterIndex < 0 at
    * decode time, so every HotStuffEquivocationProof reachable here already carries a non-negative voterIndex.
    */
  def allConflictGeneratorIndexes: Seq[GeneratorIndex] =
    conflict.map(_.endorserIndex) ++ hotstuffConflicts.map(p => GeneratorIndex(p.voterIndex))

  override def toString: String =
    s"Voting(v=[${valid.mkString(",")}], h=$finalizedHeight, c=[${conflict.mkString(", ")}], " +
      s"hsc=[${hotstuffConflicts.mkString(", ")}], s=$aggregatedEndorsement)"
}

object FinalizationVoting {
  def combine(old: Option[FinalizationVoting], recent: Option[FinalizationVoting]): Option[FinalizationVoting] =
    (old, recent) match {
      case (r, None)                 => r
      case (None, r)                 => r
      case (Some(old), Some(recent)) => Some(FinalizationVoting.combine(old, recent))
    }

  def combine(old: FinalizationVoting, recent: FinalizationVoting): FinalizationVoting =
    recent.copy(conflict = old.conflict ++ recent.conflict, hotstuffConflicts = old.hotstuffConflicts ++ recent.hotstuffConflicts)

  def isFinalized(endorsedBalance: BigInt, totalBalance: BigInt): Boolean = {
    // Same as: endorsedBalance >= totalBalance * 2/3
    // But solves a fraction issue:
    //  endorsed=7, total=11, required=7.(3), 7 < 7.(3) - not finalized with BigDecimal, finalized with BigInt (drops fraction part)
    //  endorsed * 3=21, total * 2=22, 21 < 22 - not finalized
    endorsedBalance * 3 >= totalBalance * 2
  }
}
