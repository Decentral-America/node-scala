package com.decentralchain.ride.runner.input

import com.decentralchain.account.PublicKeys.EmptyPublicKey
import com.decentralchain.account.{AddressOrAlias, PublicKey}
import com.decentralchain.transaction.Asset
import com.decentralchain.transaction.Asset.Waves

case class RideRunnerTransaction(
    amount: Long = 1,
    assetId: Asset = Waves,
    fee: Long = 100_000,
    feeAssetId: Asset = Waves,
    recipient: AddressOrAlias,
    senderPublicKey: PublicKey = EmptyPublicKey,
    height: Option[Int] = None,
    timestamp: Long = System.currentTimeMillis(),
    proofs: List[StringOrBytesAsByteArray] = Nil,
    version: Byte = 3,
    attachment: StringOrBytesAsByteArray = StringOrBytesAsByteArray(Array.empty[Byte])
)
