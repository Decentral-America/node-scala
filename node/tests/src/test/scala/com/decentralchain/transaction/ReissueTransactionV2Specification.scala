package com.decentralchain.transaction

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.assets.{IssueTransaction, ReissueTransaction}
import com.decentralchain.transaction.TxHelpers
import org.scalacheck.Gen
import play.api.libs.json.*

class ReissueTransactionV2Specification extends GenericTransactionSpecification[ReissueTransaction] {
  def transactionParser: TransactionParser = ReissueTransaction

  def updateProofs(tx: ReissueTransaction, p: Proofs): ReissueTransaction = {
    tx.copy(proofs = p)
  }

  def assertTxs(first: ReissueTransaction, second: ReissueTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.timestamp shouldEqual second.timestamp
    first.fee shouldEqual second.fee
    first.version shouldEqual second.version
    first.quantity shouldEqual second.quantity
    first.reissuable shouldEqual second.reissuable
    first.asset shouldEqual second.asset
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  def generator: Gen[(Seq[com.decentralchain.transaction.Transaction], ReissueTransaction)] =
    for {
      (sender, assetName, description, quantity, decimals, _, iFee, timestamp) <- issueParamGen
      fee                                                                      <- smallFeeGen
      reissuable                                                               <- Gen.oneOf(true, false)
    } yield {
      val issue = IssueTransaction
        .create(
          TxVersion.V1,
          sender.publicKey,
          new String(assetName),
          new String(description),
          quantity,
          decimals,
          reissuable = true,
          script = None,
          iFee,
          timestamp
        )
        .map(_.signWith(sender.privateKey))
        .explicitGet()
      val reissue1 = ReissueTransaction
        .create(2.toByte, sender.publicKey, issue.asset, quantity, reissuable = reissuable, fee, timestamp, Proofs.empty)
        .map(_.signWith(sender.privateKey))
        .explicitGet()
      (Seq(issue), reissue1)
    }

  def jsonRepr: Seq[(JsValue, ReissueTransaction)] =
    Seq(
      (
        Json.parse("""{
                       "type": 5,
                       "id": "BrAoJM1xSxBN5tkpkeoGrPq9E5sKjBsUbyGH5scfpccY",
                       "sender": "3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "feeAssetId": null,
                       "timestamp": 1526287561757,
                       "proofs": [
                       "4DFEtUwJ9gjMQMuEXipv2qK7rnhhWEBqzpC3ZQesW1Kh8D822t62e3cRGWNU3N21r7huWnaty95wj2tZxYSvCfro"
                       ],
                       "version": 2,
                       "chainId": 63,
                       "assetId": "9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz",
                       "quantity": 100000000,
                       "reissuable": true
                    }
    """),
        ReissueTransaction
          .create(
            2.toByte,
            PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
            IssuedAsset(ByteStr.decodeBase58("9ekQuYn92natMnMq8KqeGK3Nn7cpKd3BvPEGgD6fFyyz").get),
            100000000L,
            true,
            100000000L,
            1526287561757L,
            Proofs(Seq(ByteStr.decodeBase58("4DFEtUwJ9gjMQMuEXipv2qK7rnhhWEBqzpC3ZQesW1Kh8D822t62e3cRGWNU3N21r7huWnaty95wj2tZxYSvCfro").get))
          )
          .explicitGet()
      )
    )

  def transactionName: String = "ReissueTransactionV2"

  override def preserBytesJson: Option[(Array[Byte], JsValue)] = {
    val asset = IssuedAsset(ByteStr(Array.fill(32)(4: Byte)))
    val tx = TxHelpers.reissue(asset, version = TxVersion.V2)
    Some(tx.bytes() -> tx.json())
  }
}
