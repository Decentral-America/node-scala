package com.decentralchain.test

import com.decentralchain.database.{RDB, TestStorageFactory}
import com.decentralchain.db.DBCacheSettings
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.events.BlockchainUpdateTriggers
import com.decentralchain.history.Domain
import com.decentralchain.settings.DCCSettings
import com.decentralchain.transaction.TxHelpers
import com.decentralchain.{NTPTime, TestHelpers}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.Files

trait SharedDomain extends BeforeAndAfterAll with NTPTime with DBCacheSettings { suite: Suite =>
  private val path       = Files.createTempDirectory(s"rocks-temp-${getClass.getSimpleName}").toAbsolutePath
  private val rdb        = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))
  private val (bui, ldb) = TestStorageFactory(settings, rdb, ntpTime, BlockchainUpdateTriggers.noop)

  def settings: DCCSettings                 = DomainPresets.ScriptsAndSponsorship
  def genesisBalances: Seq[AddrWithBalance] = Seq.empty

  lazy val domain: Domain = Domain(rdb, bui, ldb, settings)

  override protected def beforeAll(): Unit = {
    val genesisTransactions = genesisBalances.map(ab => TxHelpers.genesis(ab.address, ab.balance))
    if (genesisTransactions.nonEmpty) {
      domain.appendBlock(genesisTransactions*)
    }
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    bui.shutdown()
    ldb.close()
    rdb.close()
    TestHelpers.deleteRecursively(path)
  }
}
