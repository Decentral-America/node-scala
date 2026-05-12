package com.decentralchain.transaction.validation.impl

import cats.syntax.validated.*
import com.decentralchain.transaction.TxValidationError.NegativeMinFee
import com.decentralchain.transaction.assets.SponsorFeeTransaction
import com.decentralchain.transaction.validation.{TxValidator, ValidatedV}

object SponsorFeeTxValidator extends TxValidator[SponsorFeeTransaction] {
  override def validate(tx: SponsorFeeTransaction): ValidatedV[SponsorFeeTransaction] = tx.validNel

  def checkMinSponsoredAssetFee(minSponsoredAssetFee: Option[Long]): Either[NegativeMinFee, Unit] =
    Either.cond(minSponsoredAssetFee.forall(_ > 0), (), NegativeMinFee(minSponsoredAssetFee.get, "asset"))
}
