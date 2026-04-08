package com.decentralchain.api.http.requests

import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Proofs
import com.decentralchain.transaction.lease.LeaseCancelTransaction
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

case class LeaseCancelRequest(
    version: Option[Byte],
    senderPublicKey: String,
    leaseId: String,
    fee: Long,
    timestamp: Option[Long],
    signature: Option[ByteStr],
    proofs: Option[Proofs],
    chainId: Byte
) extends TxBroadcastRequest[LeaseCancelTransaction] {
  def toTx: Either[ValidationError, LeaseCancelTransaction] =
    for {
      validProofs  <- toProofs(signature, proofs)
      validLeaseId <- parseBase58(leaseId, "invalid.leaseTx", DigestStringLength)
      validSender  <- PublicKey.fromBase58String(senderPublicKey)
      tx <- LeaseCancelTransaction.create(
        version.getOrElse(1.toByte),
        validSender,
        validLeaseId,
        fee,
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object LeaseCancelRequest {
  import com.decentralchain.utils.byteStrFormat
  given Format[LeaseCancelRequest] = Format(
    ((JsPath \ "version").readNullable[Byte] and
      (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "leaseId").read[String].orElse((JsPath \ "txId").read[String]) and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").readNullable[Long] and
      (JsPath \ "signature").readNullable[ByteStr] and
      (JsPath \ "proofs").readNullable[Proofs] and
      (JsPath \ "chainId").readWithDefault(AddressScheme.current.chainId))(LeaseCancelRequest.apply),
    Json.writes[LeaseCancelRequest]
  )
}
