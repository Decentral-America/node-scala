package com.decentralchain.features

import com.decentralchain.state.Blockchain

object InvokeScriptSelfPaymentPolicyProvider {
  implicit class SelfPaymentPolicyBlockchainExt(b: Blockchain) {
    def disallowSelfPayment: Boolean =
      b.isFeatureActivated(BlockchainFeatures.BlockV5)
  }
}
