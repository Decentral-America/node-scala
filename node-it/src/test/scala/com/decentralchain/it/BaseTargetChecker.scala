package com.decentralchain.it

import com.typesafe.config.ConfigFactory.{defaultApplication, defaultReference}
import com.decentralchain.account.KeyPair
import com.decentralchain.block.Block
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.consensus.PoSSelector
import com.decentralchain.database.RDB
import com.decentralchain.events.BlockchainUpdateTriggers
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.StorageFactory
import com.decentralchain.settings.*
import com.decentralchain.transaction.Asset.Dcc
import com.decentralchain.utils.NTP
import pureconfig.ConfigSource

object BaseTargetChecker {
  def main(args: Array[String]): Unit = {
    val sharedConfig = Docker
      .genesisOverride()
      .withFallback(Docker.configTemplate)
      .withFallback(defaultApplication())
      .withFallback(defaultReference())
      .resolve()

    val settings                       = DCCSettings.fromRootConfig(sharedConfig)
    val db                             = RDB.open(settings.dbSettings.copy(directory = "/tmp/tmp-db"))
    val ntpTime                        = new NTP("ntp.pool.org")
    val (blockchainUpdater, rdbWriter) = StorageFactory(settings, db, ntpTime, BlockchainUpdateTriggers.noop)
    val poSSelector                    = PoSSelector(blockchainUpdater, settings.synchronizationSettings.maxBaseTarget)

    try {
      val genesisBlock =
        Block
          .genesis(
            settings.blockchainSettings.genesisSettings,
            blockchainUpdater.isFeatureActivated(BlockchainFeatures.RideV6),
            blockchainUpdater.isFeatureActivated(BlockchainFeatures.LightNode)
          )
          .explicitGet()
      blockchainUpdater.processBlock(genesisBlock, genesisBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty)

      NodeConfigs.Default.map(_.withFallback(sharedConfig)).collect {
        case cfg if ConfigSource.fromConfig(cfg).at("dcc.miner.enable").loadOrThrow[Boolean] =>
          val account   = KeyPair.fromSeed(cfg.getString("account-seed")).explicitGet()
          val address   = account.toAddress
          val balance   = blockchainUpdater.balance(address, Dcc)
          val timeDelay = poSSelector
            .getValidBlockDelay(blockchainUpdater.height, account, genesisBlock.header.baseTarget, balance)
            .explicitGet()

          f"$address: ${timeDelay * 1e-3}%10.3f s"
      }
    } finally {
      ntpTime.close()
      rdbWriter.close()
      db.close()
    }
  }
}
