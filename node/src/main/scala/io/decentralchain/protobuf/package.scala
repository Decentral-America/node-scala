package io.decentralchain

import com.google.protobuf.ByteString
import com.decentralchain.account.{Address, AddressScheme, PublicKey}
import com.decentralchain.common.state.ByteStr
import io.decentralchain.protobuf.transaction.PBRecipients
import com.decentralchain.state.TransactionId
import com.decentralchain.transaction.Asset
import com.decentralchain.transaction.Asset.{IssuedAsset, Dcc}

import scala.annotation.targetName

package object protobuf {
  extension (bs: ByteStr) def toByteString: ByteString = ByteString.copyFrom(bs.arr)

  extension (txId: TransactionId) {
    @targetName("txIdToByteString") def toByteString: ByteString = ByteString.copyFrom(txId.arr)
  }

  extension (a: Address) def toByteString: ByteString = ByteString.copyFrom(a.bytes)

  extension (pk: PublicKey) {
    @targetName("publicKeyToByteString") def toByteString: ByteString = ByteString.copyFrom(pk.arr)
  }

  extension (bs: ByteString) {
    def toByteStr: ByteStr                                                = ByteStr(bs.toByteArray)
    def toTxId: TransactionId                                             = TransactionId(toByteStr)
    def toIssuedAssetId: IssuedAsset                                      = IssuedAsset(ByteStr(bs.toByteArray))
    def toAssetId: Asset                                                  = if (bs.isEmpty) Dcc else toIssuedAssetId
    def toPublicKey: PublicKey                                            = PublicKey(bs.toByteArray)
    def toAddress(chainId: Byte = AddressScheme.current.chainId): Address =
      PBRecipients
        .toAddress(bs.toByteArray, chainId)
        .fold(ve => throw new IllegalArgumentException(ve.toString), identity)
    def toIssuedAsset: Asset.IssuedAsset = Asset.IssuedAsset(toByteStr)
  }
}
