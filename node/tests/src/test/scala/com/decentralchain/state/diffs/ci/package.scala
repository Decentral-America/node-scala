package com.decentralchain.state.diffs

import com.decentralchain.account.KeyPair
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.directives.values.V5
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.v1.compiler.Terms.{BLOCK, FUNCTION_CALL, LET}
import com.decentralchain.state.diffs.FeeValidation.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.transaction.{TransactionType, TxVersion}
import com.decentralchain.transaction.smart.{InvokeExpressionTransaction, SetScriptTransaction}
import org.scalacheck.Gen

package object ci {
  private def invokeFee(freeCall: Boolean): Long =
    if (freeCall)
      FeeUnit * FeeConstants(TransactionType.InvokeExpression)
    else
      FeeUnit * FeeConstants(TransactionType.InvokeScript)

  def ciFee(sc: Int = 0, nonNftIssue: Int = 0, freeCall: Boolean = false): Gen[Long] =
    Gen.const(
      invokeFee(freeCall) + sc * ScriptExtraFee + nonNftIssue * FeeConstants(TransactionType.Issue) * FeeUnit
    )

  def toInvokeExpression(
      setScript: SetScriptTransaction,
      invoker: KeyPair,
      fee: Option[Long] = None,
      call: Option[FUNCTION_CALL] = None
  ): InvokeExpressionTransaction = {
    val callables  = setScript.script.get.asInstanceOf[ContractScriptImpl].expr.callableFuncs
    val expression =
      call.fold(
        callables.head.u.body
      ) { c =>
        val callable = callables.find(_.u.name == c.function.funcName).get.u
        (callable.args zip c.args).foldLeft(callable.body) { case (resultExpr, (argName, arg)) =>
          BLOCK(LET(argName, arg), resultExpr)
        }
      }
    InvokeExpressionTransaction
      .selfSigned(
        TxVersion.V1,
        invoker,
        ExprScript(V5, expression, isFreeCall = true).explicitGet(),
        fee.getOrElse(ciFee(freeCall = true).sample.get),
        Dcc,
        setScript.timestamp
      )
      .explicitGet()
  }
}
