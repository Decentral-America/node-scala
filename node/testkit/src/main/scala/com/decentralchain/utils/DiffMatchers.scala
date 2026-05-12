package com.decentralchain.utils

import com.decentralchain.common.state.ByteStr
import com.decentralchain.state.StateSnapshot
import com.decentralchain.state.TxMeta.Status.Succeeded
import org.scalatest.matchers.{MatchResult, Matcher}

trait DiffMatchers {
  def containAppliedTx(transactionId: ByteStr) = new DiffAppliedTxMatcher(transactionId, true)
  def containFailedTx(transactionId: ByteStr)  = new DiffAppliedTxMatcher(transactionId, false)

  class DiffAppliedTxMatcher(transactionId: ByteStr, shouldBeApplied: Boolean) extends Matcher[StateSnapshot] {
    override def apply(snapshot: StateSnapshot): MatchResult = {
      val isApplied = snapshot.transactions.get(transactionId) match {
        case Some(nt) if nt.status == Succeeded => true
        case _                                  => false
      }
      MatchResult(
        shouldBeApplied == isApplied,
        s"$transactionId was not ${if (shouldBeApplied) "applied" else "failed"}: $snapshot",
        s"$transactionId was ${if (shouldBeApplied) "applied" else "failed"}: $snapshot"
      )
    }
  }
}
