package com.decentralchain.settings

import com.typesafe.config.{Config, ConfigFactory}
import com.decentralchain.metrics.Metrics
import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class DCCSettings(
    directory: String,
    ntpServer: String,
    maxTxErrorLogSize: Int,
    dbSettings: DBSettings,
    extensions: Seq[String],
    extensionsShutdownTimeout: FiniteDuration,
    networkSettings: NetworkSettings,
    walletSettings: WalletSettings,
    blockchainSettings: BlockchainSettings,
    minerSettings: MinerSettings,
    hotStuffSettings: HotStuffSettings,
    restAPISettings: RestAPISettings,
    synchronizationSettings: SynchronizationSettings,
    utxSettings: UtxSettings,
    featuresSettings: FeaturesSettings,
    rewardsSettings: RewardsVotingSettings,
    metrics: Metrics.Settings,
    enableLightMode: Boolean,
    config: Config
)

object DCCSettings {
  def fromRootConfig(rootConfig: Config): DCCSettings = {
    val dcc             = rootConfig.getConfig("dcc")
    val dccConfigSource = ConfigSource.fromConfig(dcc)

    val directory                 = dccConfigSource.at("directory").loadOrThrow[String]
    val ntpServer                 = dccConfigSource.at("ntp-server").loadOrThrow[String]
    val maxTxErrorLogSize         = dccConfigSource.at("max-tx-error-log-size").loadOrThrow[Int]
    val dbSettings                = dccConfigSource.at("db").loadOrThrow[DBSettings]
    val extensions                = dccConfigSource.at("extensions").loadOrThrow[Seq[String]]
    val extensionsShutdownTimeout = dccConfigSource.at("extensions-shutdown-timeout").loadOrThrow[FiniteDuration]
    val networkSettings           = dccConfigSource.at("network").loadOrThrow[NetworkSettings]
    val walletSettings            = dccConfigSource.at("wallet").loadOrThrow[WalletSettings]
    val blockchainSettings        = dccConfigSource.at("blockchain").loadOrThrow[BlockchainSettings]
    val minerSettings             = dccConfigSource.at("miner").loadOrThrow[MinerSettings]
    val hotStuffSettings          = dccConfigSource.at("hotstuff").loadOrThrow[HotStuffSettings]
    val restAPISettings           = dccConfigSource.at("rest-api").loadOrThrow[RestAPISettings]
    val synchronizationSettings   = dccConfigSource.at("synchronization").loadOrThrow[SynchronizationSettings]
    val utxSettings               = dccConfigSource.at("utx").loadOrThrow[UtxSettings]
    val featuresSettings          = dccConfigSource.at("features").loadOrThrow[FeaturesSettings]
    val rewardsSettings           = dccConfigSource.at("rewards").loadOrThrow[RewardsVotingSettings]
    val metrics                   =
      ConfigSource.fromConfig(rootConfig).at("metrics").loadOrThrow[Metrics.Settings] // NOTE: Metrics config is outside dcc {} root — known structure
    val enableLightMode = dccConfigSource.at("enable-light-mode").loadOrThrow[Boolean]

    DCCSettings(
      directory,
      ntpServer,
      maxTxErrorLogSize,
      dbSettings,
      extensions,
      extensionsShutdownTimeout,
      networkSettings,
      walletSettings,
      blockchainSettings,
      minerSettings,
      hotStuffSettings,
      restAPISettings,
      synchronizationSettings,
      utxSettings,
      featuresSettings,
      rewardsSettings,
      metrics,
      enableLightMode,
      rootConfig
    )
  }

  def default(): DCCSettings = fromRootConfig(ConfigFactory.load())
}
