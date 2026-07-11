package com.decentralchain.state.diffs

import cats.instances.list.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.features.BlockchainFeatures.RideV6
import com.decentralchain.features.ComplexityCheckPolicyProvider.*
import com.decentralchain.features.EstimatorProvider.*
import com.decentralchain.lang.ValidationError
import com.decentralchain.lang.contract.DApp
import com.decentralchain.lang.directives.values.StdLibVersion
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.script.{ContractScript, Script}
import com.decentralchain.lang.v1.ContractLimits.{MaxContractSizeInBytes, MaxContractSizeInBytesV6, MaxExprSizeInBytes}
import com.decentralchain.lang.v1.estimator.ScriptEstimator
import com.decentralchain.state.{AccountScriptInfo, Blockchain, Portfolio, StateSnapshot}
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.smart.SetScriptTransaction

object SetScriptTransactionDiff {
  def apply(blockchain: Blockchain)(tx: SetScriptTransaction): Either[ValidationError, StateSnapshot] =
    for {
      // Validate script size limit
      _ <- tx.script match {
        case Some(script) =>
          if (script.isInstanceOf[ExprScript]) scriptSizeValidation(script, MaxExprSizeInBytes)
          else if (blockchain.isFeatureActivated(BlockchainFeatures.RideV6)) scriptSizeValidation(script, MaxContractSizeInBytesV6)
          else scriptSizeValidation(script, MaxContractSizeInBytes)

        case None => Right(())
      }

      callableComplexities <- tx.script match {
        case Some(ContractScriptImpl(version, dApp)) => estimate(blockchain, version, dApp)
        case _                                       => Right(Map[Int, Map[String, Long]]())
      }
      verifierWithComplexity <- DiffsCommon.countVerifierComplexity(tx.script, blockchain, isAsset = false)
      scriptWithComplexities <- verifierWithComplexity
        .map { case (script, verifierComplexity) =>
          AccountScriptInfo(tx.sender, script, verifierComplexity, callableComplexities)
        }
        .traverseTap(checkOverflow(blockchain, _))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(tx.sender.toAddress -> Portfolio(-tx.fee.value)),
        accountScripts = Map(tx.sender -> scriptWithComplexities)
      )
    } yield snapshot

  def scriptSizeValidation(value: Script, limit: Int = MaxExprSizeInBytes): Either[GenericError, Unit] = {
    Either.cond(
      value.bytes().size <= limit,
      (),
      GenericError(s"Script is too large: ${value.bytes().size} bytes > $limit bytes")
    )
  }

  def estimate(
      blockchain: Blockchain,
      version: StdLibVersion,
      dApp: DApp
  ): Either[GenericError, Map[Int, Map[String, Long]]] = {
    val callables          = dApp.copy(verifierFuncOpt = None)
    val actualComplexities =
      for {
        currentComplexity <- ContractScript.estimateComplexity(
          version,
          callables,
          blockchain.estimator,
          fixEstimateOfVerifier = blockchain.isFeatureActivated(RideV6),
          useReducedVerifierLimit = blockchain.useReducedVerifierComplexityLimit
        )
        nextComplexities <- estimateNext(blockchain, version, callables)
        complexitiesByEstimator = (currentComplexity :: nextComplexities).mapWithIndex { case ((_, complexitiesByCallable), i) =>
          (i + blockchain.estimator.version, complexitiesByCallable)
        }.toMap
      } yield complexitiesByEstimator

    actualComplexities.leftMap(GenericError(_))
  }

  private def estimateNext(
      blockchain: Blockchain,
      version: StdLibVersion,
      dApp: DApp
  ): Either[String, List[(Long, Map[String, Long])]] =
    ScriptEstimator
      .all(fixOverflow = blockchain.checkEstimationOverflow)
      .drop(blockchain.estimator.version)
      .traverse(se =>
        ContractScript
          .estimateComplexityExact(version, dApp, se, fixEstimateOfVerifier = blockchain.isFeatureActivated(RideV6))
          .map { case ((_, maxComplexity), complexities) => (maxComplexity, complexities) }
      )

  private def checkOverflow(blockchain: Blockchain, s: AccountScriptInfo): Either[GenericError, Unit] =
    if (blockchain.checkEstimationOverflow)
      if (s.verifierComplexity < 0)
        Left(GenericError("Unexpected negative verifier complexity"))
      else
        s.complexitiesByEstimator.values.flatten
          .collectFirst { case (name, complexity) if complexity < 0 => GenericError(s"Unexpected negative callable `$name` complexity") }
          .toLeft(())
    else
      Right(())
}
