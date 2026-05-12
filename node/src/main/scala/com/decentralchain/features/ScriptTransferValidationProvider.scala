package com.decentralchain.features

import com.decentralchain.state.Blockchain

object ScriptTransferValidationProvider {
  implicit class PassCorrectAssetIdExt(b: Blockchain) {
    def passCorrectAssetId: Boolean =
      b.isFeatureActivated(BlockchainFeatures.BlockV5)
  }
}
