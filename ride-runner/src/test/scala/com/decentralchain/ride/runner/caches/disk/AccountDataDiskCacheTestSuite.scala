package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.account.Address
import com.decentralchain.database.AddressId
import com.decentralchain.ride.runner.db.{Heights, ReadOnly, ReadWrite}
import com.decentralchain.state.{BooleanDataEntry, DataEntry}

class AccountDataDiskCacheTestSuite extends DiskCacheWithHistoryTestSuite[(Address, String), DataEntry[?]] {
  private val defaultAddressId                         = AddressId(0L) // There is only one addressId
  private val defaultPairDataKey                       = "foo"
  protected override val defaultKey: (Address, String) = (aliceAddr, defaultPairDataKey)
  protected override val defaultValue: DataEntry[?]    = BooleanDataEntry(defaultPairDataKey, value = true)

  protected override def test(f: DiskCache[(Address, String), DataEntry[?]] => ReadWrite => Unit): Unit = withDb { db =>
    db.directReadWrite { implicit ctx =>
      f(DefaultDiskCaches(db).accountDataEntries)(ctx)
    }
  }

  override protected def getHistory(implicit ctx: ReadOnly): Heights =
    ctx
      .getOpt(KvPairs.AccountDataEntriesHistory.at((defaultAddressId, defaultPairDataKey)))
      .getOrElse(Vector.empty)
}
