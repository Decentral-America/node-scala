package com.decentralchain.network

import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.Height
import com.decentralchain.test.FreeSpec
import com.decentralchain.transaction.{Transaction, TxHelpers}
import com.decentralchain.transaction.TxValidationError.{AlreadyInTheState, GenericError}
import com.decentralchain.transaction.smart.script.trace.TracedResult

import scala.concurrent.duration.*

class PendingTransactionRetrySpec extends FreeSpec {
  private class Fixture(capacity: Int = 10) {
    var tip: Option[ByteStr]            = Some(ByteStr.fill(32)(1))
    var clock: Long                     = 0L
    var accept: Set[ByteStr]            = Set.empty
    var broadcasts: Seq[ByteStr]        = Seq.empty
    var validations: Int                = 0
    val retry                           = new PendingTransactionRetry(capacity, 60.seconds, () => tip, () => clock)
    private val notYet: ValidationError = GenericError("Attempt to transfer unavailable funds")
    def hold(tx: Transaction): Unit     = retry.hold(tx, None, notYet)
    def moveTip(b: Byte): Unit          = tip = Some(ByteStr.fill(32)(b))
    def run(): Unit                     = retry.retry(putIfNew, (tx, _) => broadcasts :+= tx.id())
    private def putIfNew(tx: Transaction): TracedResult[ValidationError, Boolean] = {
      validations += 1
      TracedResult(if (accept(tx.id())) Right(true) else Left(notYet))
    }
  }

  private val txs        = (1 to 3).map(n => TxHelpers.transfer(TxHelpers.signer(n), TxHelpers.secondAddress, 1))
  private def tx(n: Int) = txs(n - 1)

  "holds retryable rejections only, up to capacity" in {
    val f = new Fixture(capacity = 2)
    f.retry.hold(tx(1), None, AlreadyInTheState(tx(1).id(), Height(1)))
    f.retry.size shouldBe 0
    f.hold(tx(1)); f.hold(tx(2)); f.hold(tx(3))
    f.retry.size shouldBe 2
  }

  "does not re-validate while the tip has not moved" in {
    val f = new Fixture
    f.hold(tx(1))
    f.run() // first pass sees a new tip
    val after = f.validations
    f.run(); f.run()
    f.validations shouldBe after
  }

  "relays a held transaction once the tip moves and it is accepted" in {
    val f = new Fixture
    f.hold(tx(1)); f.hold(tx(2))
    f.run()
    f.broadcasts shouldBe empty

    f.accept = Set(tx(1).id()) // its dependency arrived with the next microblock
    f.moveTip(2)
    f.run()
    f.broadcasts shouldBe Seq(tx(1).id())
    f.retry.size shouldBe 1 // tx(2) still waits

    f.moveTip(3); f.run()
    f.broadcasts shouldBe Seq(tx(1).id()) // relayed exactly once
  }

  "drops entries after their ttl" in {
    val f = new Fixture
    f.hold(tx(1))
    f.clock = 61.seconds.toMillis
    f.moveTip(2); f.run()
    f.retry.size shouldBe 0
    f.broadcasts shouldBe empty
  }
}
