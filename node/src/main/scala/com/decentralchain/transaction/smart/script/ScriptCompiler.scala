package com.decentralchain.transaction.smart.script

import com.decentralchain.lang.directives.values.*
import com.decentralchain.lang.script.ContractScript.ContractScriptImpl
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.script.v1.ExprScript.ExprScriptImpl
import com.decentralchain.lang.v1.estimator.ScriptEstimator
import com.decentralchain.lang.{API, CompileResult}

object ScriptCompiler {
  @deprecated("use ScriptCompiler.compile instead", "1.0")
  def apply(
      scriptText: String,
      isAssetScript: Boolean,
      estimator: ScriptEstimator
  ): Either[String, (Script, Long)] = {
    val script = if (!isAssetScript || scriptText.contains("SCRIPT_TYPE")) scriptText else s"{-# SCRIPT_TYPE ASSET #-}\n$scriptText"
    compile(script, estimator)
  }

  def compile(
      scriptText: String,
      estimator: ScriptEstimator,
      libraries: Map[String, String] = Map(),
      defaultStdLib: => StdLibVersion = StdLibVersion.VersionDic.default
  ): Either[String, (Script, Long)] =
    API.compile(scriptText, estimator, libraries = libraries, defaultStdLib = defaultStdLib).map {
      case CompileResult.Expression(v, _, complexity, expr, _, isFreeCall) => (ExprScriptImpl(v, isFreeCall, expr), complexity)
      case CompileResult.Library(v, _, complexity, expr)                   => (ExprScriptImpl(v, isFreeCall = false, expr), complexity)
      case CompileResult.DApp(v, r, _, _)                                  => (ContractScriptImpl(v, r.dApp), r.verifierComplexity)
    }
}
