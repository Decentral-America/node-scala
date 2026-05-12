package com.decentralchain.api.common.lease

import com.google.common.collect.AbstractIterator
import com.decentralchain.common.state.ByteStr
import com.decentralchain.database
import com.decentralchain.database.{AddressId, DBResource, Keys, RDB}
import com.decentralchain.state.LeaseDetails

private class LeaseByAddressIterator(resource: DBResource, apiHandle: RDB.ApiHandle, addressId: AddressId)
    extends AbstractIterator[Seq[(ByteStr, LeaseDetails)]] {
  private val seqNr = resource.get(Keys.addressLeaseSeqNr(addressId, apiHandle))
  resource.withSafePrefixIterator(_.seekForPrev(Keys.addressLeaseSeq(addressId, seqNr, apiHandle).keyBytes))(())

  final override def computeNext(): Seq[(ByteStr, LeaseDetails)] =
    resource.withSafePrefixIterator { iterator =>
      if (iterator.isValid) {
        val details = for {
          id      <- database.readLeaseIdSeq(iterator.value())
          details <- database.loadLease(resource, id) if details.isActive
        } yield (id, details)
        iterator.prev()
        details
      } else endOfData()
    }(endOfData())
}
