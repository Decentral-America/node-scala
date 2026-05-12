package com.decentralchain
import com.decentralchain.block.Block
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.BlockchainUpdaterImpl.BlockApplyResult
import com.decentralchain.state.{Blockchain, StateSnapshot}
import com.decentralchain.transaction.Transaction

package object mining {
  private[mining] def createConstConstraint(maxSize: Long, transactionSize: => Long, description: String) = OneDimensionalMiningConstraint(
    maxSize,
    new com.decentralchain.mining.TxEstimators.Fn {
      override def apply(b: Blockchain, t: Transaction, s: StateSnapshot): Long = transactionSize
      override val minEstimate                                                  = transactionSize
      override val toString: String                                             = s"const($transactionSize)"
    },
    description
  )

  type Appender = Block => Either[ValidationError, BlockApplyResult]
}
