package io.decentralchain.protobuf.transaction

import com.decentralchain.transaction as vt
import com.decentralchain.account.{AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.lang.ValidationError
import io.decentralchain.protobuf.*
import io.decentralchain.protobuf.order.AssetPair
import io.decentralchain.protobuf.order.Order.{PriceMode, Sender}
import io.decentralchain.protobuf.order.Order.PriceMode.{ASSET_DECIMALS, FIXED_DECIMALS, DEFAULT as DEFAULT_PRICE_MODE}
import com.decentralchain.transaction.assets.exchange.OrderPriceMode.{AssetDecimals, FixedDecimals, Default as DefaultPriceMode}
import vt.assets.exchange.OrderAuthentication
import com.decentralchain.transaction.TxValidationError.GenericError
import com.decentralchain.transaction.assets.exchange.OrderType
import com.decentralchain.transaction.{TxExchangeAmount, TxMatcherFee, TxOrderPrice}

object PBOrders {
  import io.decentralchain.protobuf.utils.PBImplicitConversions.*

  def vanilla(order: PBOrder): Either[ValidationError, VanillaOrder] =
    for {
      amount     <- TxExchangeAmount(order.amount)(GenericError(TxExchangeAmount.errMsg))
      price      <- TxOrderPrice(order.price)(GenericError(TxOrderPrice.errMsg))
      orderType  <- vanillaOrderType(order.orderSide)
      matcherFee <- TxMatcherFee(order.getMatcherFee.longAmount)(GenericError(TxMatcherFee.errMsg))
    } yield {
      VanillaOrder(
        order.version.toByte,
        order.sender match {
          case Sender.SenderPublicKey(value) => OrderAuthentication.OrderProofs(PublicKey(value.toByteStr), order.proofs.map(_.toByteStr))
          case Sender.Eip712Signature(sig)   => OrderAuthentication.Eip712Signature(sig.toByteStr)
          case Sender.Empty                  => throw new IllegalArgumentException("Order should have either senderPublicKey or eip712Signature")
        },
        PublicKey(order.matcherPublicKey.toByteArray),
        vt.assets.exchange
          .AssetPair(PBAmounts.toVanillaAssetId(order.getAssetPair.amountAssetId), PBAmounts.toVanillaAssetId(order.getAssetPair.priceAssetId)),
        orderType,
        amount,
        price,
        order.timestamp,
        order.expiration,
        matcherFee,
        PBAmounts.toVanillaAssetId(order.getMatcherFee.assetId),
        order.priceMode match {
          case DEFAULT_PRICE_MODE        => DefaultPriceMode
          case ASSET_DECIMALS            => AssetDecimals
          case FIXED_DECIMALS            => FixedDecimals
          case PriceMode.Unrecognized(v) => throw new IllegalArgumentException(s"Unknown order price mode: $v")
        },
        Option.unless(order.attachment.isEmpty)(order.attachment.toByteStr)
      )
    }

  def protobuf(order: VanillaOrder): PBOrder = {
    PBOrder(
      AddressScheme.current.chainId,
      order.matcherPublicKey.toByteString,
      Some(AssetPair(PBAmounts.toPBAssetId(order.assetPair.amountAsset), PBAmounts.toPBAssetId(order.assetPair.priceAsset))),
      order.orderType match {
        case vt.assets.exchange.OrderType.BUY  => PBOrder.Side.BUY
        case vt.assets.exchange.OrderType.SELL => PBOrder.Side.SELL
      },
      order.amount.value,
      order.price.value,
      order.timestamp,
      order.expiration,
      Some((order.matcherFeeAssetId, order.matcherFee.value)),
      order.version,
      order.proofs.map(_.toByteString),
      order.priceMode match {
        case DefaultPriceMode => DEFAULT_PRICE_MODE
        case AssetDecimals    => ASSET_DECIMALS
        case FixedDecimals    => FIXED_DECIMALS
      },
      order.attachment.getOrElse(ByteStr.empty).toByteString,
      order.orderAuthentication match {
        case OrderAuthentication.OrderProofs(key, _)        => Sender.SenderPublicKey(key.toByteString)
        case OrderAuthentication.Eip712Signature(signature) => Sender.Eip712Signature(signature.toByteString)
      }
    )
  }

  private def vanillaOrderType(orderSide: io.decentralchain.protobuf.order.Order.Side): Either[GenericError, OrderType] =
    orderSide match {
      case PBOrder.Side.BUY             => Right(vt.assets.exchange.OrderType.BUY)
      case PBOrder.Side.SELL            => Right(vt.assets.exchange.OrderType.SELL)
      case PBOrder.Side.Unrecognized(v) => Left(GenericError(s"Unknown order type: $v"))
    }
}
