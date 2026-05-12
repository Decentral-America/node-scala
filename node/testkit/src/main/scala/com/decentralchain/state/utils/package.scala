package com.decentralchain.state

import com.decentralchain.account.Address
import com.decentralchain.api.common.AddressTransactions
import com.decentralchain.common.state.ByteStr
import com.decentralchain.database.{RDB, RocksDBWriter, TestStorageFactory}
import com.decentralchain.events.BlockchainUpdateTriggers
import com.decentralchain.settings.TestSettings.*
import com.decentralchain.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings, RewardsSettings, TestSettings}
import com.decentralchain.transaction.Transaction
import com.decentralchain.utils.SystemTime
import monix.execution.Scheduler

package object utils {

  def addressTransactions(
      rdb: RDB,
      snapshot: => Option[(Height, StateSnapshot)],
      address: Address,
      types: Set[Transaction.Type],
      fromId: Option[ByteStr]
  )(implicit s: Scheduler): Seq[(Height, Transaction)] =
    AddressTransactions
      .allAddressTransactions(rdb, snapshot, address, None, types, fromId)
      .map { case (tm, tx, _) => tm.height -> tx }
      .toListL
      .runSyncUnsafe()

  object TestRocksDB {
    def withFunctionalitySettings(
        rdb: RDB,
        fs: FunctionalitySettings
    ): RocksDBWriter =
      TestStorageFactory(
        TestSettings.Default.withFunctionalitySettings(fs),
        rdb,
        SystemTime,
        BlockchainUpdateTriggers.noop
      )._2

    def createTestBlockchainSettings(fs: FunctionalitySettings): BlockchainSettings =
      BlockchainSettings('!', fs, GenesisSettings.TESTNET, RewardsSettings.TESTNET)
  }
}
