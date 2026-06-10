package com.decentralchain.transaction

import com.decentralchain.account.{KeyPair, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.TxHelpers
import org.scalacheck.Gen
import play.api.libs.json.*

class SetScriptTransactionSpecification extends GenericTransactionSpecification[SetScriptTransaction] {

  def transactionParser: TransactionParser = SetScriptTransaction

  def updateProofs(tx: SetScriptTransaction, p: Proofs): SetScriptTransaction = {
    tx.copy(1.toByte, proofs = p)
  }

  def assertTxs(first: SetScriptTransaction, second: SetScriptTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.timestamp shouldEqual second.timestamp
    first.fee shouldEqual second.fee
    first.version shouldEqual second.version
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
    first.script shouldEqual second.script
  }

  def generator: Gen[(Seq[com.decentralchain.transaction.Transaction], SetScriptTransaction)] = setScriptTransactionGen.map(t => (Seq(), t))

  def jsonRepr: Seq[(JsValue, SetScriptTransaction)] =
    Seq(
      (
        Json.parse("""{
                       "type": 13,
                       "id": "FCMke7Ua1NRFVcfuL59hGGLvWJWr9bxhfCvLfL6isMJr",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000,
                       "feeAssetId": null,
                       "timestamp": 1526983936610,
                       "proofs": [
                       "tcTr672rQ5gXvcA9xCGtQpkHC8sAY1TDYqDcQG7hQZAeHcvvHFo565VEv1iD1gVa3ZuGjYS7hDpuTnQBfY2dUhY"
                       ],
                       "version": 1,
                       "chainId": 63,
                       "script": null
                       }
    """),
        SetScriptTransaction
          .create(
            1.toByte,
            PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
            None,
            100000,
            1526983936610L,
            Proofs(Seq(ByteStr.decodeBase58("tcTr672rQ5gXvcA9xCGtQpkHC8sAY1TDYqDcQG7hQZAeHcvvHFo565VEv1iD1gVa3ZuGjYS7hDpuTnQBfY2dUhY").get))
          )
          .explicitGet()
      )
    )

  def transactionName: String = "SetScriptTransaction"

  property("SetScriptTransaction id doesn't depend on proof (spec)") {
    forAll(accountGen, proofsGen, proofsGen, contractOrExpr) { case (acc: KeyPair, proofs1, proofs2, script) =>
      val tx1 = SetScriptTransaction.create(1.toByte, acc.publicKey, Some(script), 1, 1, proofs1).explicitGet()
      val tx2 = SetScriptTransaction.create(1.toByte, acc.publicKey, Some(script), 1, 1, proofs2).explicitGet()
      tx1.id() shouldBe tx2.id()
    }
  }

  override def preserBytesJson: Option[(Array[Byte], JsValue)] = {
    val tx = TxHelpers.setScript(TxHelpers.defaultSigner, script = None, version = TxVersion.V1)
    Some(tx.bytes() -> tx.json())
  }
}
