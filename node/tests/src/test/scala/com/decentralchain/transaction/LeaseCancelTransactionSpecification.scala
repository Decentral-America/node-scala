package com.decentralchain.transaction

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.lease.LeaseCancelTransaction
import com.decentralchain.transaction.serialization.impl.LeaseCancelTxSerializer
import com.decentralchain.transaction.TxHelpers
import play.api.libs.json.Json

class LeaseCancelTransactionSpecification extends PropSpec {

  property("Lease cancel serialization roundtrip") {
    forAll(leaseCancelGen) { (tx: LeaseCancelTransaction) =>
      val recovered = LeaseCancelTxSerializer.parseBytes(tx.bytes()).get
      assertTxs(recovered, tx)
    }
  }

  property("Lease cancel binary parse roundtrip") {
    val leaseId = ByteStr(Array.fill(32)(2: Byte))
    val tx      = TxHelpers.leaseCancel(leaseId, version = TxVersion.V2)
    val parsed  = LeaseCancelTxSerializer.parseBytes(tx.bytes()).get
    parsed.json() shouldBe tx.json()
  }

  property("Lease cancel serialization from TypedTransaction") {
    forAll(leaseCancelGen) { (tx: LeaseCancelTransaction) =>
      val recovered = TransactionParsers.parseBytes(tx.bytes()).get
      assertTxs(recovered.asInstanceOf[LeaseCancelTransaction], tx)
    }
  }

  private def assertTxs(first: LeaseCancelTransaction, second: LeaseCancelTransaction): Unit = {
    first.leaseId shouldEqual second.leaseId
    first.fee shouldEqual second.fee
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  property("JSON format validation for LeaseCancelTransactionV1") {
    val js = Json.parse("""{
                       "type": 9,
                       "id": "7hmabbFS8a2z79a29pzZH1s8LHxrsEAnnLjJxNdZ1gGw",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 1000000,
                       "feeAssetId": null,
                       "timestamp": 1526646300260,
                       "signature": "4T76AXcksn2ixhyMNu4m9UyY54M3HDTw5E2HqUsGV4phogs2vpgBcN5oncu4sbW4U3KU197yfHMxrc3kZ7e6zHG3",
                       "proofs": ["4T76AXcksn2ixhyMNu4m9UyY54M3HDTw5E2HqUsGV4phogs2vpgBcN5oncu4sbW4U3KU197yfHMxrc3kZ7e6zHG3"],
                       "version": 1,
                       "leaseId": "EXhjYjy8a1dURbttrGzfcft7cddDnPnoa3vqaBLCTFVY"
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
        1.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        ByteStr.decodeBase58("EXhjYjy8a1dURbttrGzfcft7cddDnPnoa3vqaBLCTFVY").get,
        1000000,
        1526646300260L,
        Proofs(ByteStr.decodeBase58("4T76AXcksn2ixhyMNu4m9UyY54M3HDTw5E2HqUsGV4phogs2vpgBcN5oncu4sbW4U3KU197yfHMxrc3kZ7e6zHG3").get)
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

  property("JSON format validation for LeaseCancelTransactionV2") {
    val js = Json.parse("""{
                        "type": 9,
                        "id": "5NxW2bzREyMqNPaNLDMM8QnUp5RCGeGzoeN2U7eTFqKh",
                        "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                        "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                        "fee": 1000000,
                        "feeAssetId":null,
                        "timestamp": 1526646300260,
                        "proofs": [
                        "3h5SQLbCzaLoTHUeoCjXUHB6qhNUfHZjQQVsWTRAgTGMEdK5aeULMVUfDq63J56kkHJiviYTDT92bLGc8ELrUgvi"
                        ],
                        "version": 2,
                        "leaseId": "DJWkQxRyJNqWhq9qSQpK2D4tsrct6eZbjSv3AH4PSha6",
                        "chainId": 63
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
        2.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        ByteStr.decodeBase58("DJWkQxRyJNqWhq9qSQpK2D4tsrct6eZbjSv3AH4PSha6").get,
        1000000,
        1526646300260L,
        Proofs(Seq(ByteStr.decodeBase58("3h5SQLbCzaLoTHUeoCjXUHB6qhNUfHZjQQVsWTRAgTGMEdK5aeULMVUfDq63J56kkHJiviYTDT92bLGc8ELrUgvi").get))
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

}
