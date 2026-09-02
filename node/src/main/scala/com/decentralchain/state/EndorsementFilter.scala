package com.decentralchain.state

import com.decentralchain.account.Address
import com.decentralchain.block.Block.BlockId
import com.decentralchain.crypto.bls.BlsPublicKey
import com.decentralchain.state.EndorsementFilter.SimulationResult
import com.decentralchain.state.Height

import scala.collection.mutable

/** @param normalizedGeneratorSet All, including conflict. Zero balance means it is not enough for mining and endorsing
  * @param cryptoV2
  *   Feature-30 era for THIS voting round (task 6), set once by `BlockEndorser` from the same
  *   `votingHeight` it uses to sign -- the block the endorsement targets, not a bare live-tip read.
  *   `EndorsementStorage.verifySig` reads it from here so the signer and the p2p gossip verifier
  *   share one era for a given round instead of two independent tip reads that could straddle the
  *   activation boundary. Defaulted to `false` (legacy) only so pre-existing test call sites that
  *   don't exercise the v2 path keep compiling unchanged.
  */
case class EndorsementFilter(
    maxValidEndorsers: Int,
    miner: GeneratorIndex,
    isMiner: Boolean,
    finalizedId: BlockId,
    finalizedHeight: Height,
    endorsedId: BlockId,
    normalizedGeneratorSet: IndexedSeq[(Address, BlsPublicKey, Long)],
    conflict: Set[GeneratorIndex],
    cryptoV2: Boolean = false
) {
  private val minerBalance = normalizedGeneratorSet.lift(miner.toInt).fold(0L)(_._3)
  private val totalBalance = normalizedGeneratorSet.foldLeft(BigInt(0L)) { case (r, (_, _, b)) => r + b } -
    conflict.view.map(i => normalizedGeneratorSet(i.toInt)._3).sum

  override def toString: String = {
    val endorsersStr = normalizedGeneratorSet.view.map { case (addr, _, b) => s"$addr -> $b" }.mkString(", ")
    s"EndorsementFilter(m=$miner, fid=$finalizedId, fh=$finalizedHeight, eid=$endorsedId, e={$endorsersStr})"
  }

  def sameVoting(other: EndorsementFilter): Boolean =
    finalizedId == other.finalizedId && finalizedHeight == other.finalizedHeight && endorsedId == other.endorsedId

  def simulate(validIndexes: Iterable[Int], newConflictIndexes: Set[Int]): SimulationResult = {
    type Item = (idx: GeneratorIndex, blsPk: BlsPublicKey, balance: Long)
    val lifted = normalizedGeneratorSet.lift
    val items  = for {
      i                   <- validIndexes.view
      (_, blsPk, balance) <- lifted(i)
      if balance > 0

      gi = GeneratorIndex(i)
      if !(gi == miner || conflict.contains(gi) || newConflictIndexes.contains(i)) // Miner is included below, ignore conflicting
    } yield (GeneratorIndex(i), blsPk, balance): Item

    val totalBalanceWithoutNewConflict = totalBalance - newConflictIndexes.view.map(normalizedGeneratorSet(_)._3).sum
    val doubledTotalBalance            = totalBalanceWithoutNewConflict * 2

    val richest = mutable.PriorityQueue.empty[Item](using Ordering.by[Item, Long](_.balance))
    richest.addAll(items)

    var endorserIndexes = Vector.empty[GeneratorIndex]
    var endorsedBalance = BigInt(minerBalance)
    var reached         = false
    while (endorserIndexes.size < maxValidEndorsers && richest.nonEmpty && !reached) {
      val x = richest.dequeue()
      endorserIndexes = endorserIndexes.appended(x.idx)
      endorsedBalance += x.balance
      reached = endorsedBalance * 3 >= doubledTotalBalance // Same as endorsedBalance >= totalBalance * 2 / 3, but with precision
    }

    SimulationResult(reached, endorsedBalance, totalBalance, endorserIndexes)
  }
}

object EndorsementFilter {
  case class SimulationResult(
      reachedFinalization: Boolean = false,
      endorsedBalance: BigInt,
      totalBalance: BigInt,
      chosenValid: Seq[GeneratorIndex] = Nil
  )
}
