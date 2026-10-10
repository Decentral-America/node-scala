package com.decentralchain.network

import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.diffs.TransactionDiffer.TransactionValidationError
import com.decentralchain.transaction.Transaction
import com.decentralchain.transaction.TxValidationError.AlreadyInTheState
import com.decentralchain.transaction.smart.script.trace.TracedResult
import com.decentralchain.utils.ScorexLogging
import io.netty.channel.Channel

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

/** Holds network-received transactions that failed validation and retries them once the chain tip
  * moves, in the spirit of Bitcoin's orphan-transaction pool.
  *
  * A transaction often reaches a peer before the microblock holding the transaction it depends on
  * (transactions are pushed, microblocks are announced and then pulled), so the peer rejects it
  * against state that is about to change. The sender never re-sends it inside
  * `received-txs-cache-timeout`, so before this the peer simply never had it, and only nodes that
  * accepted it could ever mine it (live testnet 2026-10-10: dependent transactions sat in main's
  * pool for 58-356s, until main itself next generated a block).
  *
  * Bounded (`capacity` entries, `ttl` each) and retried only when the tip has changed since the last
  * pass, so the extra validation work is at most `capacity` per appended block or microblock.
  */
final class PendingTransactionRetry(
    capacity: Int,
    ttl: FiniteDuration,
    tip: () => Option[ByteStr],
    now: () => Long = () => System.currentTimeMillis()
) extends ScorexLogging {
  private final case class Entry(tx: Transaction, source: Option[Channel], expiresAt: Long)

  private val entries           = new ConcurrentHashMap[ByteStr, Entry]()
  @volatile private var lastTip = Option.empty[ByteStr]

  def size: Int = entries.size()

  /** Holds `tx` for a retry unless the error can never resolve by the tip moving. */
  def hold(tx: Transaction, source: Option[Channel], error: ValidationError): Unit =
    if (PendingTransactionRetry.retryable(error) && entries.size() < capacity)
      entries.putIfAbsent(tx.id(), Entry(tx, source, now() + ttl.toMillis))

  /** Re-validates held transactions if the tip moved; broadcasts and releases the ones now accepted. */
  def retry(putIfNew: Transaction => TracedResult[ValidationError, Boolean], broadcast: (Transaction, Option[Channel]) => Unit): Unit =
    if (!entries.isEmpty) {
      val currentTip = tip()
      if (currentTip != lastTip) {
        lastTip = currentTip
        val t = now()
        entries.forEach { (id, e) =>
          if (e.expiresAt < t) entries.remove(id)
          else
            putIfNew(e.tx).resultE match {
              case Right(isNew) =>
                entries.remove(id)
                if (isNew) {
                  log.debug(s"Relaying ${e.tx.id()}: accepted once the tip moved")
                  broadcast(e.tx, e.source)
                }
              case Left(error) if !PendingTransactionRetry.retryable(error) => entries.remove(id)
              case Left(_)                                                  => ()
            }
        }
      }
    }
}

object PendingTransactionRetry {
  def retryable(error: ValidationError): Boolean = error match {
    case TransactionValidationError(cause, _) => retryable(cause)
    case _: AlreadyInTheState                 => false
    case _                                    => true
  }
}
