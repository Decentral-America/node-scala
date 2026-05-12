package com.decentralchain.ride

import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.features.ComplexityCheckPolicyProvider.VerifierComplexityCheckExt
import com.decentralchain.features.EstimatorProvider.EstimatorBlockchainExt
import com.decentralchain.lang.script.Script
import com.decentralchain.lang.script.Script.ComplexityInfo
import com.decentralchain.state.Blockchain

package object runner {

  // See DiffCommon.countVerifierComplexity
  def estimate(
      blockchain: Blockchain,
      script: Script,
      isAsset: Boolean,
      withCombinedContext: Boolean = false
  ): ComplexityInfo = {
    val fixEstimateOfVerifier    = blockchain.isFeatureActivated(BlockchainFeatures.RideV6)
    val useContractVerifierLimit = !isAsset && blockchain.useReducedVerifierComplexityLimit

    Script.complexityInfo(
      script,
      blockchain.estimator,
      fixEstimateOfVerifier,
      useContractVerifierLimit,
      withCombinedContext = withCombinedContext
    ) match {
      case Right(x) => x
      case Left(e)  => throw new RuntimeException(s"Can't get a complexity info of script: $e")
    }
  }

}
