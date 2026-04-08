package com.decentralchain.it.sync.smartcontract.freecall

import com.google.protobuf.ByteString
import com.typesafe.config.Config
import com.decentralchain.account.AddressScheme
import com.decentralchain.features.BlockchainFeatures.ContinuationTransaction
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.api.SyncGrpcApi.*
import com.decentralchain.it.api.{PutDataResponse, StateChangesDetails}
import com.decentralchain.it.sync.grpc.GrpcBaseTransactionSuite
import com.decentralchain.it.sync.invokeExpressionFee
import com.decentralchain.lang.directives.values.V6
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.v1.compiler.TestCompiler
import com.decentralchain.protobuf.block.VanillaBlock
import com.decentralchain.state.Height
import com.decentralchain.transaction.Asset.Waves
import com.decentralchain.transaction.smart.InvokeExpressionTransaction
import org.scalatest.{Assertion, CancelAfterFailure}

class InvokeExpressionGrpcSuite extends GrpcBaseTransactionSuite with CancelAfterFailure {
  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(
    BiggestMiner.quorum(0).preactivatedFeatures((ContinuationTransaction, Height(1)))
  )

  private val expr: ExprScript =
    TestCompiler(V6).compileFreeCall(
      """
        | [
        |   BooleanEntry("check", true)
        | ]
      """.stripMargin
    )

  test("successful applying to the state") {
    val id     = sender.broadcastInvokeExpression(firstAcc, expr, waitForTx = true).id
    val height = sender.getTransactionInfo(id).height.toInt

    val lastBlock          = sender.blockAt(height)
    val blockById          = sender.blockById(ByteString.copyFrom(lastBlock.id.value().arr))
    val blocksSeq          = sender.blockSeq(1, 100)
    val blocksSeqByAddress = sender.blockSeqByAddress(lastBlock.header.generator.toAddress.toString, 1, 100)
    List(
      findTxInBlock(lastBlock, id),
      findTxInBlock(blockById, id),
      findTxInBlockSeq(blocksSeq, id),
      findTxInBlockSeq(blocksSeqByAddress, id)
    ).foreach(checkTx)

    val stateChangesById      = sender.stateChanges(id)._2
    val stateChangesByAddress = sender.stateChanges(ByteString.copyFrom(firstAcc.toAddress.bytes)).head._2
    List(stateChangesById, stateChangesByAddress).foreach(checkStateChanges)

    sender.getDataByKey(firstAddress, "check").head.value.boolValue.get shouldBe true
  }

  private def findTxInBlock(b: VanillaBlock, id: String): InvokeExpressionTransaction =
    findTxInBlockSeq(Seq(b), id)

  private def findTxInBlockSeq(b: Seq[VanillaBlock], id: String): InvokeExpressionTransaction =
    b.flatMap(_.transactionData).find(_.id.value().toString == id).get.asInstanceOf[InvokeExpressionTransaction]

  private def checkTx(tx: InvokeExpressionTransaction): Assertion = {
    tx.fee.value shouldBe invokeExpressionFee
    tx.feeAssetId shouldBe Dcc
    tx.sender shouldBe firstAcc.publicKey
    tx.expression shouldBe expr
    tx.version shouldBe 1
    tx.timestamp should be > 0L
    tx.proofs.size shouldBe 1
    tx.chainId shouldBe AddressScheme.current.chainId
  }

  private def checkStateChanges(s: StateChangesDetails): Assertion =
    s.data.head shouldBe PutDataResponse("boolean", true, "check")
}
