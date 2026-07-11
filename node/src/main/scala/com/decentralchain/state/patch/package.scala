package com.decentralchain.state

import com.decentralchain.account.Address
import com.decentralchain.common.utils.EitherExt2.*
import play.api.libs.json.{Json, OFormat, Reads}

package object patch {
  implicit val leaseBalanceFormat: OFormat[LeaseBalance]        = Json.format[LeaseBalance]
  implicit val leaseMapReads: Reads[Map[Address, LeaseBalance]] =
    implicitly[Reads[Map[String, LeaseBalance]]]
      .map(_.map { case (addrStr, balance) => (Address.fromString(addrStr).explicitGet(), balance) })

}
