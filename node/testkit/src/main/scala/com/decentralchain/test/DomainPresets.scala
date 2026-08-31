package com.decentralchain.test

import com.decentralchain.features.{BlockchainFeature, BlockchainFeatures}
import com.decentralchain.lang.directives.values.*
import com.decentralchain.settings.{FunctionalitySettings, DCCSettings, loadConfig}
import com.decentralchain.transaction.TxHelpers

object DomainPresets {
  implicit class DCCSettingsOps(val ws: DCCSettings) extends AnyVal {
    def configure(transformF: FunctionalitySettings => FunctionalitySettings): DCCSettings = {
      val functionalitySettings = transformF(ws.blockchainSettings.functionalitySettings)
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = functionalitySettings))
    }

    def withFeatures(fs: BlockchainFeature*): DCCSettings =
      configure(_.copy(preActivatedFeatures = fs.map(_.id -> 0).toMap))

    def addFeatures(fs: BlockchainFeature*): DCCSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map(_.id -> 0)
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def setFeaturesHeight(fs: (BlockchainFeature, Int)*): DCCSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map { case (f, height) => (f.id, height) }
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def withActivationPeriod(period: Int): DCCSettings =
      configure(_.copy(featureCheckBlocksPeriod = period, blocksForFeatureActivation = period, doubleFeaturesPeriodsAfterHeight = 10000))

    def noFeatures(): DCCSettings = {
      ws.copy(
        blockchainSettings = ws.blockchainSettings.copy(
          functionalitySettings = ws.blockchainSettings.functionalitySettings
            .copy(preActivatedFeatures = Map.empty)
        ),
        featuresSettings = ws.featuresSettings.copy(supported = Nil)
      )
    }
  }

  lazy val SettingsFromDefaultConfig: DCCSettings = DCCSettings.fromRootConfig(loadConfig(None))

  def domainSettingsWithFS(fs: FunctionalitySettings): DCCSettings =
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(functionalitySettings = fs)
    )

  def domainSettingsWithPreactivatedFeatures(fs: BlockchainFeature*): DCCSettings =
    domainSettingsWithFeatures(fs.map(_ -> 0)*)

  def domainSettingsWithFeatures(fs: (BlockchainFeature, Int)*): DCCSettings = {
    val defaultFS = SettingsFromDefaultConfig
      .noFeatures()
      .blockchainSettings
      .functionalitySettings
      .copy(lightNodeBlockFieldsAbsenceInterval = 0)

    domainSettingsWithFS(defaultFS.copy(preActivatedFeatures = fs.map { case (f, h) =>
      f.id -> h
    }.toMap))
  }

  val NG: DCCSettings = domainSettingsWithPreactivatedFeatures(
    BlockchainFeatures.MassTransfer, // Removes limit of 100 transactions per block
    BlockchainFeatures.NG
  )

  val ScriptsAndSponsorship: DCCSettings = NG
    .addFeatures(
      BlockchainFeatures.SmartAccounts,
      BlockchainFeatures.SmartAccountTrading,
      BlockchainFeatures.OrderV3,
      BlockchainFeatures.FeeSponsorship,
      BlockchainFeatures.DataTransaction,
      BlockchainFeatures.SmartAssets
    )
    .setFeaturesHeight(
      BlockchainFeatures.FeeSponsorship -> -NG.blockchainSettings.functionalitySettings.activationWindowSize(1)
    )

  val RideV3: DCCSettings = ScriptsAndSponsorship.addFeatures(
    BlockchainFeatures.Ride4DApps
  )

  val RideV4: DCCSettings = RideV3.addFeatures(
    BlockchainFeatures.BlockReward,
    BlockchainFeatures.BlockV5
  )

  val RideV4WithRewards: DCCSettings = RideV4.addFeatures(BlockchainFeatures.BlockReward)

  val RideV5: DCCSettings = RideV4.addFeatures(BlockchainFeatures.SynchronousCalls)

  val RideV6: DCCSettings = RideV5.addFeatures(BlockchainFeatures.RideV6)

  val ConsensusImprovements: DCCSettings = RideV6.addFeatures(BlockchainFeatures.ConsensusImprovements)

  // BlockRewardDistribution requires non-None daoAddress and xtnBuybackAddress for the
  // 3-way reward split. FunctionalitySettings.TESTNET no longer provides these (they were
  // Waves-specific governance addresses). Derive deterministic test addresses from fixed nonces
  // using the current AddressScheme so they are valid regardless of network configuration.
  private lazy val testDaoAddress        = TxHelpers.address(1001).toString
  private lazy val testXtnBuybackAddress = TxHelpers.address(1002).toString

  val BlockRewardDistribution: DCCSettings = ConsensusImprovements
    .addFeatures(BlockchainFeatures.BlockRewardDistribution)
    .configure(_.copy(daoAddress = Some(testDaoAddress), xtnBuybackAddress = Some(testXtnBuybackAddress)))

  val ContinuationTransaction: DCCSettings = RideV6
    .addFeatures(BlockchainFeatures.ContinuationTransaction)
    .copy(
      featuresSettings = RideV6.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false)
    )

  val TransactionStateSnapshot: DCCSettings = BlockRewardDistribution.addFeatures(BlockchainFeatures.LightNode)

  val DeterministicFinality: DCCSettings = TransactionStateSnapshot.addFeatures(BlockchainFeatures.DeterministicFinality)

  def settingsForRide(version: StdLibVersion): DCCSettings =
    version match {
      case V1 => RideV3
      case V2 => RideV3
      case V3 => RideV3
      case V4 => RideV4
      case V5 => RideV5
      case V6 => RideV6
      case V7 => BlockRewardDistribution
      case V8 => TransactionStateSnapshot
      case V9 => DeterministicFinality
    }

  def mostRecent: DCCSettings = RideV6
}
