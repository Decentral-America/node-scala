package com.decentralchain.it

import com.decentralchain.account.{Address, AddressOrAlias, Alias}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.v1.traits.domain.Recipient

package object util {
  implicit class AddressOrAliasExt(val a: AddressOrAlias) extends AnyVal {
    def toRide: Recipient =
      a match {
        case address: Address => Recipient.Address(ByteStr(address.bytes))
        case alias: Alias     => Recipient.Alias(alias.name)
      }
  }
}
