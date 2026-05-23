package com.decentralchain.it.util

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.google.protobuf.ByteString
import com.decentralchain.account.{AddressScheme, KeyPair}
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.*
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto
import io.decentralchain.protobuf.Amount
import io.decentralchain.protobuf.transaction.{MassTransferTransactionData, PBTransaction}
import io.decentralchain.protobuf.utils.PBImplicitConversions.*
import io.decentralchain.protobuf.utils.PBUtils
import com.decentralchain.serialization.Deser
import com.decentralchain.transaction.transfer.MassTransferTransaction
import com.decentralchain.transaction.{Asset, TxVersion}

object TxHelpers {
  def massTransferBodyBytes(
      sender: KeyPair,
      assetId: Option[String],
      transfers: Seq[MassTransferTransactionData.Transfer],
      attachment: ByteString,
      fee: Long,
      timestamp: Long,
      version: Int = 1
  ): ByteStr = {
    val bodyBytes = version match {
      case TxVersion.V1 =>
        val transferBytes = transfers.map { t =>
          Bytes.concat(t.getRecipient.toAddressOrAlias(AddressScheme.current.chainId).explicitGet().bytes, Longs.toByteArray(t.amount))
        }

        Bytes.concat(
          Array(MassTransferTransaction.typeId, version.toByte),
          sender.publicKey.arr,
          Asset.fromString(assetId).byteRepr,
          Shorts.toByteArray(transfers.size.toShort),
          Bytes.concat(transferBytes*),
          Longs.toByteArray(timestamp),
          Longs.toByteArray(fee),
          Deser.serializeArrayWithLength(attachment.toByteArray)
        )

      case _ =>
        val unsigned = PBTransaction(
          AddressScheme.current.chainId,
          ByteString.copyFrom(sender.publicKey.arr),
          Some(Amount.of(ByteString.EMPTY, fee)),
          timestamp,
          version,
          PBTransaction.Data.MassTransfer(
            MassTransferTransactionData.of(
              if (assetId.isDefined) ByteString.copyFrom(Base58.decode(assetId.get)) else ByteString.EMPTY,
              transfers,
              attachment
            )
          )
        )

        PBUtils.encodeDeterministic(unsigned)
    }

    crypto.sign(sender.privateKey, bodyBytes)
  }

}
