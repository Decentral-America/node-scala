package com.decentralchain.transaction

import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.test.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.serialization.impl.TransferTxSerializer
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV1Specification extends PropSpec {

  property("Transfer serialization roundtrip") {
    forAll(transferV1Gen) { (transfer: TransferTransaction) =>
      val recovered = TransferTransaction.parseBytes(transfer.bytes()).get

      recovered.sender shouldEqual transfer.sender
      recovered.assetId shouldBe transfer.assetId
      recovered.feeAssetId shouldBe transfer.feeAssetId
      recovered.timestamp shouldEqual transfer.timestamp
      recovered.amount shouldEqual transfer.amount
      recovered.fee shouldEqual transfer.fee
      recovered.recipient shouldEqual transfer.recipient

      recovered.bytes() shouldEqual transfer.bytes()
    }
  }

  property("Transfer binary parse roundtrip") {
    val tx = TxHelpers.transfer(version = TxVersion.V1)
    val parsed = TransferTxSerializer.parseBytes(tx.bytes()).get
    parsed.json() shouldBe tx.json()
  }

  property("Transfer serialization from TypedTransaction") {
    forAll(transferV1Gen) { (tx: TransferTransaction) =>
      val recovered = TransactionParsers.parseBytes(tx.bytes()).get
      recovered.bytes() shouldEqual tx.bytes()
    }
  }

  property("JSON format validation") {
    val js = Json.parse("""{
                        "type": 4,
                        "id": "9h8ZFor6z2dEBYs6LJDMV5kkrwUpwjCQyQZqnYppoXem",
                        "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                        "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                        "fee": 100000,
                        "timestamp": 1526552510868,
                        "signature": "eaV1i3hEiXyYQd6DQY7EnPg9XzpAvB9VA3bnpin2qJe4G36GZXaGnYKCgSf9xiQ61DcAwcBFzjSXh6FwCgazzFz",
                        "proofs": ["eaV1i3hEiXyYQd6DQY7EnPg9XzpAvB9VA3bnpin2qJe4G36GZXaGnYKCgSf9xiQ61DcAwcBFzjSXh6FwCgazzFz"],
                        "version": 1,
                        "recipient": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                        "assetId": null,
                        "feeAsset":null,
                        "feeAssetId":null,
                        "amount": 1900000,
                        "attachment": "4t2Xazb2SX"
                        }
    """)

    val recipient = Address.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet()
    val tx = TransferTransaction(
      1.toByte,
      PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
      recipient,
      Dcc,
      TxPositiveAmount.unsafeFrom(1900000),
      Dcc,
      TxPositiveAmount.unsafeFrom(100000),
      ByteStr.decodeBase58("4t2Xazb2SX").get,
      1526552510868L,
      Proofs(Seq(ByteStr.decodeBase58("eaV1i3hEiXyYQd6DQY7EnPg9XzpAvB9VA3bnpin2qJe4G36GZXaGnYKCgSf9xiQ61DcAwcBFzjSXh6FwCgazzFz").get)),
      recipient.chainId
    )

    tx.json() shouldEqual js
  }

  property("negative") {
    for {
      (_, sender, recipient, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction.selfSigned(1.toByte, sender, recipient, Dcc, amount, Dcc, feeAmount, attachment, timestamp) should produce(
      "insufficient fee"
    )
  }
}
