package com.decentralchain.transaction

import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.serialization.impl.TransferTxSerializer
import com.decentralchain.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV2Specification extends PropSpec {

  property("VersionedTransferTransactionSpecification serialization roundtrip") {
    forAll(transferV2Gen) { (tx: TransferTransaction) =>
      val recovered = TransferTransaction.parseBytes(tx.bytes()).get
      assertTxs(recovered, tx)
    }
  }

  property("TransferV2 decode pre-encoded bytes") {
    // Regenerated for this chain's network id: the original historical bytes embedded a recipient
    // address signed for a different (now-invalid) network id, so decoding threw InvalidAddress before
    // reaching this comparison. Freshly built + signed via TxHelpers.transfer (real keys), captured
    // bytes()/json() verbatim.
    val bytes = Base58.decode(
      "15mTGj6k3JpYPAJ4BtVqjmybyLNpicMzUtm6FpPWyTBTVcHbSVjhtHTuEB82tTK27kezCUG9ZAptqAED9jZSPnQLK8ryFix2nb4EbifPk3uXeQdP4ihunCNGZCbarfppZkfTy39ZQfG72fD33QEJ3cuhEpDXAVGpdE4jtuxftypLU6cfrX6KhNzGGfh2RSFgLxeCGR9NGJ3WUiCJMhTz7S3"
    )
    val json = Json.parse(
      """{
        |  "senderPublicKey" : "Zmi5prMF9vQMXzGvRpS57s37BKtug3HdmKjSdDZU5Bw",
        |  "amount" : 80901858834201,
        |  "fee" : 1000000,
        |  "type" : 4,
        |  "version" : 2,
        |  "attachment" : "",
        |  "sender" : "3DkaXDdfq1wZaxr5ecz5yvi5Rrn2rCdeWUh",
        |  "feeAssetId" : null,
        |  "proofs" : [ "5NuSowPewWzJsHxE2NUc7CyXBDEoLSGVRVwPv5CuSchhM6DBrmcTxp55jzG48jgUhNZzqR4EyZK1Hod7c7oPsdCF" ],
        |  "assetId" : null,
        |  "recipient" : "3DjMEbfgAyjasG2cQfYWqHYCwByFPx43xu5",
        |  "feeAsset" : null,
        |  "id" : "2kBzPGcHx1ByQkpE3LozaQp9dPMBLE1pRMFvwwgc3nHZ",
        |  "timestamp" : 1526641218066
        |}
        |""".stripMargin
    )

    val tx = TransferTxSerializer.parseBytes(bytes)
    tx.get.json() shouldBe json
  }

  property("VersionedTransferTransactionSpecification serialization from TypedTransaction") {
    forAll(transferV2Gen) { (tx: TransferTransaction) =>
      val recovered = TransactionParsers.parseBytes(tx.bytes()).get
      assertTxs(recovered.asInstanceOf[TransferTransaction], tx)
    }
  }

  property("VersionedTransferTransactionSpecification id doesn't depend on proof") {
    forAll(accountGen, accountGen, proofsGen, proofsGen, attachmentGen) { case (_, acc2, proofs1, proofs2, attachment) =>
      val tx1 = TransferTransaction(
        2.toByte,
        acc2.publicKey,
        acc2.toAddress,
        Dcc,
        TxPositiveAmount.unsafeFrom(1),
        Dcc,
        TxPositiveAmount.unsafeFrom(1),
        attachment,
        1,
        proofs1,
        acc2.toAddress.chainId
      )
      val tx2 = TransferTransaction(
        2.toByte,
        acc2.publicKey,
        acc2.toAddress,
        Dcc,
        TxPositiveAmount.unsafeFrom(1),
        Dcc,
        TxPositiveAmount.unsafeFrom(1),
        attachment,
        1,
        proofs2,
        acc2.toAddress.chainId
      )
      tx1.id() shouldBe tx2.id()
    }
  }

  private def assertTxs(first: TransferTransaction, second: TransferTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.timestamp shouldEqual second.timestamp
    first.fee shouldEqual second.fee
    first.amount shouldEqual second.amount
    first.recipient shouldEqual second.recipient
    first.version shouldEqual second.version
    first.assetId shouldEqual second.assetId
    first.feeAssetId shouldEqual second.feeAssetId
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  property("JSON format validation") {
    val js = Json.parse("""{
                       "type": 4,
                       "id": "pFsbBUcp1JFrS7ZBPyEZojjB7y6dui8qMvgc4eH5Edf",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "timestamp": 1526641218066,
                       "proofs": [
                       "4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi"
                       ],
                       "version": 2,
                       "recipient": "3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH",
                       "assetId": null,
                       "feeAsset": null,
                       "feeAssetId":null,
                       "amount": 100000000,
                       "attachment": "4t2Xazb2SX"}
    """)

    val recipient = Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet()
    val tx = TransferTransaction(
      2.toByte,
      PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
      Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet(),
      Dcc,
      TxPositiveAmount.unsafeFrom(100000000),
      Dcc,
      TxPositiveAmount.unsafeFrom(100000000),
      ByteStr.decodeBase58("4t2Xazb2SX").get,
      1526641218066L,
      Proofs(Seq(ByteStr.decodeBase58("4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi").get)),
      recipient.chainId
    )

    tx.json() shouldEqual js
  }
}
