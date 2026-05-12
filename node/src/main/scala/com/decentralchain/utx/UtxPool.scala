package com.decentralchain.utx

import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.mining.{MiningConstraint, MultiDimensionalMiningConstraint}
import com.decentralchain.state.StateSnapshot
import com.decentralchain.transaction.*
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.utx.UtxPool.PackStrategy

import scala.concurrent.duration.FiniteDuration

trait UtxForAppender {
  def setPrioritySnapshots(snapshots: Seq[StateSnapshot]): Unit
}

trait UtxPool extends UtxForAppender with AutoCloseable {
  def putIfNew(tx: Transaction, forceValidate: Boolean = false): TracedResult[ValidationError, Boolean]
  def removeAll(txs: Iterable[Transaction]): Unit
  def all: Seq[Transaction]
  def size: Int
  def transactionById(transactionId: ByteStr): Option[Transaction]
  def addAndScheduleCleanup(transactions: Iterable[Transaction]): Unit
  def scheduleCleanup(): Unit
  def packUnconfirmed(
      rest: MultiDimensionalMiningConstraint,
      prevStateHash: Option[ByteStr],
      strategy: PackStrategy = PackStrategy.Unlimited,
      cancelled: () => Boolean = () => false
  ): (Option[Seq[Transaction]], MiningConstraint, Option[ByteStr])
  def resetPriorityPool(): Unit
  def cleanUnconfirmed(): Unit
  def getPriorityPool: Option[UtxPriorityPool]
}

object UtxPool {
  sealed trait PackStrategy
  object PackStrategy {
    case class Limit(time: FiniteDuration)    extends PackStrategy
    case class Estimate(time: FiniteDuration) extends PackStrategy
    case object Unlimited                     extends PackStrategy
  }
}
