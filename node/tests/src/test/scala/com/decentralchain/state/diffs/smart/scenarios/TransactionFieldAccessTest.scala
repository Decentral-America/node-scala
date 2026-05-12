package com.decentralchain.state.diffs.smart.scenarios

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithState
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.utils.*
import com.decentralchain.lang.v1.compiler.ExpressionCompiler
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.state.diffs.ENOUGH_AMT
import com.decentralchain.state.diffs.smart.*
import com.decentralchain.test.*
import com.decentralchain.transaction.{GenesisTransaction, TxHelpers}
import com.decentralchain.transaction.lease.LeaseTransaction
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.*

class TransactionFieldAccessTest extends PropSpec with WithState {

  private def preconditionsTransferAndLease(code: String): (GenesisTransaction, SetScriptTransaction, LeaseTransaction, TransferTransaction) = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val genesis   = TxHelpers.genesis(master.toAddress)
    val untyped   = Parser.parseExpr(code).get.value
    val typed     = ExpressionCompiler(compilerContext(V1, Expression, isAssetScript = false), V1, untyped).explicitGet()._1
    val setScript = TxHelpers.setScript(master, ExprScript(typed).explicitGet())
    val transfer  = TxHelpers.transfer(master, recipient.toAddress, ENOUGH_AMT / 2)
    val lease     = TxHelpers.lease(master, recipient.toAddress, ENOUGH_AMT / 2)

    (genesis, setScript, lease, transfer)
  }

  private val script =
    """
      |
      | match tx {
      | case ttx: TransferTransaction =>
      |       isDefined(ttx.assetId)==false
      | case _ =>
      |       false
      | }
      """.stripMargin

  property("accessing field of transaction without checking its type first results on exception") {
    val (genesis, setScript, lease, transfer) = preconditionsTransferAndLease(script)
    assertDiffAndState(Seq(TestBlock.create(Seq(genesis, setScript))), TestBlock.create(Seq(transfer)), smartEnabledFS) { case _ => () }
    assertDiffEi(Seq(TestBlock.create(Seq(genesis, setScript))), TestBlock.create(Seq(lease)), smartEnabledFS)(snapshotEi =>
      snapshotEi should produce("TransactionNotAllowedByScript")
    )
  }
}
