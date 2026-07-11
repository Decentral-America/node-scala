package com.decentralchain.state.diffs.smart.scenarios

import com.decentralchain.account.Alias
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithState
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.utils.*
import com.decentralchain.lang.v1.compiler.ExpressionCompiler
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.state.{BinaryDataEntry, BooleanDataEntry, IntegerDataEntry, StringDataEntry}
import com.decentralchain.state.diffs.TransactionDiffer.TransactionValidationError
import com.decentralchain.state.diffs.smart.smartEnabledFS
import com.decentralchain.test.*
import com.decentralchain.transaction.TxValidationError.ScriptExecutionError
import com.decentralchain.transaction.smart.SetScriptTransaction
import com.decentralchain.transaction.transfer.*
import com.decentralchain.transaction.{CreateAliasTransaction, DataTransaction, GenesisTransaction, TxHelpers}

class OracleDataTest extends PropSpec with WithState {

  val preconditions: (Seq[GenesisTransaction], CreateAliasTransaction, SetScriptTransaction, DataTransaction, TransferTransaction) = {
    val master = TxHelpers.signer(1)
    val oracle = TxHelpers.signer(2)
    val alice  = TxHelpers.signer(3)

    val genesis     = Seq(master, oracle).map(acc => TxHelpers.genesis(acc.toAddress))
    val alias       = Alias.create("alias").explicitGet()
    val createAlias = TxHelpers.createAlias(alias.name, oracle)

    val long                    = IntegerDataEntry("long", 1)
    val bool                    = BooleanDataEntry("bool", true)
    val bin                     = BinaryDataEntry("bin", ByteStr.fromLong(1))
    val str                     = StringDataEntry("str", "test_str")
    val dataTx                  = TxHelpers.data(oracle, Seq(long, bool, bin, str))
    val allFieldsRequiredScript = s"""
                                     | match tx {
                                     | case t : DataTransaction =>
                                     |   let txId = match extract(transactionById(t.id)) {
                                     |     case d: DataTransaction => d.bodyBytes == base64'${ByteStr(dataTx.bodyBytes.apply()).base64}'
                                     |     case _ => false
                                     |   }
                                     |   let txHeightId = extract(transactionHeightById(t.id)) > 0
                                     |   txId && txHeightId
                                     | case _ : CreateAliasTransaction => true
                                     | case _ =>
                                     |   let oracle = Alias("${alias.name}")
                                     |   let long = extract(getInteger(oracle,"${long.key}")) == ${long.value}
                                     |   let bool = extract(getBoolean(oracle,"${bool.key}")) == ${bool.value}
                                     |   let bin = extract(getBinary(oracle,"${bin.key}")) == base58'${bin.value.toString}'
                                     |   let str = extract(getString(oracle,"${str.key}")) == "${str.value}"
                                     |   long && bool && bin && str
                                     |}""".stripMargin
    val untypedAllFieldsRequiredScript = Parser.parseExpr(allFieldsRequiredScript).get.value
    val typedAllFieldsRequiredScript   =
      ExpressionCompiler(compilerContext(V1, Expression, isAssetScript = false), V1, untypedAllFieldsRequiredScript).explicitGet()._1
    val setScript            = TxHelpers.setScript(master, ExprScript(typedAllFieldsRequiredScript).explicitGet())
    val transferFromScripted = TxHelpers.transfer(master, alice.toAddress)

    (genesis, createAlias, setScript, dataTx, transferFromScripted)
  }

  property("simple oracle value required to transfer") {
    val (genesis, createAlias, setScript, dataTransaction, transferFromScripted) = preconditions
    assertDiffAndState(
      Seq(TestBlock.create(genesis :+ createAlias :+ setScript :+ dataTransaction)),
      TestBlock.create(Seq(transferFromScripted)),
      smartEnabledFS
    ) { case _ => () }
    assertDiffEi(
      Seq(TestBlock.create(genesis :+ createAlias :+ setScript)),
      TestBlock.create(Seq(transferFromScripted)),
      smartEnabledFS
    )(_ should matchPattern { case Left(TransactionValidationError(_: ScriptExecutionError, _)) => })
  }
}
