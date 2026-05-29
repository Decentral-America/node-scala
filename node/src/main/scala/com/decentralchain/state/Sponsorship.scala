package com.decentralchain.state

import cats.kernel.Monoid
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.state.diffs.FeeValidation
import com.decentralchain.transaction.Asset.IssuedAsset
import com.decentralchain.transaction.{Asset, Transaction}
import play.api.libs.json.{JsNumber, Writes}

sealed abstract class Sponsorship
case class SponsorshipValue(minFee: Long) extends Sponsorship
case object SponsorshipNoInfo             extends Sponsorship

object Sponsorship {
  implicit val writesValue: Writes[SponsorshipValue] = Writes[SponsorshipValue](v => JsNumber(v.minFee))

  implicit val sponsorshipMonoid: Monoid[Sponsorship] = new Monoid[Sponsorship] {
    override def empty: Sponsorship = SponsorshipNoInfo

    override def combine(x: Sponsorship, y: Sponsorship): Sponsorship = y match {
      case SponsorshipNoInfo => x
      case _                 => y
    }
  }

  def calcDccFeeAmount(tx: Transaction, getSponsorship: IssuedAsset => Option[Long]): Long = tx.assetFee match {
    case (asset @ IssuedAsset(_), amountInAsset) =>
      val sponsorship = getSponsorship(asset).getOrElse(0L)
      Sponsorship.toDcc(amountInAsset, sponsorship)

    case (Asset.Dcc, amountInDcc) =>
      amountInDcc
  }

  def sponsoredFeesSwitchHeight(blockchain: Blockchain): Height =
    blockchain
      .featureActivationHeight(BlockchainFeatures.FeeSponsorship)
      .map(h => h + blockchain.settings.functionalitySettings.activationWindowSize(h.toInt))
      .getOrElse(Height(Int.MaxValue))

  def toDcc(assetFee: Long, sponsorship: Long): Long =
    if (sponsorship == 0) Long.MaxValue
    else {
      val dcc = BigInt(assetFee) * FeeValidation.FeeUnit / sponsorship
      dcc.bigInteger.longValueExact()
    }

  def fromDcc(dccFee: Long, sponsorship: Long): Long =
    if (dccFee == 0 || sponsorship == 0) 0
    else {
      val assetFee = BigInt(dccFee) * sponsorship / FeeValidation.FeeUnit
      assetFee.bigInteger.longValueExact()
    }
}
