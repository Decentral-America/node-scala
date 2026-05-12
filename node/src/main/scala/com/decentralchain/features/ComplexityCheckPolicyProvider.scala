package com.decentralchain.features

import com.decentralchain.state.Blockchain

object ComplexityCheckPolicyProvider {
  implicit class VerifierComplexityCheckExt(b: Blockchain) {
    def useReducedVerifierComplexityLimit: Boolean =
      b.activatedFeatures.contains(BlockchainFeatures.BlockV5.id)
  }
}
