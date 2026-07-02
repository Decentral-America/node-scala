package com.decentralchain.transaction

import com.decentralchain.TestValues
import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.contract.DApp
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.{ContractScript, Script}
import io.decentralchain.protobuf.dapp.DAppMeta
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.assets.SetAssetScriptTransaction
import com.decentralchain.transaction.TxHelpers
import org.scalacheck.Gen
import play.api.libs.json.*
import com.decentralchain.test.*

class SetAssetScriptTransactionSpecification extends GenericTransactionSpecification[SetAssetScriptTransaction] {
  property("issuer can`t make SetAssetScript tx when Script is Contract") {
    val accountA = PublicKey.fromBase58String("5k3gXC486CCFCwzUAgavH9JfPwmq9CbBZvTARnFujvgr").explicitGet()

    SetAssetScriptTransaction
      .create(
        1.toByte,
        accountA,
        IssuedAsset(ByteStr.decodeBase58("DUyJyszsWcmZG7q2Ctk1hisDeGBPB8dEzyU8Gs5V2j3n").get),
        Some(ContractScript(V3, DApp(DAppMeta(), List.empty, List.empty, None)).explicitGet()),
        1222,
        System.currentTimeMillis(),
        Proofs.empty
      ) should produce("not Contract")
  }

  property("can't be created with empty script") {
    val gen = for {
      acc   <- accountGen
      asset <- bytes32gen
      fee   <- smallFeeGen
      ts    <- timestampGen
      txEi = SetAssetScriptTransaction.selfSigned(TxVersion.V2, acc, IssuedAsset(ByteStr(asset)), None, fee, ts)
    } yield txEi

    forAll(gen)(_ should produce("Cannot set empty script"))
  }

  override def transactionParser: TransactionParser = SetAssetScriptTransaction

  override def updateProofs(tx: SetAssetScriptTransaction, p: Proofs): SetAssetScriptTransaction = tx.copy(1.toByte, proofs = p)

  override def generator: Gen[(Seq[Transaction], SetAssetScriptTransaction)] = setAssetScriptTransactionGen
  override def assertTxs(first: SetAssetScriptTransaction, second: SetAssetScriptTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.timestamp shouldEqual second.timestamp
    first.fee shouldEqual second.fee
    first.version shouldEqual second.version
    first.asset shouldEqual second.asset
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
    first.script shouldEqual second.script
  }

  def jsonRepr: Seq[(JsValue, SetAssetScriptTransaction)] =
    Seq(
      (
        Json.parse(
          s"""{"type":15,"id":"AuUiA9L8nJuEb3GhFGuFuFLJnAAZJnsAS8RQdLsF6CoT","sender":"3DjEAhXXip6eL6CScXURdmXynGpuuJaLXXy","senderPublicKey":"5k3gXC486CCFCwzUAgavH9JfPwmq9CbBZvTARnFujvgr","fee":78311891,"feeAssetId":null,"timestamp":1868142423132802425,"proofs":["5sRtXKcdDa","9Zfe5aw9D7rRR3nvU3QuAjCNT7pdwRXwvBFxHmdt2WtWwiEwffn","","3C","24jboCkAEFrsBKNh6z8FFyJP8YhejsrBwt7JdHVhiCk7DCc3Zxsc4g6PYG8tsLXmK",""],"version":1,"chainId":${AddressScheme.current.chainId},"assetId":"DUyJyszsWcmZG7q2Ctk1hisDeGBPB8dEzyU8Gs5V2j3n","script":"base64:AQkAAGcAAAACAHho/EXujJiPAJUhuPXZYac+rt2jYg=="}"""
        ),
        SetAssetScriptTransaction
          .create(
            1.toByte,
            PublicKey.fromBase58String("5k3gXC486CCFCwzUAgavH9JfPwmq9CbBZvTARnFujvgr").explicitGet(),
            IssuedAsset(ByteStr.decodeBase58("DUyJyszsWcmZG7q2Ctk1hisDeGBPB8dEzyU8Gs5V2j3n").get),
            Some(Script.fromBase64String("base64:AQkAAGcAAAACAHho/EXujJiPAJUhuPXZYac+rt2jYg==").explicitGet()),
            78311891L,
            1868142423132802425L,
            Proofs(
              Seq(
                "5sRtXKcdDa",
                "9Zfe5aw9D7rRR3nvU3QuAjCNT7pdwRXwvBFxHmdt2WtWwiEwffn",
                "",
                "3C",
                "24jboCkAEFrsBKNh6z8FFyJP8YhejsrBwt7JdHVhiCk7DCc3Zxsc4g6PYG8tsLXmK",
                ""
              ).map(ByteStr.decodeBase58(_).get)
            )
          )
          .explicitGet()
      )
    )

  def transactionName: String = "SetAssetScriptTransaction"

  override def preserBytesJson: Option[(Array[Byte], JsValue)] = {
    // Bypasses SetAssetScriptTransaction.create's "Cannot set empty script" validation on purpose:
    // this checks that pre-existing serialized transactions with a null script (predating that
    // validation) still parse and round-trip correctly.
    val asset = IssuedAsset(ByteStr(Array.fill(32)(3: Byte)))
    val tx = SetAssetScriptTransaction(
      TxVersion.V1,
      TxHelpers.defaultSigner.publicKey,
      asset,
      None,
      TxPositiveAmount.unsafeFrom(TestValues.fee),
      TxHelpers.timestamp,
      Proofs.empty,
      AddressScheme.current.chainId
    ).signWith(TxHelpers.defaultSigner.privateKey)
    Some(tx.bytes() -> tx.json())
  }
}
