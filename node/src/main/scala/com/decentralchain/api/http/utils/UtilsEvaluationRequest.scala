package com.decentralchain.api.http.utils

import cats.implicits.toTraverseOps
import cats.syntax.either.*
import com.decentralchain.account.{Address, PublicKey}
import com.decentralchain.api.http.requests.InvokeScriptRequest
import com.decentralchain.api.http.requests.InvokeScriptRequest.FunctionCallPart
import com.decentralchain.api.http.utils.UtilsApiRoute.DefaultPublicKey
import com.decentralchain.api.http.utils.UtilsEvaluator.{ConflictingRequestStructure, ParseJsonError}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.directives.values.{Expression, StdLibVersion, V6}
import com.decentralchain.lang.v1.compiler.Terms.EXPR
import com.decentralchain.lang.v1.compiler.{ExpressionCompiler, Terms}
import com.decentralchain.lang.v1.evaluator.ContractEvaluator.Invocation
import com.decentralchain.lang.v1.parser.Parser.LibrariesOffset.NoLibraries
import com.decentralchain.lang.v1.traits.domain.Recipient.Address as RideAddress
import com.decentralchain.lang.{ValidationError, utils}
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import com.decentralchain.state.{Blockchain, BlockchainOverrides, SnapshotBlockchain, StateSnapshot}
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.AttachedPaymentExtractor
import com.decentralchain.transaction.smart.InvokeScriptTransaction.Payment
import com.decentralchain.transaction.{Asset, TransactionType, smart}
import play.api.libs.json.*

import scala.collection.immutable.VectorMap

sealed trait UtilsEvaluationRequest {
  def state: Option[BlockchainOverrides]
  def mkBlockchain(underlying: Blockchain): Blockchain = {

    state.fold(underlying) { ovs =>
      SnapshotBlockchain(
        underlying,
        StateSnapshot(balances = VectorMap.from[(Address, Asset), Long](for {
          (addr, ov)    <- ovs.accounts
          (id, balance) <- ov.assetBalances
        } yield ((addr, id), balance.value)) ++ VectorMap.from(ovs.accounts.flatMap { case (addr, acc) =>
          acc.regularBalance.map(v => ((addr, Asset.Dcc), v.value))
        }))
      )
    }
  }
}

object UtilsEvaluationRequest {
  // This should be Reads, but we will broke REST API
  def parse(request: JsObject): Either[ValidationError, UtilsEvaluationRequest] = {
    def withoutCommonFields = request - "state"
    (request.validate[UtilsExprRequest], request.validate[UtilsInvocationRequest]) match {
      case (JsSuccess(_, _), JsSuccess(_, _)) if withoutCommonFields.fields.size > 1 =>
        ConflictingRequestStructure.asLeft
      case (JsSuccess(exprRequest, _), _)       => exprRequest.asRight
      case (_, JsSuccess(invocationRequest, _)) => invocationRequest.asRight
      case (_, e: JsError)                      => ParseJsonError(e).asLeft
    }
  }
}

case class UtilsExprRequest(
    expr: String,
    state: Option[BlockchainOverrides] = None
) extends UtilsEvaluationRequest {
  def parseCall(version: StdLibVersion): Either[GenericError, Terms.EXPR] = compile(version, expr)

  private def compile(version: StdLibVersion, str: String): Either[GenericError, EXPR] =
    ExpressionCompiler
      .compileUntyped(str, NoLibraries, utils.compilerContext(version, Expression, isAssetScript = false).copy(arbitraryDeclarations = true), version)
      .leftMap(GenericError(_))
}

object UtilsExprRequest {
  implicit val utilsExprRequestReads: Reads[UtilsExprRequest] = Json.using[Json.WithDefaultValues].reads[UtilsExprRequest]
}

case class UtilsInvocationRequest(
    call: FunctionCallPart = FunctionCallPart("default", Nil),
    id: String = ByteStr(empty32Bytes).toString,
    fee: Long = FeeConstants(TransactionType.InvokeScript) * FeeUnit,
    feeAssetId: Option[String] = None,
    sender: Option[String] = None,
    senderPublicKey: String = DefaultPublicKey.toString,
    payment: Seq[Payment] = Nil,
    state: Option[BlockchainOverrides] = None
) extends UtilsEvaluationRequest {
  def toInvocation: Either[ValidationError, Invocation] =
    for {
      senderPK <- PublicKey.fromBase58String(senderPublicKey)
      id       <- decodeBase58(id)
      functionCall = InvokeScriptRequest.buildFunctionCall(call)
      feeAssetId    <- feeAssetId.traverse(decodeBase58)
      senderAddress <- sender
        .fold(RideAddress(ByteStr(senderPK.toAddress.bytes)).asRight[ValidationError]) { s =>
          Address.fromString(s).map(a => RideAddress(ByteStr(a.bytes)))
        }
      payments <- AttachedPaymentExtractor
        .extractPayments(payment, V6, blockchainAllowsMultiPayment = true, smart.DApp)
        .leftMap(GenericError(_))
    } yield Invocation(functionCall, senderAddress, senderPK.byteStr, senderAddress, senderPK.byteStr, payments, id, fee, feeAssetId)

  private def decodeBase58(base58: String): Either[ValidationError, ByteStr] =
    ByteStr.decodeBase58(base58).toEither.leftMap(e => GenericError(String.valueOf(e.getMessage)))
}

object UtilsInvocationRequest {
  implicit val reads: Reads[UtilsInvocationRequest] = Json.using[Json.WithDefaultValues].reads[UtilsInvocationRequest]
}
