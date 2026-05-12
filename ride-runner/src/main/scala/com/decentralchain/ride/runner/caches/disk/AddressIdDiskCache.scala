package com.decentralchain.ride.runner.caches.disk

import com.decentralchain.account.Address
import com.decentralchain.database.AddressId
import com.decentralchain.ride.runner.db.{ReadOnly, ReadWrite}

trait AddressIdDiskCache {
  def getAddress(addressId: AddressId)(implicit ctx: ReadOnly): Option[Address]
  def getAddressId(address: Address)(implicit ctx: ReadOnly): Option[AddressId]

  def getOrMkAddressId(address: Address)(implicit ctx: ReadWrite): AddressId
}
