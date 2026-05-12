package com.decentralchain.transaction.validation.impl

import cats.data.ValidatedNel
import cats.syntax.either.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.lease.LeaseCancelTransaction
import com.decentralchain.transaction.validation.TxValidator

object LeaseCancelTxValidator extends TxValidator[LeaseCancelTransaction] {
  override def validate(tx: LeaseCancelTransaction): ValidatedNel[ValidationError, LeaseCancelTransaction] = {
    import tx.*
    V.seq(tx)(
      checkLeaseId(leaseId).toValidatedNel
    )
  }

  def checkLeaseId(leaseId: ByteStr): Either[GenericError, Unit] =
    Either.cond(
      leaseId.arr.length == crypto.DigestLength,
      (),
      GenericError(s"Lease id=$leaseId has invalid length = ${leaseId.arr.length} byte(s) while expecting ${crypto.DigestLength}")
    )
}
