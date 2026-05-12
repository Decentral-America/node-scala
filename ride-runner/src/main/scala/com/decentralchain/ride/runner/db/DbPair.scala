package com.decentralchain.ride.runner.db

import com.decentralchain.database.DBEntry
import com.decentralchain.ride.runner.caches.disk.KvPair

class DbPair[KeyT, ValueT](kvPair: KvPair[KeyT, ValueT], val dbEntry: DBEntry) {
  lazy val key: KeyT     = kvPair.parseKey(dbEntry.getKey)
  lazy val value: ValueT = kvPair.parseValue(dbEntry.getValue)
}
