package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.account.{Address, Alias}
import com.decentralchain.ride.runner.caches.RemoteData
import com.decentralchain.ride.runner.db.{ReadOnly, ReadWrite}
import com.decentralchain.state.Height

trait AliasDiskCache {
  def getAddress(key: Alias)(implicit ctx: ReadOnly): RemoteData[Address]
  def setAddress(atHeight: Height, key: Alias, address: RemoteData[Address])(implicit ctx: ReadWrite): Unit
  def removeAllFrom(fromHeight: Height)(implicit ctx: ReadWrite): Vector[Alias]
}
