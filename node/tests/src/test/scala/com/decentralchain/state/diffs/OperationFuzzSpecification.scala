package com.decentralchain.state.diffs

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.test.{FreeSpec, NumericExt}
import com.decentralchain.transaction.TxHelpers

import scala.util.Random

/** Seeded "Operation" fuzzer over a small closed pool of accounts: applies a sequence of randomly
  * generated transfers between pool accounts to one evolving blockchain state (via `Domain`), checking
  * per-account balance conservation after every accepted block and no-op after every rejected one.
  * Modeled on the Cosmos SDK simulation framework's "Operations" pattern (random Msg sequences +
  * invariant checks), scoped down to transfers first — see Task 2 for broader operation types.
  */
class OperationFuzzSpecification extends FreeSpec with WithDomain {
  private val PoolSize       = 5
  private val OperationCount = 200
  private val InitialBalance = 1000.dcc
  private val TransferFee    = 100000L // min fee for Transfer: FeeConstants(Transfer) = 1 * FeeUnit (100000)

  // Indices start at 1, not 0: `domain.appendBlockE` mines every block with `TxHelpers.defaultSigner`
  // (== `TxHelpers.signer(0)`) as the block generator. If that identity were inside the pool, the
  // miner's fee reward would land back on a tracked account whenever it took part in a transfer,
  // silently breaking the conservation invariant below for reasons having nothing to do with
  // transaction processing. Keeping the pool disjoint from the miner avoids that confound.
  private val pool = (1 to PoolSize).map(TxHelpers.signer)

  private def runFuzzRound(seed: Long): Unit = {
    val rnd = new Random(seed)
    withDomain(balances = pool.map(kp => AddrWithBalance(kp.toAddress, InitialBalance))) { domain =>
      (1 to OperationCount).foreach { step =>
        val from   = pool(rnd.nextInt(PoolSize))
        val to     = pool(rnd.nextInt(PoolSize))
        // amounts drawn across the full initial-balance range (as Long — InitialBalance vastly
        // exceeds Int range once expressed in the smallest unit) so overdrafts are plausible; the
        // lower bound is 1, since a 0-amount transfer is rejected at construction (NonPositiveAmount)
        // rather than yielding a Left we could assert against.
        val amount = rnd.between(1L, InitialBalance)
        val tx     = TxHelpers.transfer(from, to.toAddress, amount, fee = TransferFee)

        val beforeSender    = domain.balance(from.toAddress)
        val beforeRecipient = domain.balance(to.toAddress)

        val result = domain.appendBlockE(tx)

        val afterSender    = domain.balance(from.toAddress)
        val afterRecipient = domain.balance(to.toAddress)

        withClue(s"seed=$seed step=$step from=${from.toAddress} to=${to.toAddress} amount=$amount result=$result: ") {
          result match {
            case Right(_) if from != to =>
              afterSender shouldBe (beforeSender - amount - TransferFee)
              afterRecipient shouldBe (beforeRecipient + amount)
            case Right(_) =>
              // self-transfer: sender pays only the fee, amount cancels out
              afterSender shouldBe (beforeSender - TransferFee)
            case Left(_) =>
              // rejected (e.g. insufficient balance) — must be all-or-nothing, no partial application
              afterSender shouldBe beforeSender
              afterRecipient shouldBe beforeRecipient
          }
        }
      }
    }
  }

  "a pool of 5 accounts under 200 random transfers per seed" - {
    (0 until 50).foreach { seed =>
      s"seed=$seed: every accepted transfer conserves balance exactly, every rejection is a no-op" in runFuzzRound(seed.toLong)
    }
  }
}
