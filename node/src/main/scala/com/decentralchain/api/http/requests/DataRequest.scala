package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.state.DataEntry
import com.decentralchain.transaction.{DataTransaction, Proofs}
import play.api.libs.json.{Format, Json}

case class DataRequest(
    version: Byte,
    senderPublicKey: String,
    data: List[DataEntry[?]],
    fee: Long,
    timestamp: Long,
    proofs: Option[Proofs],
    signature: Option[ByteStr],
    chainId: Byte = AddressScheme.current.chainId
) extends TxBroadcastRequest[DataTransaction] {
  def toTx: Either[ValidationError, DataTransaction] =
    for {
      validProofs <- toProofs(signature, proofs)
      validSender <- PublicKey.fromBase58String(senderPublicKey)
      tx          <- DataTransaction.create(version, validSender, data, fee, timestamp, validProofs, chainId)
    } yield tx

}

object DataRequest {
  given Format[DataRequest] = Json.format
}
