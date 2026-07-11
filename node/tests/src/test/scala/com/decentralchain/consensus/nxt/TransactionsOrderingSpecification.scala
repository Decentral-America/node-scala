package com.decentralchain.consensus.nxt

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.TransactionsOrdering
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.{Asset, TxHelpers}
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.transfer.*

import java.util.concurrent.ThreadLocalRandom

class TransactionsOrderingSpecification extends PropSpec {

  private val kp: KeyPair = KeyPair(ByteStr(new Array[Byte](32)))
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        125L,
        Dcc,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Dcc,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Dcc,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      )
    )

    val sorted = new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort correctly") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Dcc,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        123L,
        Dcc,
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        123L,
        Dcc,
        ByteStr.empty,
        2
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        1
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        124L,
        Asset.fromCompatId(Some(ByteStr.empty)),
        ByteStr.empty,
        2
      )
    )

    val sorted = new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        1,
        Dcc,
        ByteStr.empty,
        124L
      ),
      TxHelpers.transfer(
        kp,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        1,
        Dcc,
        ByteStr.empty,
        123L
      )
    )

    new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock) shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort txs by ascending block timestamp taking into consideration whitelisted senders") {
    val whitelisted = KeyPair(Array.fill(32)(1: Byte))
    val correctSeq  = Seq(
      TxHelpers.transfer(
        whitelisted,
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        2,
        Dcc,
        ByteStr.empty,
        123L
      ),
      TxHelpers.transfer(
        KeyPair(Array.fill(32)(0: Byte)),
        Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet(),
        100000,
        Dcc,
        2,
        Dcc,
        ByteStr.empty,
        124L
      )
    )
    new scala.util.Random(ThreadLocalRandom.current())
      .shuffle(correctSeq)
      .sorted(using TransactionsOrdering.InUTXPool(Set(whitelisted.toAddress.toString))) shouldBe correctSeq
  }
}
