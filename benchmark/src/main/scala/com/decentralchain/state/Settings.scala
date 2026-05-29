package com.decentralchain.state

import com.typesafe.config.Config
import pureconfig.*

case class Settings(
    networkConfigFile: String,
    aliasesFile: String,
    restTxsFile: String,
    blocksFile: String,
    accountsFile: String,
    assetsFile: String,
    dataFile: String
) derives ConfigReader

object Settings {
  def fromConfig(config: Config): Settings = {
    ConfigSource.fromConfig(config).at("dcc.benchmark.state").loadOrThrow[Settings]
  }
}
