package com.decentralchain.state.patch

import cats.implicits.catsSyntaxSemigroup
import com.decentralchain.account.Address
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.state.{Blockchain, Height, LeaseBalance, LeaseDetails, StateSnapshot}
import play.api.libs.json.{Json, OFormat}

case object CancelAllLeases extends PatchAtHeight() {
  private[patch] case class LeaseData(senderPublicKey: String, amount: Long, recipient: String, id: String)

  private[patch] case class CancelledLeases(balances: Map[Address, LeaseBalance], cancelledLeases: Seq[LeaseData]) {
    private val height                                                                = Height(patchHeight.getOrElse(0))
    val leaseStates: Map[ByteStr, LeaseDetails.Status & LeaseDetails.Status.Inactive] = cancelledLeases.map { data =>
      (ByteStr.decodeBase58(data.id).get, LeaseDetails.Status.Expired(height))
    }.toMap
  }

  private[patch] object CancelledLeases {
    implicit val dataFormat: OFormat[LeaseData]       = Json.format[LeaseData]
    implicit val jsonFormat: OFormat[CancelledLeases] = Json.format[CancelledLeases]
  }

  def apply(blockchain: Blockchain): StateSnapshot = {
    val patch = readPatchData[CancelledLeases]()
    StateSnapshot.ofLeaseBalances(patch.balances, blockchain).explicitGet() |+| StateSnapshot(cancelledLeases = patch.leaseStates)
  }
}
