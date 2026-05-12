package com.decentralchain.features.api

import com.decentralchain.features.BlockchainFeatureStatus
import com.decentralchain.state.Height

case class FeatureActivationStatus(
    id: Short,
    description: String,
    blockchainStatus: BlockchainFeatureStatus,
    nodeStatus: NodeFeatureStatus,
    activationHeight: Option[Height],
    supportingBlocks: Option[Int]
)
