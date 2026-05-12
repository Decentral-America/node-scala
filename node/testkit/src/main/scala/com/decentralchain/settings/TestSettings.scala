package com.decentralchain.settings

import com.typesafe.config.ConfigFactory
import com.decentralchain.features.BlockchainFeatures

object TestSettings {
  val Default: DCCSettings = DCCSettings.fromRootConfig(ConfigFactory.load())

  implicit class DCCSettingsExt(val ws: DCCSettings) extends AnyVal {
    def withFunctionalitySettings(fs: FunctionalitySettings): DCCSettings =
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = fs))

    def withNG: DCCSettings =
      ws.withFunctionalitySettings(
        ws.blockchainSettings.functionalitySettings.copy(
          blockVersion3AfterHeight = 0,
          preActivatedFeatures = ws.blockchainSettings.functionalitySettings.preActivatedFeatures ++ Map(BlockchainFeatures.NG.id -> 0)
        )
      )
  }
}
