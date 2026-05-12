package com.decentralchain.features

import com.decentralchain.state.Blockchain

object EvaluatorFixProvider {
  implicit class CorrectFunctionCallScopeExt(b: Blockchain) {
    def correctFunctionCallScope: Boolean =
      b.height >= b.settings.functionalitySettings.estimatorSumOverflowFixHeight
    def newEvaluatorMode: Boolean =
      b.isFeatureActivated(BlockchainFeatures.RideV6)
  }
}
