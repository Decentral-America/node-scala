package com.decentralchain.transaction

import cats.kernel.Monoid
import com.google.protobuf.ByteString
import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.directives.DirectiveSet
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.ContractScript
import com.decentralchain.lang.v1.compiler
import com.decentralchain.lang.v1.evaluator.ctx.impl.dcc.DccContext
import com.decentralchain.lang.v1.evaluator.ctx.impl.{CryptoContext, PureContext}
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.lang.v1.traits.Environment
import com.decentralchain.lang.{Global, utils}
import com.decentralchain.state.HistoryTest
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.assets.IssueTransaction
import com.decentralchain.transaction.serialization.impl.IssueTxSerializer
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.{WithNewDBForEachTest, crypto}
import org.scalatest.EitherValues
import play.api.libs.json.Json

class IssueTransactionV2Specification extends PropSpec with WithNewDBForEachTest with HistoryTest with EitherValues {

  property("IssueV2 serialization roundtrip") {
    forAll(issueV2TransactionGen()) { (tx: IssueTransaction) =>
      val recovered = IssueTransaction.parseBytes(tx.bytes()).get

      tx.sender shouldEqual recovered.sender
      tx.timestamp shouldEqual recovered.timestamp
      tx.decimals shouldEqual recovered.decimals
      tx.name shouldEqual recovered.name
      tx.description shouldEqual recovered.description
      tx.script shouldEqual recovered.script
      tx.reissuable shouldEqual recovered.reissuable
      tx.fee shouldEqual recovered.fee
      tx.chainId shouldEqual recovered.chainId
      tx.bytes() shouldEqual recovered.bytes()
    }
  }

  property("IssueV2 binary parse roundtrip") {
    val tx =
      TxHelpers.issue(name = "Gigacoin", description = "Gigacoin", amount = 10000000000L, decimals = 8, reissuable = true, version = TxVersion.V2)
    val parsed = IssueTxSerializer.parseBytes(tx.bytes()).get
    parsed.json() shouldBe tx.json()
    assert(crypto.verify(tx.signature, tx.bodyBytes(), tx.sender), "signature should be valid")
  }

  property("JSON format validation") {
    val js = Json.parse("""{
                       "type": 3,
                       "id": "HzbjDutCH6Exkr6SaM6UjiesLudKhdWgdTG2aLH1abvd",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "feeAssetId": null,
                       "timestamp": 1526287561757,
                       "proofs": [
                       "43TCfWBa6t2o2ggsD4bU9FpvH3kmDbSBWKE1Z6B5i5Ax5wJaGT2zAvBihSbnSS3AikZLcicVWhUk1bQAMWVzTG5g"
                       ],
                       "version": 2,
                       "assetId": "HzbjDutCH6Exkr6SaM6UjiesLudKhdWgdTG2aLH1abvd",
                       "chainId": 63,
                       "name": "Gigacoin",
                       "quantity": 10000000000,
                       "reissuable": true,
                       "decimals": 8,
                       "description": "Gigacoin",
                       "script":null
                       }
    """)

    val tx = IssueTransaction(
      TxVersion.V2,
      PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
      ByteString.copyFromUtf8("Gigacoin"),
      ByteString.copyFromUtf8("Gigacoin"),
      TxPositiveAmount.unsafeFrom(10000000000L),
      TxDecimals.unsafeFrom(8.toByte),
      reissuable = true,
      None,
      TxPositiveAmount.unsafeFrom(100000000),
      1526287561757L,
      Proofs(Seq(ByteStr.decodeBase58("43TCfWBa6t2o2ggsD4bU9FpvH3kmDbSBWKE1Z6B5i5Ax5wJaGT2zAvBihSbnSS3AikZLcicVWhUk1bQAMWVzTG5g").get)),
      AddressScheme.current.chainId
    )

    tx.json() shouldEqual js
  }

  property("Contract script on asset isn't allowed") {
    val contract = {
      val script =
        s"""
           |{-# STDLIB_VERSION 3 #-}
           |{-# CONTENT_TYPE CONTRACT #-}
           |
           |@Verifier(txx)
           |func verify() = {
           |    true
           |}
        """.stripMargin
      Parser.parseContract(script).get.value
    }

    val ctx = {
      utils.functionCosts(V3)
      Monoid
        .combineAll(
          Seq(
            PureContext.build(V3, useNewPowPrecision = true).withEnvironment[Environment],
            CryptoContext.build(Global, V3, fixEcrecover = false).withEnvironment[Environment],
            DccContext.build(
              Global,
              DirectiveSet(V3, Account, Expression).explicitGet(),
              fixBigScriptField = true
            )
          )
        )
    }

    val script = ContractScript(V3, compiler.ContractCompiler(ctx.compilerContext, contract, V3).explicitGet())

    val tx = IssueTransaction.create(
      TxVersion.V2,
      PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
      "Gigacoin",
      "Gigacoin",
      10000000000L,
      8,
      reissuable = true,
      script.toOption,
      100000000,
      1526287561757L,
      Proofs(Seq(ByteStr.decodeBase58("43TCfWBa6t2o2ggsD4bU9FpvH3kmDbSBWKE1Z6B5i5Ax5wJaGT2zAvBihSbnSS3AikZLcicVWhUk1bQAMWVzTG5g").get))
    )

    tx.left.value
  }

  /* property("parses invalid UTF-8 string") {
    forAll(byteArrayGen(16), accountGen) { (bytes, sender) =>
      val tx = IssueTransaction(
        2.toByte,
        sender,
        bytes,
        bytes,
        1,
        1,
        reissuable = false,
        None,
        1000000,
        System.currentTimeMillis()
      ).signWith(sender)

      tx.name.toByteArray shouldBe bytes
      tx.description.toByteArray shouldBe bytes

      val pb     = PBTransactions.protobuf(tx)
      val fromPB = PBTransactions.vanillaUnsafe(pb)
      fromPB shouldBe tx
    }
  } */
}
