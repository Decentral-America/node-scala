package com.decentralchain.consensus.nxt

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.TransactionsOrdering
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.Asset
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.transfer.*

import java.util.concurrent.ThreadLocalRandom

class TransactionsOrderingSpecification extends PropSpec {

  private val kp: KeyPair = KeyPair(ByteStr(new Array[Byte](32)))
  property("TransactionsOrdering.InBlock should sort correctly") {
    val correctSeq = Seq(
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          125L,
          ByteStr.empty,
          1
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          124L,
          ByteStr.empty,
          2
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          124L,
          ByteStr.empty,
          1
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          ByteStr.empty,
          2
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          ByteStr.empty,
          1
        )
        .explicitGet()
    )

    val sorted = new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock)

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort correctly") {
    val correctSeq = Seq(
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          124L,
          ByteStr.empty,
          1
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          123L,
          ByteStr.empty,
          1
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          123L,
          ByteStr.empty,
          2
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          ByteStr.empty,
          1
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Asset.fromCompatId(Some(ByteStr.empty)),
          124L,
          ByteStr.empty,
          2
        )
        .explicitGet()
    )

    val sorted = new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set.empty))

    sorted shouldBe correctSeq
  }

  property("TransactionsOrdering.InBlock should sort txs by decreasing block timestamp") {
    val correctSeq = Seq(
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          1,
          ByteStr.empty,
          124L
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          kp,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          1,
          ByteStr.empty,
          123L
        )
        .explicitGet()
    )

    new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InBlock) shouldBe correctSeq
  }

  property("TransactionsOrdering.InUTXPool should sort txs by ascending block timestamp taking into consideration whitelisted senders") {
    val whitelisted = KeyPair(Array.fill(32)(1: Byte))
    val correctSeq = Seq(
      TransferTransaction
        .selfSigned(
          1.toByte,
          whitelisted,
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          2,
          ByteStr.empty,
          123L
        )
        .explicitGet(),
      TransferTransaction
        .selfSigned(
          1.toByte,
          KeyPair(Array.fill(32)(0: Byte)),
          Address.fromString("3MydsP4UeQdGwBq7yDbMvf9MzfB2pxFoUKU").explicitGet(),
          Dcc,
          100000,
          Dcc,
          2,
          ByteStr.empty,
          124L
        )
        .explicitGet()
    )
    new scala.util.Random(ThreadLocalRandom.current()).shuffle(correctSeq).sorted(using TransactionsOrdering.InUTXPool(Set(whitelisted.toAddress.toString))) shouldBe correctSeq
  }
}
