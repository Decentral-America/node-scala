package com.decentralchain.state.diffs

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.tags.SlowTest
import com.decentralchain.test.{FreeSpec, NumericExt}
import com.decentralchain.transaction.TxHelpers

import scala.util.Random

/** Seeded "Operation" fuzzer over a small closed pool of accounts: applies a sequence of randomly
  * generated operations (transfers and leases) between pool accounts to one evolving blockchain state
  * (via `Domain`), checking per-account invariants after every accepted block and no-op after every
  * rejected one. Modeled on the Cosmos SDK simulation framework's "Operations" pattern (random Msg
  * sequences + invariant checks).
  */
class OperationFuzzSpecification extends FreeSpec with WithDomain {
  private val PoolSize = 5
  // Overridable at runtime via -Ddcc.fuzz.seedCount so the nightly workflow (which does real
  // per-block RocksDB appends per seed, unlike the push-gated budget) can sweep far more seeds
  // than the 50 baked into the push-gated run without touching this file.
  private val SeedCount      = sys.props.get("dcc.fuzz.seedCount").map(_.toInt).getOrElse(50)
  private val OperationCount = 200
  private val InitialBalance = 1000.dcc
  private val TransferFee    = 100000L // min fee for Transfer: FeeConstants(Transfer) = 1 * FeeUnit (100000)
  private val LeaseFee       = 100000L // min fee for Lease: FeeConstants(Lease) = 1 * FeeUnit (100000), same base fee as Transfer

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
        val fromIdx = rnd.nextInt(PoolSize)
        val from    = pool(fromIdx)
        // amounts drawn across the full initial-balance range (as Long — InitialBalance vastly
        // exceeds Int range once expressed in the smallest unit) so overdrafts are plausible; the
        // lower bound is 1, since a 0-amount transfer/lease is rejected at construction
        // (NonPositiveAmount) rather than yielding a Left we could assert against.
        val amount = rnd.between(1L, InitialBalance)

        if (rnd.nextBoolean()) {
          val to = pool(rnd.nextInt(PoolSize)) // self-transfer is allowed and asserted separately below
          val tx = TxHelpers.transfer(from, to.toAddress, amount, fee = TransferFee)

          val beforeSender    = domain.balance(from.toAddress)
          val beforeRecipient = domain.balance(to.toAddress)

          val result = domain.appendBlockE(tx)

          val afterSender    = domain.balance(from.toAddress)
          val afterRecipient = domain.balance(to.toAddress)

          withClue(s"seed=$seed step=$step transfer from=${from.toAddress} to=${to.toAddress} amount=$amount result=$result: ") {
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
        } else {
          // Unlike self-transfer, self-lease is rejected at *transaction-construction* time —
          // `LeaseTxValidator` enforces `sender != recipient` (`ToSelf`), and `TxHelpers.lease` calls
          // `.explicitGet()` on the result, so a self-lease candidate would throw before we ever reach
          // `appendBlockE` and get a `Left` to assert against. So, mirroring how the amount lower bound
          // above avoids the construction-time `NonPositiveAmount` case, draw a recipient index distinct
          // from `fromIdx` here rather than allowing self-lease to be generated at all.
          val to = pool((fromIdx + 1 + rnd.nextInt(PoolSize - 1)) % PoolSize)
          val tx = TxHelpers.lease(from, to.toAddress, amount, fee = LeaseFee)

          // leasing doesn't move the DCC balance the way a transfer does: the leased amount stays
          // put on the sender's regular balance and is tracked separately as "leased out" (`.out`)
          // on the sender / "leased in" (`.in`) on the recipient (see `DiffsCommon.processLease`,
          // which applies `Portfolio(-fee, LeaseBalance(0, amount))` to the sender and
          // `Portfolio(0, LeaseBalance(amount, 0))` to the recipient) — only the fee leaves the
          // sender's regular balance.
          val beforeSenderBalance    = domain.balance(from.toAddress)
          val beforeSenderLeaseOut   = domain.blockchainUpdater.leaseBalance(from.toAddress).out
          val beforeRecipientLeaseIn = domain.blockchainUpdater.leaseBalance(to.toAddress).in

          val result = domain.appendBlockE(tx)

          val afterSenderBalance    = domain.balance(from.toAddress)
          val afterSenderLeaseOut   = domain.blockchainUpdater.leaseBalance(from.toAddress).out
          val afterRecipientLeaseIn = domain.blockchainUpdater.leaseBalance(to.toAddress).in

          withClue(s"seed=$seed step=$step lease from=${from.toAddress} to=${to.toAddress} amount=$amount result=$result: ") {
            result match {
              case Right(_) =>
                afterSenderBalance shouldBe (beforeSenderBalance - LeaseFee)
                afterSenderLeaseOut shouldBe (beforeSenderLeaseOut + amount)
                afterRecipientLeaseIn shouldBe (beforeRecipientLeaseIn + amount)
              case Left(_) =>
                // rejected (e.g. insufficient balance, over-leasing, duplicate lease id) — no-op
                afterSenderBalance shouldBe beforeSenderBalance
                afterSenderLeaseOut shouldBe beforeSenderLeaseOut
                afterRecipientLeaseIn shouldBe beforeRecipientLeaseIn
            }
          }
        }
      }
    }
  }

  "a pool of 5 accounts under 200 random transfer/lease operations per seed" - {
    (0 until SeedCount).foreach { seed =>
      s"seed=$seed: every accepted operation conserves balance/lease-balance exactly, every rejection is a no-op" taggedAs SlowTest in runFuzzRound(
        seed.toLong
      )
    }
  }
}
