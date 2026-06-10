package com.decentralchain.transaction

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.assets.BurnTransaction
import com.decentralchain.crypto
import com.decentralchain.test.PropSpec
import com.decentralchain.transaction.serialization.impl.BurnTxSerializer
import com.decentralchain.transaction.TxHelpers
import play.api.libs.json.Json

class BurnTransactionSpecification extends PropSpec {
  property("Burn serialization roundtrip") {
    forAll(burnGen) { tx =>
      val recovered = BurnTxSerializer.parseBytes(tx.bytes()).get
      recovered.bytes() shouldEqual tx.bytes()
    }
  }

  property("Burn binary parse roundtrip") {
    val asset = IssuedAsset(ByteStr(Array.fill(32)(1: Byte)))
    val tx = TxHelpers.burn(asset, amount = 34639959482919L, version = TxVersion.V2)
    val parsed = BurnTransaction.serializer.parseBytes(tx.bytes()).get
    parsed.json() shouldBe tx.json()
    assert(crypto.verify(tx.signature, tx.bodyBytes(), tx.sender), "signature should be valid")
  }

  property("Burn serialization from TypedTransaction") {
    forAll(burnGen) { (issue: BurnTransaction) =>
      val recovered = TransactionParsers.parseBytes(issue.bytes()).get
      recovered.bytes() shouldEqual issue.bytes()
    }
  }

  property("JSON format validation for BurnTransactionV1") {
    val js = Json.parse("""{
                       "type": 6,
                       "id": "Ci1q7y7Qq2C2GDH7YVXsQ8w5vRRKYeoYTp9J76AXw8TZ",
                       "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "timestamp": 1526287561757,
                       "signature": "uapJcAJQryBhWThU43rYgMNmvdT7kY747vx5BBgxr2KvaeTRx8Vsuh4yu1JxBymU9LnAoo1zjQcPrWSuhi6dVPE",
                       "proofs": ["uapJcAJQryBhWThU43rYgMNmvdT7kY747vx5BBgxr2KvaeTRx8Vsuh4yu1JxBymU9LnAoo1zjQcPrWSuhi6dVPE"],
                       "version": 1,
                       "assetId": "9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz",
                       "feeAssetId": null,
                       "amount": 10000000000
                    }
    """)

    val tx = BurnTransaction
      .create(
        1.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        IssuedAsset(ByteStr.decodeBase58("9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz").get),
        10000000000L,
        100000000L,
        1526287561757L,
        Proofs(ByteStr.decodeBase58("uapJcAJQryBhWThU43rYgMNmvdT7kY747vx5BBgxr2KvaeTRx8Vsuh4yu1JxBymU9LnAoo1zjQcPrWSuhi6dVPE").get)
      )
      .explicitGet()
    js shouldEqual tx.json()
  }

  property("JSON format validation for BurnTransactionV2") {
    val js = Json.parse("""{
                       "type": 6,
                       "id": "5RsafjZtMGiDWJh4TeM2cofdUvaYH2sTFX3tSgDBmYpg",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "timestamp": 1526287561757,
                       "proofs": [
                       "3NcEv6tcVMuXkTJwiqW4J3GMCTe8iSLY7neEfNZonp59eTQEZXYPQWs565CRUctDrvcbtmsRgWvnN7BnFZ1AVZ1H"
                       ],
                       "chainId": 63,
                       "version": 2,
                       "assetId": "9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz",
                       "feeAssetId": null,
                       "amount": 10000000000
                    }
    """)

    val tx = BurnTransaction
      .create(
        2.toByte,
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        IssuedAsset(ByteStr.decodeBase58("9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz").get),
        10000000000L,
        100000000L,
        1526287561757L,
        Proofs(Seq(ByteStr.decodeBase58("3NcEv6tcVMuXkTJwiqW4J3GMCTe8iSLY7neEfNZonp59eTQEZXYPQWs565CRUctDrvcbtmsRgWvnN7BnFZ1AVZ1H").get))
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

}
