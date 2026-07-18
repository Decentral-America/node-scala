package com.decentralchain.transaction

import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.serialization.impl.LeaseTxSerializer
import com.decentralchain.transaction.TxHelpers
import play.api.libs.json.Json

class LeaseTransactionSpecification extends PropSpec {

  property("Lease transaction serialization roundtrip") {
    forAll(leaseGen) { (tx: LeaseTransaction) =>
      val recovered = LeaseTxSerializer.parseBytes(tx.bytes()).get
      assertTxs(recovered, tx)
    }
  }

  property("Lease binary parse roundtrip") {
    val tx = TxHelpers.lease(version = TxVersion.V2)
    val parsed = LeaseTxSerializer.parseBytes(tx.bytes()).get
    parsed.json() shouldBe tx.json()
  }

  property("Lease transaction from TransactionParser") {
    forAll(leaseGen) { (tx: LeaseTransaction) =>
      val recovered = TransactionParsers.parseBytes(tx.bytes()).get
      assertTxs(recovered.asInstanceOf[LeaseTransaction], tx)
    }
  }

  private def assertTxs(first: LeaseTransaction, second: LeaseTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.recipient shouldEqual second.recipient
    first.amount shouldEqual second.amount
    first.fee shouldEqual second.fee
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  property("JSON format validation for LeaseTransactionV1") {
    val js = Json.parse("""{
                       "type": 8,
                       "id": "AQ3j5JKAPmQx8gZ4XwGGwcmyK3gBDhQi3c4caZFAg8hy",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 1000000,
                       "feeAssetId": null,
                       "timestamp": 1526646300260,
                       "signature": "iy3TmfbFds7pc9cDDqfjEJhfhVyNtm3GcxoVz8L3kJFvgRPUmiqqKLMeJGYyN12AhaQ6HvE7aF1tFgaAoCCgNJJ",
                       "proofs": ["iy3TmfbFds7pc9cDDqfjEJhfhVyNtm3GcxoVz8L3kJFvgRPUmiqqKLMeJGYyN12AhaQ6HvE7aF1tFgaAoCCgNJJ"],
                       "version": 1,
                       "amount": 10000000,
                       "recipient": "3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH"
                       }
    """)

    val tx = LeaseTransaction
      .create(
        1.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet(),
        10000000,
        1000000,
        1526646300260L,
        Proofs(ByteStr.decodeBase58("iy3TmfbFds7pc9cDDqfjEJhfhVyNtm3GcxoVz8L3kJFvgRPUmiqqKLMeJGYyN12AhaQ6HvE7aF1tFgaAoCCgNJJ").get)
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

  property("JSON format validation for LeaseTransactionV2") {
    val js = Json.parse("""{
                        "type": 8,
                        "id": "8CxaX9rkG1HdDm1QnzcB23oqnVSNMd6YbzUXZMgrwoY7",
                        "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                        "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                        "fee": 1000000,
                        "feeAssetId": null,
                        "timestamp": 1526646497465,
                        "proofs": [
                        "5Fr3yLwvfKGDsFLi8A8JbHqToHDojrPbdEGx9mrwbeVWWoiDY5pRqS3rcX1rXC9ud52vuxVdBmGyGk5krcgwFu9q"
                        ],
                        "version": 2,
                        "amount": 10000000,
                        "recipient": "3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH"
                       }
    """)

    val tx = LeaseTransaction
      .create(
        2.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet(),
        10000000,
        1000000,
        1526646497465L,
        Proofs(Seq(ByteStr.decodeBase58("5Fr3yLwvfKGDsFLi8A8JbHqToHDojrPbdEGx9mrwbeVWWoiDY5pRqS3rcX1rXC9ud52vuxVdBmGyGk5krcgwFu9q").get))
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

  property("forbid assetId in LeaseTransactionV2") {
    val leaseV2Gen      = leaseGen.filter(_.version == 2)
    val assetIdBytesGen = bytes32gen
    forAll(leaseV2Gen, assetIdBytesGen) { (tx, assetId) =>
      val bytes = tx.bytes()
      // hack in an assetId
      bytes(3) = 1: Byte
      val bytesWithAssetId = bytes.take(4) ++ assetId ++ bytes.drop(4)
      val parsed           = LeaseTxSerializer.parseBytes(bytesWithAssetId)
      parsed.isFailure shouldBe true
      parsed.failed.get.getMessage.contains("Leasing assets is not supported yet") shouldBe true
    }
  }
}
