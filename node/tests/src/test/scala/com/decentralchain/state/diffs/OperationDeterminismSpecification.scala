package com.decentralchain.state.diffs

import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.tags.SlowTest
import com.decentralchain.test.{FreeSpec, NumericExt}
import com.decentralchain.transaction.TxHelpers

import scala.util.Random

/** Applies the SAME seeded operation sequence to two independently-genesised `Domain` instances and
  * checks their per-account balance state matches after every single step. A divergence here would be
  * exactly the "two nodes computed different state from the same transaction sequence" failure class
  * behind the open Committed-Generators StateHash Finding, so this is a targeted regression check for
  * that finding rather than a general fuzzer.
  */
class OperationDeterminismSpecification extends FreeSpec with WithDomain {
  private val PoolSize = 5
  // Overridable at runtime via -Ddcc.fuzz.seedCount — see OperationFuzzSpecification for rationale.
  private val SeedCount      = sys.props.get("dcc.fuzz.seedCount").map(_.toInt).getOrElse(50)
  private val OperationCount = 200
  private val InitialBalance = 1000.dcc
  private val TransferFee    = 100000L // min fee for Transfer: FeeConstants(Transfer) = 1 * FeeUnit (100000)

  // Indices start at 1, not 0: `domain.appendBlockE` mines every block with `TxHelpers.defaultSigner`
  // (== `TxHelpers.signer(0)`) as the block generator. If that identity were inside the pool, the
  // miner's fee reward would land back on a tracked account whenever it took part in a transfer,
  // silently breaking the cross-instance comparison below for reasons having nothing to do with
  // transaction processing (mirrors the fix already applied in OperationFuzzSpecification).
  private val pool = (1 to PoolSize).map(TxHelpers.signer)

  private def genesisBalances = pool.map(kp => AddrWithBalance(kp.toAddress, InitialBalance))

  private def runDeterminismRound(seed: Long): Unit = {
    val rnd = new Random(seed)

    // Build the identical operation sequence - and the identical transactions themselves, constructed
    // exactly once each - up front. Reusing the very same `Transaction` instance against both domains
    // below means any divergence found can only come from how each `Domain` independently processes an
    // identical input, never from the two sides being fed subtly different transactions (e.g. via
    // per-domain timestamp drift).
    val ops = (1 to OperationCount).map { step =>
      val from = pool(rnd.nextInt(PoolSize))
      val to   = pool(rnd.nextInt(PoolSize)) // self-transfer allowed, mirrors OperationFuzzSpecification
      // amounts drawn across the full initial-balance range (as Long - InitialBalance vastly exceeds
      // Int range once expressed in the smallest unit) so overdrafts are plausible; the lower bound is
      // 1, since a 0-amount transfer is rejected at construction (NonPositiveAmount) rather than
      // yielding a Left we could assert against.
      val amount = rnd.between(1L, InitialBalance)
      val tx     = TxHelpers.transfer(from, to.toAddress, amount, fee = TransferFee)
      (step, tx)
    }

    withDomain(balances = genesisBalances) { domainA =>
      withDomain(balances = genesisBalances) { domainB =>
        ops.foreach { case (step, tx) =>
          val resultA = domainA.appendBlockE(tx)
          val resultB = domainB.appendBlockE(tx)

          withClue(
            s"seed=$seed step=$step tx=${tx.id()}: block-acceptance diverged between the two instances (A=$resultA, B=$resultB): "
          ) {
            resultA.isRight shouldBe resultB.isRight
          }

          pool.foreach { account =>
            withClue(
              s"seed=$seed step=$step tx=${tx.id()} account=${account.toAddress}: balance diverged between the two instances: "
            ) {
              domainA.balance(account.toAddress) shouldBe domainB.balance(account.toAddress)
            }
          }
        }
      }
    }
  }

  "two independently-genesised Domain instances fed the identical seeded operation sequence" - {
    (0 until SeedCount).foreach { seed =>
      s"seed=$seed: must reach identical balance state at every step" taggedAs SlowTest in runDeterminismRound(seed.toLong)
    }
  }
}
