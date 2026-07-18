package com.decentralchain.transaction

import com.decentralchain.account.{AddressScheme, PrivateKey, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto.bls.{BlsKeyPair, BlsPublicKey}
import com.decentralchain.db.WithDomain
import com.decentralchain.state.Height
import com.decentralchain.test.*
import com.decentralchain.transaction.serialization.impl.PBTransactionSerializer
import play.api.libs.json.Json

import scala.util.{Failure, Success}

class CommitToGenerationTransactionsSpec extends FreeSpec with WithDomain {
  private val wavesSigner = TxHelpers.signer(0)
  private val blsKp       = BlsKeyPair(wavesSigner.privateKey)
  private val sig         = CommitToGenerationTransaction.mkPopSignature(blsKp, Height(3000))

  private val origTx = CommitToGenerationTransaction(
    version = TxVersion.V1,
    sender = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
    endorserPublicKey = BlsPublicKey(Base58.decode("6CagLT3FjEcaNHPYCaG2dcfEfzDj6ynVeZbxbLHkHdfzvbfBmBMkkatTYcBXD9cHMU")).explicitGet(),
    generationPeriodStart = Height(3000),
    timestamp = 1526287561757L,
    fee = TxPositiveAmount.unsafeFrom(100000000),
    commitmentSignature = sig,
    proofs = Proofs(ByteStr.decodeBase58("28kE1uN1pX2bwhzr9UHw5UuB9meTFEDFgeunNgy6nZWpHX4pzkGYotu8DhQ88AdqUG6Yy5wcXgHseKPBUygSgRMJ").get),
    chainId = AddressScheme.current.chainId
  )

  "JSON parsing" in {
    val js = Json.parse(s"""{
      "id": "DUWUmYm1CXte3a97W2Fu2Sxf1D7FA9bZFGARtw54Xbep",
      "type": 19,
      "version": 1,
      "fee": 100000000,
      "feeAssetId": null,
      "timestamp": 1526287561757,
      "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
      "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
      "generationPeriodStart": 3000,
      "endorserPublicKey": "6CagLT3FjEcaNHPYCaG2dcfEfzDj6ynVeZbxbLHkHdfzvbfBmBMkkatTYcBXD9cHMU",
      "commitmentSignature": "$sig",
      "proofs": [
        "28kE1uN1pX2bwhzr9UHw5UuB9meTFEDFgeunNgy6nZWpHX4pzkGYotu8DhQ88AdqUG6Yy5wcXgHseKPBUygSgRMJ"
      ],
      "chainId": 63
    }""")
    origTx.json() shouldEqual js
  }

  "PB roundtrip" in {
    PBTransactionSerializer.parseBytes(PBTransactionSerializer.bytes(origTx)) match {
      case Success(tx: CommitToGenerationTransaction) =>
        tx shouldBe origTx
        tx.proofs shouldBe origTx.proofs
      case Success(tx)        => fail(s"Unexpected transaction type: ${tx.tpe.transactionName}")
      case Failure(exception) => fail(exception)
    }
  }

  "Expected BLS key and PoP" in {
    val dccPk = PrivateKey(ByteStr.decodeBase58("7UR2CZi6Gv6v1yqmgcPDD98ZtosvtHnNZRxvrHA2Tuyn").get)

    val blsKp = BlsKeyPair(dccPk)
    blsKp.publicKey.byteStr.base64Raw shouldBe "jrugi0W0es2WxuHoptQtchqwactZsldOGucYObZrEIOpxbWmhL8dodvpnzA+2qUf"

    CommitToGenerationTransaction.mkPopSignature(blsKp, Height(1001)).byteStr.base64Raw shouldBe
      "sOlLZL2RZZ3c98PmUvKSN960aj+VJwyVGEUygI78mGDwGJflJWLHCwuqiYk1fRG7FOCJKOtKbKOG7tBykQ5iTcRu+7eLWhiodJw47YEfDOZHNwkl8dQwgxAam8+3BEvX"
  }
}
