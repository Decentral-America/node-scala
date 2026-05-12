package com.decentralchain.database

import com.google.common.util.concurrent.MoreExecutors
import com.decentralchain.events.BlockchainUpdateTriggers
import com.decentralchain.settings.DCCSettings
import com.decentralchain.state.BlockchainUpdaterImpl
import com.decentralchain.utils.Time

object TestStorageFactory {
  def apply(
      settings: DCCSettings,
      rdb: RDB,
      time: Time,
      blockchainUpdateTriggers: BlockchainUpdateTriggers
  ): (BlockchainUpdaterImpl, RocksDBWriter) = {
    val rocksDBWriter: RocksDBWriter = RocksDBWriter(
      rdb,
      settings.blockchainSettings,
      settings.dbSettings,
      settings.enableLightMode,
      Some(MoreExecutors.newDirectExecutorService())
    )
    (
      new BlockchainUpdaterImpl(rocksDBWriter, settings, time, blockchainUpdateTriggers, loadActiveLeases(rdb, _, _)),
      rocksDBWriter
    )
  }
}
