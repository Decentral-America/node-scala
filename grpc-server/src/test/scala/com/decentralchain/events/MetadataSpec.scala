package com.decentralchain.events

import com.decentralchain.common.state.ByteStr
import com.decentralchain.events.FakeObserver.*
import io.decentralchain.events.api.grpc.protobuf.SubscribeRequest
import io.decentralchain.events.protobuf.TransactionMetadata
import io.decentralchain.protobuf.*
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.RideV6
import com.decentralchain.transaction.assets.exchange.*
import com.decentralchain.transaction.utils.EthConverters.*
import com.decentralchain.transaction.{Asset, EthTxGenerator, TxExchangeAmount, TxHelpers, TxMatcherFee, TxOrderPrice}

class MetadataSpec extends FreeSpec with WithBUDomain {
  "BlockchainUpdates returns correct metadata for supported transaction types" in withDomainAndRepo(RideV6) { (d, r) =>
    val genesisAddress = TxHelpers.signer(100)

    d.appendBlock(TxHelpers.genesis(genesisAddress.toAddress, 100000.dcc))

    val issuer = TxHelpers.signer(50)
    val issue  = TxHelpers.issue(issuer, decimals = 2)

    val matcher = TxHelpers.signer(200)

    val dccOrder = TxHelpers.order(
      OrderType.SELL,
      issue.asset,
      Asset.Dcc,
      amount = 100L,
      price = 1.dcc,
      sender = issuer,
      matcher = matcher
    )

    val ethOrderSender = TxHelpers.signer(202).toEthKeyPair
    val ethOrder       = Order(
      4.toByte,
      OrderAuthentication.Eip712Signature(ByteStr(new Array[Byte](64))),
      matcher.publicKey,
      AssetPair(issue.asset, Asset.Dcc),
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(100L),
      TxOrderPrice.unsafeFrom(1.dcc),
      System.currentTimeMillis(),
      System.currentTimeMillis() + 10000,
      TxMatcherFee.unsafeFrom(0.003.dcc)
    )

    val signedEthOrder = ethOrder.copy(
      orderAuthentication = OrderAuthentication.Eip712Signature(ByteStr(EthOrders.signOrder(ethOrder, ethOrderSender)))
    )

    val exchange = TxHelpers.exchange(
      signedEthOrder,
      dccOrder,
      matcher,
      100L,
      1.dcc,
      version = 3.toByte
    )

    val massTransfer = TxHelpers.massTransfer(
      genesisAddress,
      Seq(
        issuer.toAddress            -> 100.dcc,
        matcher.toAddress           -> 100.dcc,
        ethOrderSender.toDccAddress -> 100.dcc
      ),
      fee = 0.003.dcc
    )

    val ethTransfer = EthTxGenerator.generateEthTransfer(
      ethOrderSender,
      matcher.toAddress,
      10,
      issue.asset,
      0.005.dcc
    )

    d.appendBlock(
      massTransfer,
      issue,
      exchange,
      ethTransfer
    )

    val txMetadata = r
      .createFakeObserver(SubscribeRequest.of(1, 2))
      .fetchAllEvents(d.blockchain, 2)
      .map(_.getUpdate.getAppend.transactionsMetadata)

    txMetadata shouldEqual Seq(
      Seq(
        TransactionMetadata()
      ),
      Seq(
        TransactionMetadata(
          genesisAddress.toAddress.toByteString,
          TransactionMetadata.Metadata.MassTransfer(
            TransactionMetadata.MassTransferMetadata(
              Seq(issuer.toAddress, matcher.toAddress, ethOrderSender.toDccAddress).map(_.toByteString)
            )
          )
        ),
        TransactionMetadata(
          issuer.toAddress.toByteString
        ),
        TransactionMetadata(
          matcher.toAddress.toByteString,
          TransactionMetadata.Metadata.Exchange(
            TransactionMetadata.ExchangeMetadata(
              Seq(exchange.order1.id().toByteString, exchange.order2.id().toByteString),
              Seq(exchange.order1.senderAddress.toByteString, exchange.order2.senderAddress.toByteString),
              Seq(exchange.order1.senderPublicKey.toByteString, exchange.order2.senderPublicKey.toByteString)
            )
          )
        ),
        TransactionMetadata(
          ethOrderSender.toDccAddress.toByteString,
          TransactionMetadata.Metadata.Ethereum(
            TransactionMetadata.EthereumMetadata(
              ethTransfer.timestamp,
              ethTransfer.fee,
              ethTransfer.sender.toByteString,
              TransactionMetadata.EthereumMetadata.Action.Transfer(
                TransactionMetadata.EthereumTransferMetadata(
                  matcher.toAddress.toByteString,
                  Some(Amount(issue.assetId.toByteString, 10))
                )
              )
            )
          )
        )
      )
    )
  }

}
