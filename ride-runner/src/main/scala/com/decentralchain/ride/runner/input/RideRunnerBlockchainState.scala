package com.decentralchain.ride.runner.input

import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.ride.runner.input.RideRunnerInputParser.given
import com.decentralchain.state.Height
import com.decentralchain.transaction.Asset.IssuedAsset
import pureconfig.ConfigReader
import pureconfig.ConfigReader.Result
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

case class RideRunnerBlockchainState(
    height: Int = 3296626,
    finalizationHeight: Option[Int] = None,
    features: Set[Short] = BlockchainFeatures.implemented,
    accounts: Map[Address, RideRunnerAccount] = Map.empty,
    assets: Map[IssuedAsset, RideRunnerAsset] = Map.empty,
    blocks: Map[Int, RideRunnerBlock] = Map.empty,
    transactions: Map[ByteStr, RideRunnerTransaction] = Map.empty
) {
  val solidFinalizationHeight: Height = Height(finalizationHeight.getOrElse(height - 1).max(1))
}

object RideRunnerBlockchainState {
  // This given is required for default args to work.
  // Details: https://github.com/pureconfig/pureconfig/issues/1673
  // Note: the proposed approach with `extension` doesn't work.
  given ConfigReader[RideRunnerBlockchainState] = deriveReader
}
