package com.decentralchain

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.lang.v1.estimator.ScriptEstimatorV1
import com.decentralchain.state.{AssetDescription, Height, TransactionId}
import com.decentralchain.state.diffs.FeeValidation.{FeeConstants, FeeUnit, ScriptExtraFee}
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.smart.script.ScriptCompiler
import com.decentralchain.transaction.{TransactionType, TxHelpers}

object TestValues {
  val keyPair: KeyPair   = TxHelpers.defaultSigner
  val address: Address   = keyPair.toAddress
  val asset: IssuedAsset = IssuedAsset(ByteStr(("A" * 32).getBytes("ASCII")))
  val bigMoney: Long     = com.decentralchain.state.diffs.ENOUGH_AMT
  val timestamp: Long    = System.currentTimeMillis()
  val fee: Long          = 1e6.toLong

  val invokeFee: Long             = FeeConstants(TransactionType.InvokeScript) * FeeUnit
  val commitToGenerationFee: Long = FeeConstants(TransactionType.CommitToGeneration) * FeeUnit

  def invokeFee(scripts: Int = 0, issues: Int = 0): Long =
    invokeFee + scripts * ScriptExtraFee + issues * FeeConstants(TransactionType.Issue) * FeeUnit

  lazy val (script, scriptComplexity) = ScriptCompiler
    .compile(
      """
        |{-# STDLIB_VERSION 2 #-}
        |{-# CONTENT_TYPE EXPRESSION #-}
        |{-# SCRIPT_TYPE ACCOUNT #-}
        |true
        |""".stripMargin,
      ScriptEstimatorV1
    )
    .explicitGet()

  lazy val (assetScript, assetScriptComplexity) = ScriptCompiler
    .compile(
      """
        |{-# STDLIB_VERSION 2 #-}
        |{-# CONTENT_TYPE EXPRESSION #-}
        |{-# SCRIPT_TYPE ASSET #-}
        |true
        |""".stripMargin,
      ScriptEstimatorV1
    )
    .explicitGet()

  lazy val (rejectAssetScript, rejectAssetScriptComplexity) = ScriptCompiler
    .compile(
      """
        |{-# STDLIB_VERSION 2 #-}
        |{-# CONTENT_TYPE EXPRESSION #-}
        |{-# SCRIPT_TYPE ASSET #-}
        |false
        |""".stripMargin,
      ScriptEstimatorV1
    )
    .explicitGet()

  val assetDescription: AssetDescription = AssetDescription(
    TransactionId(asset.id),
    TxHelpers.defaultSigner.publicKey,
    null,
    null,
    0,
    reissuable = true,
    BigInt(1),
    Height(1),
    None,
    0,
    nft = false,
    0,
    Height(1)
  )
}
