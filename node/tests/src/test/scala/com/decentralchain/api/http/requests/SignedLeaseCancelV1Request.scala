package com.decentralchain.api.http.requests

import com.decentralchain.account.PublicKey
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.Proofs
import com.decentralchain.transaction.lease.LeaseCancelTransaction
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

case class SignedLeaseCancelV1Request(
    senderPublicKey: String,
    leaseId: String,
    timestamp: Long,
    signature: String,
    fee: Long
) {
  def toTx: Either[ValidationError, LeaseCancelTransaction] =
    for {
      _sender    <- PublicKey.fromBase58String(senderPublicKey)
      _signature <- parseBase58(signature, "invalid.signature", SignatureStringLength)
      _leaseTx   <- parseBase58(leaseId, "invalid.leaseTx", SignatureStringLength)
      _t         <- LeaseCancelTransaction.create(1.toByte, _sender, _leaseTx, fee, timestamp, Proofs(_signature))
    } yield _t
}

object SignedLeaseCancelV1Request {
  implicit val reads: Reads[SignedLeaseCancelV1Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "txId").read[String].orElse((JsPath \ "leaseId").read[String]) and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "signature").read[String] and
      (JsPath \ "fee").read[Long]
  )(SignedLeaseCancelV1Request.apply)

  implicit val writes: Writes[SignedLeaseCancelV1Request] = Json.writes[SignedLeaseCancelV1Request]
}
